# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Interview Prep Flashcards — a spaced-repetition flashcard app for CS fundamentals interview prep (OS, DBMS, OOP, system design). Two Java halves: a Spring Boot backend that stores cards and progress and runs SM-2 scheduling, and a native Android client where the daily studying happens.

**Both halves are Java, not Kotlin.** That is a deliberate constraint of the project, not an accident of scaffolding — the point is to demonstrate Java competency. Do not introduce Kotlin to either module.

`docs/api-contract.md` is the spec both halves are written against — schema, endpoints, payloads, and the SM-2 golden vectors. Read it before changing the data model or adding an endpoint, and update it in the same change when the contract moves.

**Current state**: both halves are feature-complete against `docs/api-contract.md`, including AI-assisted card generation — the backend calls Gemini behind `POST /cards/generate` with the key read from configuration and never sent anywhere near a client, and the Android side offers it as a bottom sheet on the card list, holds the batch in a local `candidate` table, and lets each one be added, corrected first, or discarded. **That path has been run against a real Gemini key**, on 2026-08-19, end to end from the sheet: a batch of eight generated for `DBMS` with a `normalization` focus, one accepted after correcting it in the editor, one accepted as written, one discarded, and the two accepted ones reached Postgres on the next sync carrying the text as edited — while the five still in the band survived the pull untouched. That run is what proved `GeminiRestClient.parse`, whose walk down `steps[]` to the last `model_output` step had until then been written from documentation and exercised only against stubs this repo wrote itself. Note that the suite still runs with the endpoint unconfigured, so **every test exercises the `503` branch rather than a generation** — the wire format is pinned by nothing but that one observed response. The backend implements every endpoint in that document's table and serves it over HTTP behind the API-key filter. The Android client has its local cache, its outbox, its copy of the scheduler, its remote layer, the sync engine that drives them, the schedule that runs it, offline authoring, editing and archiving that all sync, and all four UI destinations — study, the card list, the editor and stats — each reading the cache through the same seam. **It has been run for real**, on an emulator against the live backend: a cold start pulled topics, cards and stats into the cache; a review answered on screen reached `review_log` in Postgres with the arithmetic the golden vectors specify; and a review answered in airplane mode sat in `pending_review` with its locally predicted schedule already on the card, then reached the server on reconnect carrying the same `clientReviewId` it was minted with. The AVD is `flashcards_api36` — Pixel 8, API 36, `google_apis`, x86_64 — created with the `cmdline-tools` `sdkmanager`/`avdmanager`, which are not part of a stock Android Studio SDK install and had to be added.

## Commands

There is no root-level build. `backend/` and `android/` are separate Gradle projects with their own wrappers, and neither knows about the other.

From `backend/`:

```bash
./gradlew test                  # full suite — no database setup needed, see below
./gradlew build                 # compile + test + jar
./gradlew bootRun               # start the app on :8080 — this one DOES need local Postgres
./gradlew test --tests '*Sm2SchedulerTest'          # single class
./gradlew test --tests '*Sm2SchedulerTest*lapse*'   # single method pattern
```

`bootRun` requires a local PostgreSQL 17 on `127.0.0.1:5432`, database `flashcards`, owned by a non-superuser role `flashcards`, plus the `FLASHCARDS_DB_PASSWORD` environment variable. There is no fallback value for that variable on purpose — a missing one fails startup loudly rather than silently trying a default credential.

**Deployment is a container, and that is Render's constraint rather than a preference.** Render provides native runtimes for six languages — Node, Python, Ruby, Go, Rust and Elixir — and none of them is a JVM, so `backend/Dockerfile` is the supported path. It is two-stage: a JDK builds, a JRE ships one jar and runs as a non-root user. It runs **`bootJar`, not `build`**, so no test runs inside an image build — the suite would download and start a real PostgreSQL through Zonky to prove what the build machine already proved. `server.port=${PORT:8080}` reads the port the platform assigns (Render's default is 10000) and leaves `bootRun` on 8080, which is what the Android client's default base URL expects. **There is no Docker on this machine**, so the image cannot be built locally; the first real verification of that file is a Render build.

