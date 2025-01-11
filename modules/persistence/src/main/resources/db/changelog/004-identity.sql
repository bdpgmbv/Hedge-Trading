--liquibase formatted sql

--changeset hedge:0105-security-identifiers
-- One security may be named many ways; each name belongs to exactly one security. The primary
-- key on (kind, value) is that rule, enforced by the database rather than by whoever loads it.
create table security_identifiers (
    identifier_kind  text        not null,
    identifier_value text        not null,
    security_id      text        not null,
    asserted_by      text        not null,
    created_at       timestamptz not null default now(),
    primary key (identifier_kind, identifier_value)
);
create index security_identifiers_by_security on security_identifiers (security_id);
--rollback drop table security_identifiers;

--changeset hedge:0106-account-mappings
-- Account codes are only unique within a provider: CUSTODIAN_A's "ACC-00001" and CUSTODIAN_C's
-- "ACC-00001" are not the same account, so the provider is part of the key.
create table account_mappings (
    provider_id           text        not null,
    provider_account_code text        not null,
    account_id            text        not null,
    created_at            timestamptz not null default now(),
    primary key (provider_id, provider_account_code)
);
create index account_mappings_by_account on account_mappings (account_id);
--rollback drop table account_mappings;

--changeset hedge:0107-unresolved-identifiers
-- A work queue, not a log. An identifier nobody can map is a visible gap with an owner: dropping
-- it would understate exposure, and an understated exposure is an unhedged position nobody knows
-- about. Keeping the first observation means the evidence is one join away.
create table unresolved_identifiers (
    identifier_kind      text        not null,
    identifier_value     text        not null,
    provider_id          text        not null,
    first_observation_id text        not null references raw_observations (observation_id),
    first_seen_at        timestamptz not null,
    last_seen_at         timestamptz not null,
    occurrences          integer     not null default 1,
    reason               text        not null,
    primary key (identifier_kind, identifier_value, provider_id)
);
--rollback drop table unresolved_identifiers;
