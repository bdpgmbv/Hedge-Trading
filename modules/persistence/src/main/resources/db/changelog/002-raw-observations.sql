--liquibase formatted sql

--changeset hedge:0101-providers
create table providers (
    provider_id     text        primary key,
    display_name    text        not null,
    identifier_kind text        not null,
    delivery_style  text        not null,
    format          text        not null,
    provides        text        not null,
    created_at      timestamptz not null default now()
);
--rollback drop table providers;

--changeset hedge:0102-ingest-batches
create table ingest_batches (
    batch_id        text        primary key,
    provider_id     text        not null references providers (provider_id),
    artifact_name   text        not null,
    artifact_sha256 char(64)    not null,
    artifact_bytes  integer     not null,
    received_at     timestamptz not null,
    declared_count  integer,
    parsed_count    integer     not null,
    status          text        not null,
    problem         text,
    constraint ingest_batches_same_bytes_twice unique (provider_id, artifact_sha256)
);
create index ingest_batches_provider_idx on ingest_batches (provider_id, received_at);
--rollback drop table ingest_batches;

--changeset hedge:0103-raw-observations
create table raw_observations (
    observation_id     text        primary key,
    batch_id           text        not null references ingest_batches (batch_id),
    sequence_in_batch  integer     not null,
    provider_record_id text,
    provider_version   text,
    effective_at       timestamptz not null,
    received_at        timestamptz not null,
    payload            text        not null,
    constraint raw_observations_one_row_per_sequence unique (batch_id, sequence_in_batch)
);
create index raw_observations_batch_idx on raw_observations (batch_id);
--rollback drop table raw_observations;