A deployed instance also sets `FLASHCARDS_BIND_ADDRESS=0.0.0.0`, which is not a retreat from the loopback default below: inside a container the network namespace is the boundary that the loopback bind provides on a development machine, and the platform's router is the only thing that can reach it.

It also **binds `127.0.0.1` rather than every interface** (`server.address`). One static key over plain HTTP has no business being offered to the local network, and the emulator is unaffected because `10.0.2.2` is its alias for the host’s loopback. Reaching it from a real device means setting `FLASHCARDS_BIND_ADDRESS` for that session — deliberately an opt-in, not the default.

That database server should listen on loopback only (`listen_addresses = 'localhost'`). `pg_hba.conf` is what actually refuses a remote login — it permits `127.0.0.1/32` and `::1/128` under `scram-sha-256` and nothing else — and that was verified rather than assumed: a connection from the machine’s own LAN address completed the TCP handshake and was then refused with *no pg_hba.conf entry*. Narrowing `listen_addresses` removes the pre-auth surface sitting behind that check, which is the half a host-based rule cannot cover.

`./gradlew test` needs none of that. Tests start their own Postgres (see below).

From `android/`:

```bash
./gradlew build                 # compile + unit tests + lint + both APKs
./gradlew test                  # unit tests only
./gradlew :app:testDebugUnitTest --tests '*FlashcardsApiTest'   # single class
```

The single-class form needs the variant-specific task. `:app:test` is an aggregate the Android plugin creates, not a `Test` task, so it has no `--tests` option and fails with "Unknown command-line option".

**`android/local.properties` is gitignored and has to be written by hand on a fresh clone.** Three keys, all read by the build:

```properties
sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk
flashcards.apiKey=<the same value the backend gets from FLASHCARDS_API_KEY>
flashcards.baseUrl=http://10.0.2.2:8080/api/v1/
```

`sdk.dir` needs forward slashes and an escaped drive colon. `\U` is not a properties escape, so a Windows path written with backslashes silently loses them and the failure surfaces as "The filename, directory name, or volume label syntax is incorrect", which names nothing involved.

Only `sdk.dir` is required to build. A missing `flashcards.apiKey` still compiles and still runs every test — deliberately, since the scheduler and database tests need no key — and is refused by `ApiKeyInterceptor` at construction the moment anything tries to make a request, rather than being sent and answered `401`. `flashcards.baseUrl` defaults to `http://10.0.2.2:8080/api/v1/`, the emulator's alias for the host machine's loopback.

## Backend architecture

**Stack**: Java 21 (Temurin), Spring Boot 4.1.0, Gradle 9.5.1, Spring Data JPA/Hibernate, Flyway, PostgreSQL 17, JUnit 5. Group `dev.vsdeadshot`, base package `dev.vsdeadshot.flashcards`.

**Spring Boot 4 renamed the starters.** It is `spring-boot-starter-webmvc`, not `-web`, and the test starter is split per module — `spring-boot-starter-data-jpa-test`, `-webmvc-test`, `-flyway-test`, `-validation-test` — rather than one `spring-boot-starter-test`. Copying dependency snippets from Boot 3 documentation or older answers will not resolve. Tests use plain JUnit `Assertions`; AssertJ is not on the classpath.

### The scheduler is pure, and stays that way

`scheduler/Sm2Scheduler` has no clock, no database, no Spring annotations, and no state. The current date is a **parameter**, which is what makes the golden-vector tests deterministic rather than dependent on when they run. `scheduler/SchedulingState` is an immutable record that validates its own invariants in a compact constructor and is deliberately **not** a JPA entity.

