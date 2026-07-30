# Interview Prep Flashcards

A spaced-repetition flashcard app for CS fundamentals interview prep — OS, DBMS, OOP,
and system design basics. Built to be used daily, not just shipped.

Two parts, both Java:

- **Android client** — native, where the daily studying happens
- **Spring Boot backend** — stores cards and progress, runs the SM-2 scheduling logic,
  exposes the API the client calls

Scheduling uses the SM-2 spaced-repetition algorithm, reimplemented in Java from the
version proven out in [DSA Tracker](https://github.com/VSDeadShot/DSA-Tracker). That
project covers coding problems; this one covers conceptual knowledge.

## Status

In progress, backend first.

- **Done** — SM-2 scheduler with golden-vector tests, PostgreSQL schema under Flyway,
  JPA entities. Tests run against a real Postgres started in-process, so no local
  database setup is needed to build.
- **Next** — repositories, the REST layer from `docs/api-contract.md`, then the Android
  client.

`docs/api-contract.md` is the spec both halves are written against. `CLAUDE.md` documents
the stack, architecture, and conventions.
