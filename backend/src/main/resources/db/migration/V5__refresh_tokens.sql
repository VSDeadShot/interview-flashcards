-- V5: refresh tokens, rotation, and the family a rotation chain belongs to.
--
-- An access token lasts an hour, which is short enough that a captured one expires on its own
-- and long enough that a background sync is not re-authenticating constantly. What makes that
-- work is a second, longer-lived token whose only purpose is to obtain the next access token --
-- and which is replaced every time it is used.
--
-- Rotation is what turns a stolen refresh token into a detectable event rather than a silent
-- one. Once a token has been exchanged it must never work again, so a second presentation means
-- two parties hold it: the client that rotated it legitimately, and somebody who copied it. This
-- migration adds the columns that make both halves expressible.
--
-- Additive only. V4 has been applied, so nothing here rewrites it.

alter table auth_token
    add column kind varchar(16) not null default 'ACCESS';

-- Dropped immediately after the backfill. A default is what makes an existing row valid, and
-- keeping it afterwards would let a write that forgot to say what it was issuing quietly become
-- an access token -- exactly the kind of silent wrong answer the check constraints exist to stop.
alter table auth_token
    alter column kind drop default;

alter table auth_token
    add constraint ck_auth_token_kind check (kind in ('ACCESS', 'REFRESH'));

-- The rotation chain an access token and its refresh token share. Revoking a family withdraws
-- both at once, which is what logout does and what reuse detection does, and is the reason this
-- is on access tokens too rather than only on refresh ones.
alter table auth_token
    add column family_id uuid;

-- Tokens issued before this migration have no chain to belong to, so each becomes its own
-- family. That keeps the column not-null without inventing a shared identity between tokens
-- that never had one.
update auth_token
set family_id = gen_random_uuid()
where family_id is null;

alter table auth_token
    alter column family_id set not null;

-- Set when a refresh token is exchanged for its successor. Distinct from revoked_at on purpose:
-- revoked means somebody withdrew it, rotated means it did its job. Both stop it working, and
-- only the second one means a later presentation is evidence of a copy rather than of a client
-- that has not noticed it signed out.
alter table auth_token
    add column rotated_at timestamptz;

-- Revoking a family walks every row carrying its id, which is the only query that reads this
-- column and the only one that is not a lookup by digest.
create index idx_auth_token_family on auth_token (family_id);