**The date that parameter is given comes from a configured zone, not the host's.** `ClockConfiguration` builds `Clock.system(flashcards.timezone)` — `FLASHCARDS_TIMEZONE`, defaulting to `Asia/Kolkata` — because five things derive their day boundary from that one clock: the study queue, `due_date` comparisons, `reviewedToday`, the streak's days, and the generation allowance's reset. `systemDefaultZone()` reads as correct for exactly as long as the server is the user's own machine; a deployed container runs UTC and moves all five by the user's offset without anything failing. It binds as a `ZoneId`, so an unknown name is a `ZoneRulesException` at context startup rather than a surprise at the first request — verified, not assumed. `ClockConfigurationTest` asserts against *two* zones on purpose: one assertion would also pass under a host clock on a machine set to that zone.

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

- **`GET /health` is the one unauthenticated route, and it is unauthenticated by its path.** It sits outside `/api/`, which is the prefix `ApiKeyFilter` and `RequestSizeLimitFilter` both guard, so no exclusion list exists to be forgotten and a later change of credential inherits the exemption unchanged. It deliberately **does not touch the database**: a platform's health check is wired to restarting the process, and failing it on a database outage turns that outage into a restart loop that cannot repair it. It returns `{"status":"UP"}` and nothing else — no version, no build stamp, no dependency status — because it answers anyone who asks.
- **`ApiKeyFilter` publishes the authenticated owner** as the `userId` request attribute, and controllers read it with `@RequestAttribute(ApiKeyFilter.USER_ID_ATTRIBUTE)` rather than from configuration. That is the seam a real subject claim will arrive through when auth stops being one shared key.
- **Ownership stays in the service and the repository.** Something owned by another user reads as *not found*, never *forbidden* — so nothing about its existence is discoverable.
- **Retrying is safe where it has to be.** `POST /cards` and `POST /study/{cardId}/review` take an optional client-generated UUID (`clientCardId`, `clientReviewId`) and store it on the row they create. A repeat returns the original outcome instead of making a second card or applying SM-2 twice — the latter being a corruption nothing would ever report. The keys live on `card` and `review_log` rather than in a table of remembered responses, so they never expire and there is no cleanup job. **The lookup happens before the ordering check in `StudyService.review`**, and that order is load-bearing: a retry sent after a later review has landed carries a timestamp the card has moved past, and checked the other way round it would be refused as out-of-order instead of recognised as already applied.
- **`Constraints.isViolationOf` is how a catch names what it handles.** Spring turns every integrity failure into one `DataIntegrityViolationException`, so a bare catch is right only while its table has a single reachable constraint. Anything unmatched is rethrown and becomes a `500`, which is the honest answer for a rule the caller could not have known about. `ConstraintsTest` pins that the name survives translation — if Hibernate ever stops reporting it, every narrow catch silently widens again.
- **`ApiExceptionHandler` owns every error response**: `NotFoundException` → `404`, `DuplicateTopicException` → `409`, `ConcurrentRequestException` and `IdempotencyKeyReuseException` → `409` carrying a `retryable` field so a client can tell the retryable one from the permanent one, `IllegalArgumentException` → `400`. Nothing maps `Exception`, deliberately — a catch-all copying `getMessage()` into `detail` would publish constraint names, SQL and file paths to whoever provoked it. `spring.mvc.problemdetails.enabled=true` puts Spring's own failures (malformed JSON, a missing parameter, a rejected `@Valid`) into the same `application/problem+json`, so a client needs one parser instead of two.
- **Generation is the only rationed route, because it is the only one that costs money.** `GenerationQuota` allows 20 calls per owner per day, counted from the append-only `generation_request` table, and answers `429` with `Retry-After` when the allowance is gone. It counts **attempts, not successes** — a call that reached Gemini and failed has already been paid for, so counting successes would leave the expensive case unlimited. It is its own bean rather than a private method on `CardGenerator` for a transactional reason: a self-invoked `@Transactional` method is not proxied, and `CardGenerator.generate` is deliberately **not** transactional at all, because a transaction spanning a call allowed to take 45 seconds would hold a pooled connection for the duration and block every other endpoint behind one person's button press.
- **`RequestSizeLimitFilter` caps a request body at 256KB before anything reads it.** `@Size` on a DTO fires after binding, so it cannot stop a 30MB body becoming a 60MB `String` first. A chunked request declares no length, so those are wrapped and bounded as they are read rather than waved through. It is ordered after `ApiKeyFilter` so an unauthenticated request is still answered `401` and never `413`; both filters now state their order, since two unordered filter beans are sequenced arbitrarily.
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

