# AI-assisted card generation — design

Status: approved 2026-08-18, not yet implemented. This document is the reference for the
feature; `docs/api-contract.md` moves in the same change as the endpoint it describes.

## What this is

A user picks a topic they already have, optionally narrows it with free text ("normalization"),
and gets a batch of candidate flashcards from the Gemini API. Nothing enters the deck until the
user has read each candidate and accepted it, editing it first if they want.

The app is single-user and personal. **The Gemini key lives server-side only.** No end user of
the APK ever sees, needs, or enters an API key of any kind — the same posture
`FLASHCARDS_API_KEY` already has, and the client has no notion that Gemini exists beyond one
endpoint that returns text.

## Decisions this encodes

| Decision | Choice | Why |
|---|---|---|
| Where candidates live | Client-persisted, server stateless | A draft is state on a row, like an edit, not an event. Keeps the backend free of a table with a lifecycle — the same thing avoided when idempotency keys went onto existing rows rather than into a table needing a cleanup job. |
| What a request identifies | Existing `topicId` + optional `focus` | `card.topic_id` is not null, and the editor already refuses to author a card with no topic. One decision up front beats one per candidate. "DBMS normalization" decomposes into `topicId=DBMS`, `focus=normalization`. |
| Deck awareness | Existing **fronts** for that topic are sent as an avoid-list | Fronts are short and are what determines duplication. Backs are the bulk of the content and never leave the machine. Without this, a maturing topic returns steadily more near-copies — exactly when generation is most useful. |
| Review surface | A band inside the existing card list | Reuses the list, its adapter and the editor. A separate destination would be a second adapter and a fourth destination for an activity that is really "triage the deck I am already looking at". |
| Model integration | Spring `RestClient` + the API's own response schema | No new dependency. Moves "is the output valid JSON?" from runtime hope into a request parameter. |

### Why generation is not an async job

