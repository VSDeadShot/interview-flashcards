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

Early — architecture agreed, implementation not started. Structure, stack details, and
conventions will be documented in `CLAUDE.md` once the initial scaffolding exists.