## The Android client

**Stack**: AGP 9.3.1, Gradle 9.5.1, `compileSdk`/`targetSdk` 36, `minSdk` 26, Java 17 bytecode, Room 2.8.4, Retrofit 3 on OkHttp 5 with Moshi 1.15.2, WorkManager 2.11.2, JUnit 4 with Robolectric 4.15.1. Two modules: `:app` and `:scheduler`.

**There is no Compose here, and there cannot be.** Jetpack Compose is a Kotlin compiler plugin, so the Java-only rule rules it out — the UI is XML views. This is the one place that constraint costs something real, and it is worth knowing before reaching for a Compose answer to a layout problem.

`minSdk 26` is what makes `java.time` available natively. The scheduler therefore needs no desugaring and computes exactly the dates the backend computes. **Other library APIs are not covered by that**: `Stream.toList()` is API 34 and would compile and then throw `NoSuchMethodError` on a real device. Lint catches these, which is why `./gradlew build` runs it and why a lint error fails the build rather than being reported.

### The scheduler is a copy, on purpose

`:scheduler` holds `Sm2Scheduler` and `SchedulingState` duplicated from the backend rather than shared through a composite build. Sharing would tie the Android build to the backend's, and the backend's zero-setup test property is worth more than the duplication is worth avoiding. The golden vectors are duplicated alongside it, so drift between the two copies fails a test rather than diverging silently — including vectors 6 and 7, which pin the deliberate divergence from DSA Tracker.

The client runs it to *predict* a review's outcome offline. The prediction is replaced by the server's answer when the queued review is accepted; both run the same arithmetic on the same inputs, so it is normally the same value.

### The cache is what the UI reads

Everything on screen comes from Room. The network's job is to keep those tables current, not to answer a question a screen asked — which is what makes every screen work with the radio off.

- **`topic` is a cache; `pending_review` and an unsynced `card` are not.** Topics can be dropped and pulled again at any time. The outbox is the only record that a review happened, and a `card` row with no `serverId` is the only copy of something the user wrote — so a destructive schema migration may apply to `topic` alone, the pull's deletes are scoped to rows that have a `serverId`, and the outbox is written before the card it belongs to is touched.
- **`card.id` is local and never changes; `serverId` is what the sync matches on.** A card written offline has no server id, and if `id` later had to become one, every `pending_review.cardId` pointing at it would have to be rewritten — on the path that runs immediately after a network response, in the table that must never be corrupted. Cards from a pull are stored under the server's id because it is already a free local id, not because the two mean the same thing; local ids are minted downwards from zero, so the two can never collide.
- **The outbox replays in `id` order, grouped by card**, and rows are deleted once the server answers. The server's ordering rule is per card — it refuses a review older than *that card's* last one — so a strict global order is stricter than the server asks for and would let one card the server keeps refusing hold up every review queued behind it. Grouped, a stuck card stalls only itself.
- **A review the server will never accept is deleted, not marked dead.** The row is also what keeps its card out of every pull, so a row left behind freezes that card permanently — a lost answer is the cheaper of the two failures. The reason is logged and counted by the run instead.
- **A review is an event and lives in an outbox; an edit is a state and lives on the row.** Two reviews of one card are two facts, both must be sent, and order matters — so `pending_review` holds them and a card is deliberately **not** also flagged as dirty, because two places claiming to know whether a review is pending will eventually disagree. An edit or an archive is the opposite: only the latest content matters, `PUT` replaces, and `card.pendingSince` is the only record there is. The objection is to *two* places, not to markers.
- `CardDao.localIdsWithUnsentWork()` is the single answer to what a pull may not overwrite, whatever kind of work is unsent. Add the next kind there rather than as a third list.
- The pull asks for `includeArchived=true`. A card that merely stopped appearing in a listing is indistinguishable from one that was archived, moved, or missed, and the flag is what tells them apart.
- **Never show a locally computed streak.** The forgiving rule skips days on which nothing was due, which needs the whole review history and every card's due date as it stood on each of those days. `stats_snapshot` holds the server's figure and when it was fetched; `StatsView.streakDays` is **null** until the first successful fetch, because a confident zero shown to someone thirty days into a run is the worst thing this feature could do.
- **Every other stats figure is counted from the cache on each read**, not snapshotted. `dueToday` has to fall as the user answers cards, and the cache is the more current source anyway — it holds cards written and retired here that the server has not been told about. `review_tally` counts reviews per day because the outbox is emptied by syncing and `lastReviewedAt` undercounts a card answered twice in a day.

