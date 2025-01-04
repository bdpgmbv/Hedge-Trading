--liquibase formatted sql

--changeset hedge:0001-funds
create table funds (
    fund_id       text        primary key,
    base_currency char(3)     not null,
    created_at    timestamptz not null default now()
);
--rollback drop table funds;

--changeset hedge:0002-accounts
create table accounts (
    account_id text        primary key,
    fund_id    text        not null references funds (fund_id),
    created_at timestamptz not null default now()
);
create index accounts_fund_id_idx on accounts (fund_id);
--rollback drop table accounts;

--changeset hedge:0003-securities
create table securities (
    security_id text primary key
);
--rollback drop table securities;

--changeset hedge:0004-positions
create table positions (
    account_id  text          not null references accounts (account_id),
    security_id text          not null references securities (security_id),
    quantity    numeric(28,8) not null,
    primary key (account_id, security_id)
);
--rollback drop table positions;

--changeset hedge:0005-cash-balances
create table cash_balances (
    account_id text          not null references accounts (account_id),
    currency   char(3)       not null,
    amount     numeric(28,8) not null,
    primary key (account_id, currency)
);
--rollback drop table cash_balances;