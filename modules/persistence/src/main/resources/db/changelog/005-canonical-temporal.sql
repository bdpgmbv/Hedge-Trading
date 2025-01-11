--liquibase formatted sql

--changeset hedge:0108-canonical-positions
-- Append only. A correction is a new row, never an update, so the number we published on Tuesday
-- stays answerable after Wednesday's correction arrives. Which row wins is decided at query time
-- by the two timestamps, not by a flag somebody has to remember to maintain.
--
-- The primary key is the observation that produced the row: one provider record becomes one
-- canonical value, and reusing the observation id makes replay produce identical keys.
create table canonical_positions (
    observation_id        text          primary key references raw_observations (observation_id),
    provider_id           text          not null,
    account_id            text          not null,
    security_id           text          not null,
    quantity              numeric(28,8) not null,
    effective_at          timestamptz   not null,
    received_at           timestamptz   not null,
    normalization_version text          not null
);
create index canonical_positions_as_of
    on canonical_positions (provider_id, account_id, security_id, effective_at desc, received_at desc);
--rollback drop table canonical_positions;

--changeset hedge:0109-canonical-prices
create table canonical_prices (
    observation_id        text          primary key references raw_observations (observation_id),
    provider_id           text          not null,
    security_id           text          not null,
    amount                numeric(28,8) not null,
    currency              char(3)       not null,
    effective_at          timestamptz   not null,
    received_at           timestamptz   not null,
    normalization_version text          not null
);
create index canonical_prices_as_of
    on canonical_prices (security_id, effective_at desc, received_at desc);
--rollback drop table canonical_prices;

--changeset hedge:0110-canonical-fx-rates
create table canonical_fx_rates (
    observation_id        text          primary key references raw_observations (observation_id),
    provider_id           text          not null,
    base_currency         char(3)       not null,
    quote_currency        char(3)       not null,
    rate                  numeric(28,10) not null,
    rate_kind             text          not null,
    effective_at          timestamptz   not null,
    received_at           timestamptz   not null,
    normalization_version text          not null
);
create index canonical_fx_rates_as_of
    on canonical_fx_rates (base_currency, quote_currency, rate_kind, effective_at desc, received_at desc);
--rollback drop table canonical_fx_rates;