### The sync

`SyncEngine` reconciles the two halves: the outbox goes up, then everything comes back down. It knows nothing about threads or schedules — `sync()` blocks, and `SyncWorker` is what it blocks on behalf of.

- **Push, then pull**, and not for tidiness. A pull run first would fetch the server's row for every card whose review is still queued — rows it is about to invalidate — and would leave a window where a card the user has already answered shows as due again.
- **Cards go up before reviews.** A review is addressed to a server id, and a card written on this device has none until its create is accepted — so creates first means a card written and studied in one offline session syncs completely in one run rather than two. A review whose card still has no `serverId` stalls that card's chain and only that one.
- **A card the server permanently refuses is parked, not deleted** — the opposite of what happens to a review, for the opposite reason. A review is an event: dropping it costs one answer and keeps the card moving. A card is the content itself and nothing can reconstruct it, so the row stays, `card.syncError` records why, `pendingCreates()` stops offering it, and editing the card clears the error and offers it again.
- **`blocked` is not `stalled`.** Work waiting on a network is stalled and `hasWorkLeft()` counts it, so the worker retries. Work waiting on a person — a parked card, and any review of one — is blocked and deliberately excluded, because no backoff will change the server's answer and counting it would have the worker retry for as long as the card sits there.
- **A pulled card is resolved to a local row before it is written**: by `serverId`, then by `clientCardId`. The second is what the server's echo is for — a create that was accepted whose response was lost leaves a row here with no `serverId`, and inserting the server's copy under the server's id would leave two rows for one card. Resolving repairs the row in place instead, keeping its local id.
- **`cardIdsAwaitingSync()` answers in local ids**, so the pull's exclusion check runs against the resolved row and never against the id the server sent. For a card written here those two numbers are different, and comparing the wrong one silently overwrites a prediction.
- **The server's answer is held back and written once per drained chain**, not once per accepted review. Writing each one as it lands steps the card through the schedules of the reviews accepted so far, so a chain that stalls half way would leave the card showing a state older than the prediction the user was just looking at. A chain with rows still queued is named by `cardIdsAwaitingSync()`, so the pull leaves that card alone for the same reason.
- **`cardIdsAwaitingSync()` is read inside the write transaction**, not before the requests went out. A review enqueued while the network was busy would otherwise be missing from a list taken earlier, and its card would be overwritten by the server's row from before that review — losing the prediction on screen and putting the card back in today's queue. The ids of *every* card the server listed still go to `deleteMissing`, including the skipped ones; leaving them out would delete exactly the cards with unsent work.
- **An empty answer from the server routes to `deleteAll()`.** Room expands an empty list to `not in ()`, which SQLite rejects outright, so the no-rows case cannot go through `deleteMissing` at all.
- **Only `Result.retry()` means anything different to a periodic request.** WorkManager routes success *and* failure through the same `resetPeriodic()` for periodic work: the row returns to `ENQUEUED` for the next interval either way, and neither result's output data is stored. So the result answers one question — does this run want to come back before the period is up — and **a rejected key cannot reach the UI through `WorkInfo`**; surfacing it means persisting it somewhere the app reads.
- **The two work requests use different unique names.** `enqueueUniqueWork` and `enqueueUniquePeriodicWork` share one table of names, so a one-shot enqueued under the periodic request's name would replace it rather than join it, and the recurring sync would be gone until the next process start. What two names give up is a guarantee the two never overlap; every review carries a `clientReviewId` and the pull decides which cards it may touch inside its own transaction, so an overlap costs an accurate tally and nothing else.
- **Edits and archives go up last**, after reviews, so a card is never retired before the reviews that happened while it was still in use have been recorded. An archived row means a `DELETE`, any other pending row a `PUT`.
- **The marker comes down by comparison, not by id.** `clearPendingIfUnchanged` matches on the content that was sent, so an edit the user makes while the request is in flight leaves the marker up and is sent on the next run. Clearing it outright loses that edit silently, since the row then looks synced.
- **A server answer is written narrowly, never as a whole row.** A create's echo and a review's answer both return the entire card, but the only parts this client did not already have are the id and the schedule — `recordCreated` and `recordSchedule` write exactly those. Writing the rest back undoes an edit or an archive that has not been sent, along with the marker saying it still needs to be.
- **Conflicts are last-writer-wins, deliberately.** An edit sent from here overwrites whatever the server holds; there is no `If-Match` in the contract to do better. Documented under [Retrying safely](docs/api-contract.md) rather than half-solved.
- **`GET /stats` is fetched last in the pull and its failure is swallowed.** Topics and cards are the app; the streak is decoration on one screen, and letting a hiccup fetching it turn a completed pull into a failed one would have the worker retry everything it had just finished.
- `FlashcardsApp` puts the schedule back at every process start, because a process is often started by something other than a person opening the app. **`SyncScheduler.syncNow` is called from the toolbar's sync action**, not from `ReviewRepository` — that has no `Context` and should not grow one, so the study screen wires it after `record()` returns.

