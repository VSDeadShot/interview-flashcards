# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Interview Prep Flashcards — a spaced-repetition flashcard app for CS fundamentals interview prep (OS, DBMS, OOP, system design). Two Java halves: a Spring Boot backend that stores cards and progress and runs SM-2 scheduling, and a native Android client where the daily studying happens.

**Both halves are Java, not Kotlin.** That is a deliberate constraint of the project, not an accident of scaffolding — the point is to demonstrate Java competency. Do not introduce Kotlin to either module.

`docs/api-contract.md` is the spec both halves are written against — schema, endpoints, payloads, and the SM-2 golden vectors. Read it before changing the data model or adding an endpoint, and update it in the same change when the contract moves.

**Current state**: backend only. The Android module does not exist yet. Every endpoint in `docs/api-contract.md`'s table is implemented and served over HTTP behind the API-key filter.

## Commands

All from `backend/` (there is no root-level build; the Android module will be a separate Gradle project):

```bash
./gradlew test                  # full suite — no database setup needed, see below
./gradlew build                 # compile + test + jar
./gradlew bootRun               # start the app on :8080 — this one DOES need local Postgres
./gradlew test --tests '*Sm2SchedulerTest'          # single class
./gradlew test --tests '*Sm2SchedulerTest*lapse*'   # single method pattern
```

`bootRun` requires a local PostgreSQL 17 on `127.0.0.1:5432`, database `flashcards`, owned by a non-superuser role `flashcards`, plus the `FLASHCARDS_DB_PASSWORD` environment variable. There is no fallback value for that variable on purpose — a missing one fails startup loudly rather than silently trying a default credential.

`./gradlew test` needs none of that. Tests start their own Postgres (see below).

## Architecture

**Stack**: Java 21 (Temurin), Spring Boot 4.1.0, Gradle 9.5.1, Spring Data JPA/Hibernate, Flyway, PostgreSQL 17, JUnit 5. Group `dev.vsdeadshot`, base package `dev.vsdeadshot.flashcards`.

**Spring Boot 4 renamed the starters.** It is `spring-boot-starter-webmvc`, not `-web`, and the test starter is split per module — `spring-boot-starter-data-jpa-test`, `-webmvc-test`, `-flyway-test`, `-validation-test` — rather than one `spring-boot-starter-test`. Copying dependency snippets from Boot 3 documentation or older answers will not resolve. Tests use plain JUnit `Assertions`; AssertJ is not on the classpath.

### The scheduler is pure, and stays that way

`scheduler/Sm2Scheduler` has no clock, no database, no Spring annotations, and no state. The current date is a **parameter**, which is what makes the golden-vector tests deterministic rather than dependent on when they run. `scheduler/SchedulingState` is an immutable record that validates its own invariants in a compact constructor and is deliberately **not** a JPA entity.

The dependency direction is `domain` → `scheduler`, never back. `Card` exposes exactly two methods that bridge them: `schedulingState()` and `applySchedule(next, reviewedAt)`. Keep it that way — nothing in `scheduler/` should ever import `jakarta.persistence` or Spring.

**The deliberate divergence from DSA Tracker.** DSA Tracker's `calculateSM2` derives the next interval from the previous interval alone. Because a lapse sets that interval to `1`, the next successful review reads it as a card that just passed its first review and jumps to 6 days — so a lapse costs one day and nothing else. This port tracks `repetitions` explicitly and resets it to `0` on a lapse, so recovery runs 1 day → 6 days → ease-scaled. Golden vectors 6 and 7 in `Sm2SchedulerTest` exist specifically to pin this; if you "fix" them to match DSA Tracker you have reintroduced the bug.

**One deviation from the SM-2 paper is kept on purpose**, matching DSA Tracker: a lapse does **not** reduce the ease factor. One bad day should not permanently degrade a card's schedule.

### Persistence

**Flyway owns the schema outright.** `spring.jpa.hibernate.ddl-auto=validate` — Hibernate may never create or alter a table. Schema changes mean a new numbered migration in `src/main/resources/db/migration`; never edit one that has already been applied, which now means both `V1__init.sql` and `V2__idempotency_keys.sql`. `spring.jpa.open-in-view=false`, so lazy associations touched outside a transaction fail loudly instead of firing a silent query per row.

**Data model** (`domain/`): `Topic` (1) → (many) `Card`, and `Card` (1) → (many) `ReviewLog`. Scheduling state is flattened onto `card` rather than living in a side table, because a card and its schedule are strictly 1:1.

