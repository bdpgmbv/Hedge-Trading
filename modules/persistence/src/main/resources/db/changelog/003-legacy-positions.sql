--liquibase formatted sql

--changeset hedge:0104-legacy-positions
-- The schema the old system used. What it lacks is the point of keeping it: no effective date,
-- so there is only ever "now"; no source, so a number cannot be attributed; no history, so a
-- corrected value destroys the one it replaces. Every later table exists because of a column
-- that is missing here.
create table legacy_positions (
    account_code  text          not null,
    security_code text          not null,
    quantity      numeric(28,8) not null,
    primary key (account_code, security_code)
);
--rollback drop table legacy_positions;