### The remote layer

`data/remote` mirrors the contract; `data/local` mirrors the cache; **neither imports the other**, and `data/Mappers` is the only place a field crosses. Same reason the backend keeps `web/dto` apart from `domain`: the wire format must not become a consequence of the local schema.

- **DTOs are plain classes, not records.** Moshi's record support needs `java.lang.Record` reflection that Android's runtime does not provide, so a record DTO compiles and then fails at runtime. The scheduling fields are primitives so that a JSON null is refused rather than read as zero.
- **`ProblemInterceptor` turns every non-2xx into an `ApiException` before Retrofit sees it**, so a failure cannot be ignored by accident. The trade is that a non-2xx body is no longer readable through Retrofit; nothing needs it.
- **`ApiException.disposition()` is the single place that decides what a failure means** — `RETRY`, `DROP`, or `STOP`. A `409` is the only status the server disambiguates for us, via `retryable`: true is a raced idempotency key, false is a reused one. A `409` with no `retryable` field is treated as permanent, because a retry loop on a conflict is the worse of the two failures.
- **`401` carries no body at all** — the filter rejects before any handler runs, so there is no `problem+json`, no content type, and nothing to parse. Verified against the running backend, and the test says so.
- Cleartext HTTP is permitted in **debug builds only**, and only for `10.0.2.2` and `localhost`, rather than as a blanket exemption. A real device on the LAN means adding that host to `app/src/debug/res/xml/network_security_config.xml`, setting `flashcards.baseUrl`, and starting the backend with `FLASHCARDS_BIND_ADDRESS` — all three, since the server otherwise listens on loopback alone.

### The UI

One activity, a Navigation graph with three peer destinations plus the card editor reached from
the list, and a bottom bar whose menu item ids *are* the destination ids — that match is what
`NavigationUI` works on, and nothing about a mismatch fails the build, so `MainActivityTest`
asserts on it instead. The editor is one destination titled two ways: its `android:label` is
`{title}`, which `NavigationUI` fills from the arguments, so "New card" and "Edit card" cost
neither a second destination nor the Safe Args plugin.