- **`review_log` is append-only and is never read to compute a schedule.** The next interval comes from the card's own columns. The log exists so stats and streaks can be reported without replaying anything. Do not derive scheduling from it.
- `ReviewLog.of()` takes both the before and after `SchedulingState` rather than reading the card, because by the time a review is logged the card already holds the *after* values and the *before* would otherwise be silently lost.
- Every table carries `user_id` from day one, even though auth is currently a single shared API key. That is so a real multi-user upgrade needs no data migration — **keep populating and filtering on it** in new queries.
- `DELETE /cards/{id}` archives rather than hard-deleting (`Card.archive()`), so history survives. `idx_card_due` is a *partial* index excluding archived rows, because the study queue is the hot path and never includes them.
- The migration's `check` constraints deliberately restate invariants `SchedulingState` already enforces in Java. That duplication is intentional: the database is the last line of defence against a write that bypasses the scheduler.

**Identity ids insert eagerly.** `GenerationType.IDENTITY` forces Hibernate to execute the INSERT at `persist()` to obtain the generated key — there is nothing to defer. Constraint violations therefore surface at `persist()`, not at the following `flush()`. Tests asserting on a violation must wrap the `persist` call, not just the flush.

Entity `equals`/`hashCode` treat two instances as equal only once both are persisted and share an id, with a constant `hashCode`. This keeps an entity well-behaved in a `Set` across a `save()` call, when the id goes from null to non-null.

### Repositories

**Every finder takes `user_id`.** `findByIdAndUserId` exists alongside the inherited `findById` precisely so a call site cannot forget the ownership filter — a card owned by someone else must read as *not found*, never as *forbidden*. Add new queries the same way rather than filtering in the service.

**`CardRepository.findStudyQueue` spells out `archived = false` on purpose.** It has to line up with `idx_card_due`'s own predicate. Postgres will prove the implication for equivalent spellings — `archived <> true` uses the index too — but it cannot prove anything from a filter that is not there, and a queue query without one silently degrades to a sequential scan *while still returning the right rows*. That is why `CardRepositoryTest.Index` asserts on the `EXPLAIN` output under `enable_seqscan = off` instead of only on the rows. A sibling test pins the hand-written SQL that test explains against the JPQL actually in use, so the two cannot drift.

Cards are ordered `dueDate, id`; the `id` tiebreak keeps same-day cards in a stable order so paging cannot repeat or skip one. The limit is a Spring Data `Limit`, not a `Pageable` — the contract wants a cap, not a page, so there is no count query to pay for.

`ReviewLogRepository` has no write method of its own: the table is append-only, so the inherited `save` is the entire write side. Its reads exist for `/stats` only — **reading the log to compute a schedule is the one thing forbidden**, and asking it what a card's due date *used to be*, as the streak does, is not that.

**The streak's due check is the one query that reconstructs the past.** `due_date` says where a card stands now, so a day that was studied late reads as empty; `CardRepository.existsCardDueOn` rebuilds the due date as it stood at the start of a day from the last review before it. It is native because JPQL cannot express `order by ... limit 1` in a subquery. Its two exclusions — archived cards, and cards created during the day itself — are decisions, documented in `docs/api-contract.md`, not optimisations.

### The web layer

**Controllers are thin on purpose**: read the caller, delegate, map the result to a DTO. No error handling, no ownership checks, no rules of their own. A controller that grows a `try`/`catch` or an `if (!owns(...))` is doing someone else's job.

