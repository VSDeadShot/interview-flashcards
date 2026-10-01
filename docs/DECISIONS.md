# Decisions

Short records of decisions that are not obvious from the code, newest last. No secrets, keys,
connection strings or passwords belong here.

## 1. Default Gemini model is `gemini-3.6-flash`, not 3.7

*2026-08-19*

**Context.** `gemini-3.7-flash` was the original default. During a live run it answered `500`
"experiencing high demand" on every attempt, taking up to 44s of the 45s read timeout each time.
`gemini-3.6-flash` was watched working end to end.

**Decision.** Default to `gemini-3.6-flash`, both in `application.properties` and in the fallback
in `GeminiProperties`. A model can still be chosen per deployment with `FLASHCARDS_GEMINI_MODEL`.

**Consequences.** Generation runs on the older model. The two defaults have to move together.
Revisit once 3.7 is generally available or its quota improves.

## 2. Database moved from Render's free Postgres to Neon

*2026-09-25 – 2026-09-26*

**Context.** Render suspended its free-tier database, so the backend crashed on every daily cold
start with `UnknownHostException` while resolving a host that no longer existed.

**Decision.** Move the database to Neon, connecting through its **direct (non-pooled)** endpoint
with `sslmode=require`. The connection string Neon hands out is not a JDBC URL: the configured
value needs the `jdbc:` prefix, and the `channel_binding` parameter removed. The old Render
database was deleted on 2026-09-26.

**Consequences.** Neon started empty. Flyway built the schema on first start, and the data had to
be put back by hand (see 3). Neon runs PostgreSQL 18.6, as reported in the backend's startup
logs, so production is still a major version ahead of the 17 used locally and in tests. The
version-gap section in `CLAUDE.md` still describes Render's 18.4 instance.

## 3. Topics are server-owned and created only through the API

*2026-09-26*

**Context.** Nothing seeds topics: no migration inserts one, and there is no `data.sql` or
startup runner. The Android client treats topics as a read-only cache. `FlashcardsApi.createTopic`
exists, but nothing calls it and there is no screen for it. A fresh database therefore leaves
the app saying "No topics yet" with no way forward from the phone.

**Decision.** Create topics through `POST /api/v1/topics`, which derives the slug and the owning
`user_id` the way the app always has, rather than by hand-written SQL. Neon was seeded this way
on 2026-09-26 with: Operating Systems, DBMS, OOP, Computer Networks, System Design.

**Consequences.** Any new or rebuilt database needs the same manual step. Since 2026-10-01 the
Android app can take it too: New topic, in the Cards tab's overflow menu, calls the same route.
Topics cannot be renamed or deleted by any route.

## 4. The Gemini API key is kept out of the repository

*2026-09-26*

**Context.** Generation needs a Gemini key, and the backend must be the only thing that ever
holds it.

**Decision.** The key belongs to its own AI Studio project, `interview-flashcards`. Locally it is
the Windows User-scope environment variable `FLASHCARDS_GEMINI_API_KEY`. In production it is a
Render environment variable of the same name. It was rotated on 2026-09-26.

**Consequences.** No file in the repo, including gitignored ones, holds the key. `bootRun` picks
it up from the environment. Rotating it means updating both places. A key that is set but
rejected by Gemini surfaces as a bodyless `500` from `POST /cards/generate`.