**How a screen reads the cache is the decision this layer turns on.** Repositories stay blocking
and composed; a `ViewModel` runs them on `Graph.io()` and publishes through `MutableLiveData`.
Two shorter-looking routes were considered and rejected:

- **A DAO returning `LiveData` from a `@Query`** works only for a read that *is* one query.
  `StatsRepository.snapshot()` is five queries in one transaction against an injected clock, and
  `CardDao.queue(today, limit)` binds today as a parameter — a `LiveData` built at 23:59 keeps
  that date forever, because nothing invalidates on a clock. Going this way means hoisting the
  composition into the view model and losing both the transaction and a repository that can be
  tested synchronously.
- **`InvalidationTracker.createLiveData(tables, Callable)`** looks like exactly the right seam and
  is not available: every overload is `@RestrictTo(LIBRARY_GROUP_PREFIX)`, it exists for Room's
  generated DAO code, and lint's `RestrictedApi` check is error severity — so using it means
  suppressing a lint error to reach a library's internals. Verified against the 2.8.4 jar, not
  assumed.

**Freshness comes from one `InvalidationTracker.Observer` per view model**, which is supported
API. It fires for a write made by the sync because `SyncWorker` goes through
`FlashcardsDatabase.get` — the same instance in the same process, since nothing in the manifest
asks for another one. The observer is removed in `onCleared`: Room holds it strongly, so leaving
it registered keeps the view model and the read it schedules alive for as long as the process is.

`Graph` is a static holder rather than a DI container, and **its executor is single-threaded on
purpose** — a repository call may write, and a read of the same data queued behind it shows the
state that write produced rather than racing it.

**There is still no abstract view-model base, and now it is a finding rather than a deferral.**
All four view models have landed and they do *not* all read the same way. `StatsViewModel` and
`CardListViewModel` subscribe to invalidation and act on every notification. `StudyViewModel`
subscribes too but **acts only while no card is showing**: a write from elsewhere must not swap
the question out from under somebody mid-answer, which is a reason to protect a card that is on
screen rather than to ignore the cache — with an empty queue there is nothing to interrupt, and
the narrower rule is what stops the caught-up screen telling somebody to run a sync that then
appears to do nothing. `CardEditorViewModel` does not subscribe at all, because it holds a card
the user is typing into, which a refresh would overwrite. A base class would fit two of them,
which is the shape that makes a base class a liability rather than a saving.

**`StudyViewModel.reload()` captures what is showing on the main thread before hopping.** It keeps
the answer revealed only when the card that comes back is the same one, so a background sync
neither hides a revealed answer nor leaves a new question showing its predecessor's answer.

**The stats screen is where null and zero are different answers.** `StatsView.streakDays` is null
until a sync has fetched it, and the number and the sentence under it are separate views precisely
so the number can be hidden — a confident zero shown to somebody a month into a run is the worst
thing this feature could do, and it is `StatsFragmentTest` that holds that line. Its topic
breakdown is inflated into a plain `LinearLayout` rather than drawn by a `RecyclerView`: the rows
scroll with the figures above them, a `RecyclerView` nested in a scrolling parent has to be told to
stop recycling before it will lay out at all, and the list is bounded by the number of topics.

**Generation is the one thing here that is not offline-first, and not outbox work.** `POST
/cards/generate` runs because a person pressed a button and is watching, so its failure is theirs
to see and decide about rather than something queued and retried behind them — nothing about it
touches `SyncEngine` or `ApiException.disposition()`. Everything else that talks to the server
does go through that path, which is why the exception needs stating. Candidates live in their own
table rather than as a flag on `card`, because a candidate must never reach the study queue, the
outbox, or a pull's delete-scope. Accepting one writes through the ordinary authoring path, so
from that moment it is an ordinary unsynced card and everything already true of those applies.