- **`ApiKeyFilter` publishes the authenticated owner** as the `userId` request attribute, and controllers read it with `@RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE)` rather than from configuration. That is the seam a real subject claim will arrive through when auth stops being one shared key.
- **Ownership stays in the service and the repository.** Something owned by another user reads as *not found*, never *forbidden* — so nothing about its existence is discoverable.
- **Retrying is safe where it has to be.** `POST /cards` and `POST /study/{cardId}/review` take an optional client-generated UUID (`clientCardId`, `clientReviewId`) and store it on the row they create. A repeat returns the original outcome instead of making a second card or applying SM-2 twice — the latter being a corruption nothing would ever report. The keys live on `card` and `review_log` rather than in a table of remembered responses, so they never expire and there is no cleanup job. **The lookup happens before the ordering check in `StudyService.review`**, and that order is load-bearing: a retry sent after a later review has landed carries a timestamp the card has moved past, and checked the other way round it would be refused as out-of-order instead of recognised as already applied.
- **`Constraints.isViolationOf` is how a catch names what it handles.** Spring turns every integrity failure into one `DataIntegrityViolationException`, so a bare catch is right only while its table has a single reachable constraint. Anything unmatched is rethrown and becomes a `500`, which is the honest answer for a rule the caller could not have known about. `ConstraintsTest` pins that the name survives translation — if Hibernate ever stops reporting it, every narrow catch silently widens again.
- **`ApiExceptionHandler` owns every error response**: `NotFoundException` → `404`, `DuplicateTopicException` → `409`, `ConcurrentRequestException` and `IdempotencyKeyReuseException` → `409` carrying a `retryable` field so a client can tell the retryable one from the permanent one, `IllegalArgumentException` → `400`. Nothing maps `Exception`, deliberately — a catch-all copying `getMessage()` into `detail` would publish constraint names, SQL and file paths to whoever provoked it. `spring.mvc.problemdetails.enabled=true` puts Spring's own failures (malformed JSON, a missing parameter, a rejected `@Valid`) into the same `application/problem+json`, so a client needs one parser instead of two.
- **Entities are never serialised.** `web/dto` holds the wire format so it is not a consequence of the JPA mapping and `user_id` never leaves the server. `CardResponse` is the *only* card shape — listing, create, update, the queue and a review all return it, because the client caches cards by id and two shapes for one id could not replace each other.
- **DTOs are built after the transaction has closed.** With `open-in-view=false`, `CardResponse.from` reads `card.getTopic().getId()` on a detached proxy; that works only because the id is already on the card's row. Reading any *other* field of the topic there throws `LazyInitializationException`.
- **Validate at the edge only where the alternative is a `500`.** `CardRequest`'s `@Size` earns its place because an over-long value otherwise reaches Hibernate's entity validation and becomes a server error. `ReviewRequest` deliberately has no `@Min`/`@Max` on `confidence`: `StudyService` already answers `400` using the scheduler's own constants and names the offending value, and a Bean Validation annotation would intercept one layer earlier and replace that message with `"Invalid request content."`

### Tests

**There is no Docker on this machine**, so Testcontainers is not an option. `support/EmbeddedPostgresTest` starts a real PostgreSQL 17 in-process via `io.zonky.test:embedded-postgres` and overrides the datasource with `@DynamicPropertySource`. Extend it for anything needing a database.

This matters beyond convenience: an in-memory stand-in like H2 would quietly accept things real Postgres rejects, and this schema leans on `timestamptz`, identity columns, and a partial index. The embedded binaries are Postgres 17, matching the development server, so there is no dialect gap between test and production.

Consequences worth knowing:

- The suite runs on a machine with no local Postgres and no `FLASHCARDS_DB_PASSWORD` set. Keep it that way — do not add a test that reaches for the developer's own database.
- Flyway migrates a blank database on every context start, so each run also proves the migrations still apply from nothing.
- One server is shared by the whole test JVM. Database tests must leave it as they found it; `@Transactional` on the test class rolls back.

`ddl-auto=validate` means `contextLoads()` is a real test, not a formality — it fails on any drift between an entity mapping and the migration.

**Boot 4 moved the test autoconfigure packages too**, the same way it renamed the starters: `@AutoConfigureMockMvc` is `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. An import copied from Boot 3 will not resolve.

**Controller tests are deliberately not `@Transactional`** and clean up by hand instead. A test transaction holds one Hibernate session open for the whole method, so the response would be serialised inside the session that loaded it — which production never does. That would hide exactly the lazy-association failures the DTO mapping can produce. Keep new controller tests that way; service and repository tests roll back as usual.

**The fixed clock is opt-in.** `support/FixedClockConfiguration` is a `@TestConfiguration`, so it replaces the `Clock` only in contexts that `@Import` it by name — a plain `@Configuration` under the scanned package would silently apply to every test in the suite, which is easy to depend on without noticing. It must stay top-level rather than nested in a test class: Boot scans for a nested `@TestConfiguration` only on the class it is bootstrapping, and every `@Nested` class bootstraps separately, so inner tests would quietly get the real system clock. `FixedClockConfiguration.TODAY` is never the actual day, so a date assertion cannot pass by coincidence.

## Conventions

- Comments explain *why*, not *what*. Several non-obvious decisions above are recorded in comments at the point they matter; keep that up rather than letting the reasoning live only here.
- Test names are full sentences via `@DisplayName`, grouped with `@Nested`. Assertion messages state the expected behaviour, not the values.
- Secrets never enter the repo — configuration reads them from the environment. `interview-prep-flashcards-BRIEF.md`, `notes/`, `scratch/`, and `*.handoff.md` are gitignored and must stay that way.
- Commit messages are Conventional Commits with a capitalised subject (`feat(backend): Add ...`), no attribution footer, and a body explaining the reasoning behind the change.

## Workflow rules

- Propose one discrete change at a time and wait for local review before moving to the next.
- Only commit or push after I explicitly approve — never commit/push proactively.
- Never commit or push notes, handoff, or scratch files to the repo.