Because the server stores nothing, there is nowhere for a job to live. A `202` + poll design
would require the batch to be persisted server-side, which the first decision rules out. So
generation is a single blocking call, and the latency is something the design deals with
explicitly rather than inherits — see [Timeouts](#timeouts).

## Verified facts

These were checked, not assumed, on 2026-08-18:

- `MockRestServiceServer.bindTo(RestClient$Builder)` exists in `spring-test:7.0.8`, which is
  already on the backend's `testRuntimeClasspath`. The Gemini client is therefore testable with
  no new dependency and no network.
- The current Gemini REST surface is `POST https://generativelanguage.googleapis.com/v1beta/interactions`,
  authenticated with an `x-goog-api-key` header, with the body carrying `model`, `input`, and
  `response_format: {type, mime_type, schema}` where the schema uses **lowercase** JSON-Schema
  type names. This is not the older `models/{model}:generateContent` + `generationConfig.responseSchema`
  shape, and not the older uppercase (`"STRING"`) type names.
- Errors come back as `{"error": {"code": "<snake_case string>", "message": "..."}}`. `code` is
  documented as a string rather than the integer older Google APIs returned, and there is no
  `error.status`.

**One thing still to confirm against a real response** before the error mapping is written: that
`error.code` is in fact a string on the wire. It is the field the mapping keys on, and the
documentation is the only evidence so far.

## Contract change

One row in the endpoint table of `docs/api-contract.md`:

| Method | Path | Body | Returns |
|---|---|---|---|
| `POST` | `/cards/generate` | `{topicId, focus?, count?}` | `200` + `{candidates: [{front, back}]}` |

The path is action-shaped rather than resource-shaped, with precedent in
`POST /study/{cardId}/review`. `POST /generations` would name a resource that does not exist.

**The response is a wrapper object, not a bare array**, which breaks from `[Topic]` and `[Card]`.
Those return collections of stored resources; this returns a computed batch. The wrapper leaves
room for the one field likely to be wanted later — a note that output was truncated or filtered —
without a breaking change.

**The response is deliberately not `CardResponse`.** That type is documented as the only card
shape precisely because the client caches by id. A candidate has no id, no schedule and no server
row; giving it a `CardResponse` with empty fields would be the client's problem permanently.

New entries under **Request limits**:

- `focus` capped at 200 characters.
- `count` defaults to 8, maximum 10, **clamped above the maximum and `400` at zero or below** —
  the queue's existing asymmetric rule, reused verbatim rather than invented again.

Eight is chosen for review ergonomics, not for the model. Past ten, a person skims and
rubber-stamps, which defeats the only thing this feature is for.

## Backend

A new `ai/` package beside `scheduler/`:

- **`GeminiClient`** — an interface with one method. The only thing that knows the wire format.
- **`GeminiRestClient`** — the implementation: `RestClient`, the response schema, the
  `x-goog-api-key` header, 5 s connect and 45 s read.
- **`CardGenerator`** — loads the topic filtered by owner, loads the avoid-list, calls the client,
  validates what comes back.

`ai/` must not import `jakarta.persistence` or the web DTOs, the same rule `scheduler/` already
lives under. The avoid-list arrives as a `List<String>`; the service does the loading, so the
client stays a pure translation of one request into one response.

Ownership follows the existing rule: an unknown or another user's `topicId` reads as **not found**,
never forbidden.

**Validation drops rather than fails.** A candidate with a blank side, or one over the contract's
10,000-character cap, is discarded and the rest are returned. Nine usable cards should not be lost
to one malformed one. If every candidate is dropped, that is a `422` — see below.

**The avoid-list is capped** at the 50 most recently created fronts for the topic. Uncapped, a
mature topic would grow the prompt without limit, and the cost with it.

## Error taxonomy

Upstream has failure modes the contract has no vocabulary for. The mapping is chosen by what the
person holding the phone can actually do about it.

| Upstream condition | We return | Reasoning |
|---|---|---|
| `429`, `500`, `503`, `504`, or read timeout | `503` | All one thing to a user: not your fault, try again shortly |
| Nothing usable came back, or every candidate failed validation | `422` | Distinct from retry — an identical retry will do the same thing |
| `401`/`403` from Gemini | `500`, logged loudly | Our credential, not the caller's. Nothing they can do, and nothing about it should leak |
| Unknown or foreign `topicId` | `404` | Ownership reads as not-found |
| Bad `count`, over-long `focus` | `400` | Existing validation conventions |

Each maps through `ApiExceptionHandler` like every other error, in `application/problem+json`.
Nothing maps `Exception`, so an unanticipated upstream failure becomes a `500` with no detail —
which stays the honest answer.

### The key is optional, unlike `FLASHCARDS_API_KEY`

`GEMINI_API_KEY` binds from the environment the same way, but a missing one does **not** stop the
application starting. `/cards/generate` answers `503` "generation is not configured"; every other
endpoint is unaffected.

Making it mandatory would mean the backend refuses to boot without a Gemini key and every test
context needs one, destroying the zero-setup property of `./gradlew test` that the project
protects deliberately. Generation is a capability, not a precondition.

`flashcards.gemini.model` is configurable with a default, so a model rename is a config change
rather than a code change.

### Generation is not outbox work

It is a foreground call from a view model with its own error handling. It never touches
`SyncEngine`, never enters `pending_review`, and never goes through `ApiException.disposition()`.
Everything else in the client that talks to the server does go through that path, so this needs
saying out loud in `CLAUDE.md` or the next person will assume it.

## Timeouts

The client's shared OkHttp has a 20-second read timeout. Generation can exceed it.

Generation gets 60 seconds. Rather than a second Retrofit instance, an interceptor reads a
custom header and applies `chain.withReadTimeout(...)`, with `@Headers` on the one method that
needs it. One client, and the
unusual timeout is declared where it applies rather than raised globally for calls that do not
need it.

The backend's own 45-second upstream read timeout sits **below** the client's 60 seconds, so the
server gives up first. A server still working after its client has gone is doing work nobody will
see and still being billed for it.

## Android

### Storage

A new Room table `candidate`: `id`, `topicId`, `front`, `back`, `generatedAt`. Plus a schema
migration.

**A separate table, not a flag on `card`.** A candidate must never appear in the study queue, the
outbox, or a pull's delete-scope. A flag would leave it one forgotten `where` clause away from all
three. This is the reasoning already applied to keeping reviews in `pending_review` rather than
flagging the card.

**Candidates are droppable in a destructive migration**, like `topic` and unlike `pending_review`
or an unsynced `card`. A candidate costs one API call to recreate; holding a migration hostage to
one is the worse trade.

**Accepting** writes the card through the existing offline-authoring path and deletes the
candidate row, in one transaction. An accepted candidate then rides the outbox exactly like a
hand-written card, and works with the radio off.

**Discarding** is a row delete. There is no `discarded` column, because nothing reads one.

### UI

- **The entry point is a toolbar action on Cards, not the FAB.** The FAB means "new card" and
  should keep meaning exactly one thing. The toolbar already hosts the sync action, so a verb
  there has precedent.
- A **modal bottom sheet** collects topic (reusing the editor's topic list), focus and count, then
  shows progress in the same sheet, cancellable.
- **Errors surface in the sheet, not a snackbar**, so the inputs are still on screen to retry
  with. The three cases read differently on purpose: busy, declined, no connection.
- Results land as a **band above the saved cards** in the existing list, via a second view type.
- **Editing before accepting**: the editor destination gains a `candidateId` argument alongside
  `cardId`. No new destination, consistent with the `{title}` label trick. The real cost is that
  `CardEditorViewModel` grows a second source, and that is named here rather than discovered later.

### This is the first feature that cannot work offline

Everything else in the client was built so that the radio being off changes nothing. Generation
needs the network by definition. It should fail fast with a clear message rather than after a
sixty-second wait.

## Testing

Backend:

- `MockRestServiceServer` for the wire shape — the schema is present, the key header is present,
  and the key never appears in a log or an error body.
- A stubbed `GeminiClient` for controller tests covering each row of the error table.
- Service tests for validation-drop and for all-dropped becoming `422`.
- No network in tests, so `./gradlew test` stays zero-setup.

Android:

- `CandidateDao` under Robolectric against real SQLite.
- A `MockWebServer` test for the generate call, including the longer timeout.
- A test that accepting a candidate leaves a card row, no candidate row, and a create in the
  outbox.

## Out of scope

No server-side batch persistence, no job queue, no streaming, no per-card regeneration, no usage
or cost tracking, no multi-topic batches, and no rate limiting of our own endpoint — there is one
user.

## Implementation seams

The work splits along boundaries that already exist, so each commit is reviewable alone and
nothing lands half-wired:

1. **Contract + generation core.** `docs/api-contract.md`, the `ai/` package, `GeminiClient` and
   `CardGenerator` with the avoid-list and validation, tested against `MockRestServiceServer` and
   a stub. No controller yet.
2. **Configuration and the optional key.** Binding `GEMINI_API_KEY` and the model property, and
   the not-configured path.
3. **The endpoint and its error mapping.** Controller, request and response DTOs, request limits,
   and each row of the error table through `ApiExceptionHandler`.
4. **The Room candidate table.** Entity, DAO, migration, and the accept-in-one-transaction
   operation. Data layer only, no UI.
5. **The remote call on the client.** The API method, the per-call timeout interceptor, and the
   mapping into the local table.
6. **The generate bottom sheet.** Entry point, inputs, progress, and the three error states.
7. **The results band.** The second view type in the card list, accept and discard.
8. **Editing a candidate.** The editor's `candidateId` argument and the view model's second
   source — last, because it is the only step that modifies an existing screen's behaviour.

Steps 1–3 are usable from `curl` with nothing on the client. Steps 4–5 leave the client able to
store a batch with no way to see it. The first user-visible generation is step 6.