**`Graph` has two accessors for one repository, and the split is load-bearing.**
`Graph.candidates` reads the band, accepts and discards without an API client; `Graph.generator`
is the only accessor in the app that builds one. `ApiKeyInterceptor` refuses a blank key at
construction, so a single accessor would take the whole card list down on a build with no
`flashcards.apiKey` — the build CLAUDE.md deliberately keeps runnable. For the same reason
`GenerateViewModel` builds its repository inside the background task rather than holding it as a
field: the failure belongs to the one action that needs a key.

**The editor is one destination serving three titles and two sources.** `android:label` is
`{title}`, so "New card", "Edit card" and "Add generated card" cost neither a second destination
nor Safe Args; `cardId` and `candidateId` sit side by side as arguments and are never both set.
`CardEditorViewModel` hides which of the two it loaded behind `EditorState.front()`, `.back()`
and `.topicId()`, so the fragment does not learn the difference. That was the cost this design
accepted rather than adding a fourth destination, and it is written down here because a view
model with two sources is otherwise the kind of thing that looks like an accident.

**The card list is one adapter with three view types, fed one flattened list.** A `ConcatAdapter`
would make the band's count depend on a second adapter's state, and two lists held side by side
would give up `ListAdapter`'s diffing on a screen that rebuilds on every Room invalidation.
`CardListItem` is sealed to write down that `Header`, `Candidate` and `Card` are the whole set —
it buys no exhaustiveness check, because `:app` compiles at **Java 17** where pattern matching for
`switch` is still preview. That is worth knowing before reaching for one: `:app` is Java 17
bytecode while the backend is Java 21, so language features do not transfer between the halves
even though both are Java.

### Android tests

`FlashcardsDatabaseTest` runs real SQLite in memory under Robolectric — the same reasoning as the backend running real Postgres rather than H2. `robolectric.properties` pins `sdk=35` because Robolectric 4.15.1 ships no image above it while the app targets 36; that is pinned rather than lowering `targetSdk`, which would change what the app is to suit a test tool.

The remote and mapper tests deliberately **do not** use Robolectric. Nothing in them touches the Android framework, so keeping it out makes them faster and keeps that API-35 pin confined to the database tests. `FlashcardsApiTest` runs a real `MockWebServer` on a loopback port rather than stubbing the interface, so it exercises OkHttp, Retrofit and Moshi together.

What a mock server cannot prove is that this client and Jackson agree on the wire format. They do, checked against the running backend in both directions: an `Instant` crosses as `Instant.toString()`, a `LocalDate` as `LocalDate.toString()`, a replayed `clientCardId` answers `200` with the original body, and a replayed `clientReviewId` does not apply SM-2 twice.

**A Robolectric test needs `@Config(application = Application.class)`.** Robolectric does not create the app's content providers, so `androidx.startup` never initialises WorkManager and `FlashcardsApp.onCreate` throws — with a message accusing the manifest of disabling an initializer it does not disable. On a device that initializer is in the packaged manifest and runs ahead of `onCreate`, so this is a test-environment gap rather than a fault to fix in the app. It is not fixed by making the app a `Configuration.Provider` either: that would have every data-layer test boot a real WorkManager and a real database to run a scheduler test.

## Conventions

- Comments explain *why*, not *what*. Several non-obvious decisions above are recorded in comments at the point they matter; keep that up rather than letting the reasoning live only here.
- Test names are full sentences via `@DisplayName`, grouped with `@Nested`. Assertion messages state the expected behaviour, not the values.
- Secrets never enter the repo — configuration reads them from the environment. `interview-prep-flashcards-BRIEF.md`, `notes/`, `scratch/`, and `*.handoff.md` are gitignored and must stay that way.
- Commit messages are Conventional Commits with a capitalised subject (`feat(backend): Add ...`), no attribution footer, and a body explaining the reasoning behind the change.

## Workflow rules

- Propose one discrete change at a time and wait for local review before moving to the next.
- Only commit or push after I explicitly approve — never commit/push proactively.
- Never commit or push notes, handoff, or scratch files to the repo.
