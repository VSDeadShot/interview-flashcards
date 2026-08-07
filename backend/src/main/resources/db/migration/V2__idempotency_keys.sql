-- V2: client-supplied keys that make creating a card and recording a review safe to retry.
--
-- An offline client queues work and replays it when it reconnects. If a response is lost
-- after the server committed, the replay must not create a second card or apply SM-2 twice —
-- a doubled review silently jumps a card an extra interval, which is a corruption no error
-- ever reports.
--
-- The keys live on the rows they identify rather than in a side table of stored responses.
-- `review_log` is append-only and cards are archived rather than deleted, so a key lives as
-- long as the thing it names: no expiry policy, no cleanup job, and no window after which a
-- late retry quietly doubles.
--
-- Nullable, because an online client has no reason to send one. Postgres treats nulls as
-- distinct in a unique constraint, so any number of key-less rows coexist and nothing already
-- written needs backfilling. That is the default and it is the behaviour wanted here — not
-- `nulls not distinct`, which would allow exactly one key-less row per user.

alter table card add column client_card_id uuid;
alter table review_log add column client_review_id uuid;

-- The lookup happens before the write, but the check-then-insert is a race and the check is
-- not the guarantee. These constraints are: two identical requests in flight both miss the
-- lookup, and the loser is turned back into the winner's answer.
alter table card
    add constraint uq_card_client_id unique (user_id, client_card_id);

alter table review_log
    add constraint uq_review_log_client_id unique (user_id, client_review_id);
