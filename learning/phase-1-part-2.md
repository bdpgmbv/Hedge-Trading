# PHASE 1 — PART 2 of 4

# PART B — BUILD THE PHASE (Layers 3, 4, 5)

---

# LAYER 3 — POSTGRESQL

**WHAT:** Store portfolios in a real database.
**WHY:** Data must survive a restart, and the database must enforce its own rules.
**INSIDE:** Liquibase creates the tables. HikariCP keeps connections open and reuses them. `FundRepository` writes and reads.
**ARCHITECT:** The database is not a passive store. It refuses bad data even when your code does not.

**CONNECTS:**
```
FundRepository → DataSources → PostgreSQL
SchemaMigrator → Liquibase   → tables
```

---

## B3.1 · Install and start PostgreSQL

### STEP — Check PostgreSQL is installed

**WHAT:** Confirm the database program exists.
**WHY:** Nothing in Layer 3 works without it.

**DO:**

```bash
postgres --version
```

**SEE:**

```
postgres (PostgreSQL) 17.11 (Homebrew)
```

**IF `command not found`:**

```bash
brew install postgresql@17
```

---

### STEP — Check it is running

**WHAT:** Confirm the database is accepting connections.
**WHY:** Installed is not the same as running.

**DO:**

```bash
pg_isready
```

**SEE:**

```
/tmp:5432 - accepting connections
```

**IF `no response`:**

```bash
brew services start postgresql@17
```

**INSIDE:** PostgreSQL listens on port **5432**. A port is a numbered door on your computer that programs connect through.

---

### STEP — See which databases exist

**WHAT:** Look before creating.
**WHY:** One PostgreSQL server holds many separate databases.

**DO:**

```bash
psql -l
```

**SEE:** A table listing `postgres`, `template0`, `template1`, and anything else you have.

**DO:** Press **q** to leave the list.

---

### STEP — Create the test database

**WHAT:** Make an empty database for this project.
**WHY:** Tests must never be able to damage other data.

**DO:**

```bash
createdb hedge_trading_test
```

**SEE:** Nothing. No output means success.

**CHECK:**

```bash
psql -l | grep hedge
```

**SEE:** a line containing `hedge_trading_test`.

**ARCHITECT:** A separate database per purpose. Your tests cannot reach anything else.

**CONNECTS:**
```
createdb → empty database → SchemaMigrator creates tables → FundRepository uses them
```

---

### STEP — Connect a database UI

**WHAT:** Open a program that shows tables and rows on screen.
**WHY:** Reading `psql` text output is slow. A UI shows structure at a glance.

**GO TO:** Press **Cmd + Space**, type `DBeaver`, press **Enter**.

**DO:** Menu bar → **Database** → **New Database Connection** → click **PostgreSQL** → **Next**

**DO:** Fill in exactly:

| Box | Value |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `hedge_trading_test` |
| Username | `vyshaliprabananthlal` |
| Password | leave empty |

**DO:** Click **Test Connection**. First time, click **Download** when it asks for driver files.

**SEE:** `PostgreSQL 17.11`

**DO:** Click **Finish**.

**CHECK:** Expand in the left panel:

```
hedge_trading_test → Databases → hedge_trading_test → Schemas → public → Tables
```

**SEE:** **Nothing.** Tables is empty.

**ARCHITECT:** You made the empty database by hand. You will **not** make tables by hand. Code does that, so every machine gets identical tables.

---

## B3.2 · The code

### FILE · `modules/persistence/build.gradle.kts` · EXISTING

```kotlin
plugins {
    id("hedge.java-conventions")
}

description = "PostgreSQL persistence: schema migrations, connection pool and repositories"

dependencies {
    api(project(":modules:domain"))
    api(libs.micrometer.core)
    implementation(libs.hikari)
    implementation(libs.liquibase.core)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.postgresql)
    testImplementation(project(":modules:generator"))
}
```

**LOOK:** Four different words for four different dependencies.

| Word | Meaning | Used for |
|---|---|---|
| `api` | Callers **can** see this type | `domain`, `micrometer-core` |
| `implementation` | Callers **cannot** see it | `hikari`, `liquibase` |
| `runtimeOnly` | Present when running, **not** when compiling | `postgresql` driver |
| `testImplementation` | Only when testing | `generator` |

**CRITICAL CONCEPT — hiding implementation types.**

**EXAMPLE — this caught a real mistake.** My first `PersistenceConfig.java` used `HikariDataSource`. The build failed:

```
error: package com.zaxxer.hikari does not exist
```

**The design was working.** The fix was to hide Hikari better, not expose it.

**LOOK:** `runtimeOnly(libs.postgresql)`

**WHY:** Your code must never name a PostgreSQL class. If it did, changing database would mean changing code. The driver is loaded by JDBC at runtime using the URL.

---

### FILE · `db.changelog-master.sql` · EXISTING

`modules/persistence/src/main/resources/db/changelog/db.changelog-master.sql`

```sql
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
```

**LOOK:** `primary key (account_id, security_id)`

**CRITICAL CONCEPT — the database holds the rule too.**

**EXAMPLE — the exact error this produced when our generator had a bug:**

```
ERROR: duplicate key value violates unique constraint "positions_pkey"
Detail: Key (account_id, security_id)=(ACC-00000-000, SEC-00000) already exists.
```

**That error found a missing rule in our Java code**, two layers up.

**LOOK:** `numeric(28,8)`

**CRITICAL CONCEPT — exact decimals in the database.**

**EXAMPLE — run this to see why:**

```bash
psql -d hedge_trading_test -c "select (0.1::float8 + 0.2::float8 = 0.3::float8) as float_equal, (0.1::numeric + 0.2::numeric = 0.3::numeric) as numeric_equal;"
```

**SEE:**

```
 float_equal | numeric_equal
-------------+---------------
 f           | t
```

**ARCHITECT:** Layer 1 banned `double` in Java. This column bans it in SQL. **The same rule, enforced twice.**

**LOOK:** `references funds (fund_id)`

**WHAT:** A foreign key. The value must already exist in the other table.

**EXAMPLE — try it in DBeaver:**

```sql
insert into positions (account_id, security_id, quantity)
values ('NO-SUCH-ACCOUNT', 'SEC-00000', 1);
```

**SEE:**

```
ERROR: insert or update on table "positions" violates foreign key constraint
       "positions_account_id_fkey"
```

**No Java ran.** PostgreSQL refused it.

**LOOK:** `--rollback drop table funds;`

**WHAT:** How to undo each change.
**WHY:** A change you cannot undo is not safe to deploy.

**LOOK:** `create index accounts_fund_id_idx on accounts (fund_id);`

**WHAT:** An index makes `where fund_id = ?` fast.
**WHY:** Every read of a fund looks up its accounts by `fund_id`. Without the index the database reads every row.

**PRODUCTION ROLE:** Indexes cost write speed and disk. This one earns it because it is on the path of every single read.

---

### FILE · `DataSources.java` · EXISTING

```java
package dev.hedge.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;

public final class DataSources {

    private DataSources() {}

    public static DataSource pooled(String jdbcUrl, String user, String password, int maximumPoolSize) {
        return pooled(jdbcUrl, user, password, maximumPoolSize, null);
    }

    public static DataSource pooled(
            String jdbcUrl, String user, String password, int maximumPoolSize, MeterRegistry meterRegistry) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setPoolName("hedge-pool");
        config.setConnectionTimeout(5_000);
        config.setAutoCommit(false);
        if (meterRegistry != null) {
            config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        }
        return new HikariDataSource(config);
    }

    public static void close(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new IllegalStateException("could not close the connection pool", failure);
            }
        }
    }
}
```

**LOOK:** The return type is `DataSource`, **not** `HikariDataSource`.

**WHY:** Callers never name Hikari. Swapping the pool later changes one file, not every caller.

**LOOK:** `config.setAutoCommit(false)`

**CRITICAL CONCEPT — transaction boundaries.**

**WHAT:** Changes are not saved until the code says `commit()`.

**EXAMPLE — with auto-commit ON, saving a fund with 2 accounts and a failure on the second:**

| Step | auto-commit ON | auto-commit OFF |
|---|---|---|
| insert fund | **saved** | pending |
| insert account 1 | **saved** | pending |
| insert account 2 | fails | fails |
| result | **fund + account 1 remain** | **nothing remains** |

A fund that was never successfully saved is worse than no fund. Nothing reports it.

**LOOK:** `config.setConnectionTimeout(5_000)`

**WHAT:** A thread waits at most 5 seconds for a free connection, then fails.
**WHY:** Without a limit, threads pile up until the server runs out of memory.

**LOOK:** `config.setMetricsTrackerFactory(...)`

**WHAT:** Makes the pool publish its own numbers.

**EXAMPLE — measured, with and without this line:**

| | Pool metrics published |
|---|---|
| Without | **0** |
| With | `hikaricp_connections`, `_active`, `_idle`, `_pending`, `_acquire_seconds` |

`hikaricp_connections_pending` is the metric that diagnoses a slow API. Without this line you would have nothing to look at.

---

### FILE · `SchemaMigrator.java` · EXISTING

```java
package dev.hedge.persistence;

import java.sql.Connection;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

public final class SchemaMigrator {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.sql";

    private SchemaMigrator() {}

    public static void migrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        } catch (Exception e) {
            throw new IllegalStateException("schema migration failed", e);
        }
    }
}
```

**LOOK:** `liquibase.update(...)`

**WHAT:** Runs only the changesets that have not run before.
**WHY:** The same command works on an empty database and on one already up to date.

**INSIDE:** Liquibase keeps a table called `databasechangelog`. Before each changeset it checks whether that id already ran.

**EXAMPLE — measured, running migrate a second time:**

```
PROBE second migrate took 20 ms
```

Nothing changed. It read the history table and stopped.

**CONNECTS:**
```
db.changelog-master.sql
       ↓
SchemaMigrator.migrate()
       ↓
Liquibase reads databasechangelog
       ↓
applies only new changesets
       ↓
5 tables + 2 Liquibase tables
```

---

### FILE · `PersistenceException.java` · EXISTING

```java
package dev.hedge.persistence;

public class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

**WHY:** Callers catch **one** type. They never need to know `SQLException` exists — that is a database detail.

**ARCHITECT DECISION:** Each layer translates failures into its own vocabulary. The API layer later turns this into HTTP 503.

---

### FILE · `FundRepository.java` · EXISTING

```java
package dev.hedge.persistence;

import dev.hedge.domain.Account;
import dev.hedge.domain.AccountId;
import dev.hedge.domain.Fund;
import dev.hedge.domain.FundId;
import dev.hedge.domain.Money;
import dev.hedge.domain.Position;
import dev.hedge.domain.Quantity;
import dev.hedge.domain.SecurityId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

public final class FundRepository {

    private final DataSource dataSource;

    public FundRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public void save(Fund fund) {
        Objects.requireNonNull(fund, "a fund is required");
        try (Connection connection = dataSource.getConnection()) {
            try {
                writeFund(connection, fund);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw new PersistenceException("could not save fund " + fund.id(), failure);
            }
        } catch (SQLException failure) {
            throw new PersistenceException("could not obtain a connection", failure);
        }
    }

    private static void writeFund(Connection connection, Fund fund) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("insert into funds (fund_id, base_currency) values (?, ?)")) {
            statement.setString(1, fund.id().value());
            statement.setString(2, fund.baseCurrency().getCurrencyCode());
            statement.executeUpdate();
        }
        for (Account account : fund.accounts()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("insert into accounts (account_id, fund_id) values (?, ?)")) {
                statement.setString(1, account.id().value());
                statement.setString(2, fund.id().value());
                statement.executeUpdate();
            }
            writePositions(connection, account);
            writeCash(connection, account);
        }
    }

    private static void writePositions(Connection connection, Account account) throws SQLException {
        try (PreparedStatement security = connection.prepareStatement(
                        "insert into securities (security_id) values (?) on conflict do nothing");
                PreparedStatement position = connection.prepareStatement(
                        "insert into positions (account_id, security_id, quantity) values (?, ?, ?)")) {
            for (Position held : account.positions()) {
                security.setString(1, held.securityId().value());
                security.executeUpdate();

                position.setString(1, held.accountId().value());
                position.setString(2, held.securityId().value());
                position.setBigDecimal(3, held.quantity().value());
                position.addBatch();
            }
            position.executeBatch();
        }
    }

    private static void writeCash(Connection connection, Account account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into cash_balances (account_id, currency, amount) values (?, ?, ?)")) {
            for (Money cash : account.cashBalances()) {
                statement.setString(1, account.id().value());
                statement.setString(2, cash.currency().getCurrencyCode());
                statement.setBigDecimal(3, cash.amount());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public Optional<Fund> findById(FundId id) {
        Objects.requireNonNull(id, "a fund id is required");
        try (Connection connection = dataSource.getConnection()) {
            Currency baseCurrency = readBaseCurrency(connection, id);
            if (baseCurrency == null) {
                return Optional.empty();
            }
            List<AccountId> accountIds = readAccountIds(connection, id);
            Map<AccountId, List<Position>> positions = readPositions(connection, id);
            Map<AccountId, List<Money>> cash = readCash(connection, id);

            List<Account> accounts = new ArrayList<>(accountIds.size());
            for (AccountId accountId : accountIds) {
                accounts.add(new Account(
                        accountId,
                        positions.getOrDefault(accountId, List.of()),
                        cash.getOrDefault(accountId, List.of())));
            }
            return Optional.of(new Fund(id, baseCurrency, accounts));
        } catch (SQLException failure) {
            throw new PersistenceException("could not read fund " + id, failure);
        }
    }

    private static Currency readBaseCurrency(Connection connection, FundId id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select base_currency from funds where fund_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Currency.getInstance(rows.getString(1).trim()) : null;
            }
        }
    }

    private static List<AccountId> readAccountIds(Connection connection, FundId id) throws SQLException {
        List<AccountId> accounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select account_id from accounts where fund_id = ? order by account_id")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    accounts.add(AccountId.of(rows.getString(1)));
                }
            }
        }
        return accounts;
    }

    private static Map<AccountId, List<Position>> readPositions(Connection connection, FundId id)
            throws SQLException {
        Map<AccountId, List<Position>> byAccount = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select p.account_id, p.security_id, p.quantity from positions p"
                        + " join accounts a on a.account_id = p.account_id"
                        + " where a.fund_id = ? order by p.account_id, p.security_id")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    AccountId accountId = AccountId.of(rows.getString(1));
                    byAccount.computeIfAbsent(accountId, key -> new ArrayList<>())
                            .add(new Position(
                                    accountId,
                                    SecurityId.of(rows.getString(2)),
                                    new Quantity(rows.getBigDecimal(3))));
                }
            }
        }
        return byAccount;
    }

    private static Map<AccountId, List<Money>> readCash(Connection connection, FundId id) throws SQLException {
        Map<AccountId, List<Money>> byAccount = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select c.account_id, c.currency, c.amount from cash_balances c"
                        + " join accounts a on a.account_id = c.account_id"
                        + " where a.fund_id = ? order by c.account_id, c.currency")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    AccountId accountId = AccountId.of(rows.getString(1));
                    byAccount.computeIfAbsent(accountId, key -> new ArrayList<>())
                            .add(new Money(rows.getBigDecimal(3), Currency.getInstance(rows.getString(2).trim())));
                }
            }
        }
        return byAccount;
    }

    public long countPositions(FundId id) {
        Objects.requireNonNull(id, "a fund id is required");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from positions p"
                                + " join accounts a on a.account_id = p.account_id"
                                + " where a.fund_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new PersistenceException("could not count positions for fund " + id, failure);
        }
    }

    public List<Position> findPositions(FundId id, int limit, long offset) {
        Objects.requireNonNull(id, "a fund id is required");
        List<Position> positions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select p.account_id, p.security_id, p.quantity from positions p"
                                + " join accounts a on a.account_id = p.account_id"
                                + " where a.fund_id = ?"
                                + " order by p.account_id, p.security_id"
                                + " limit ? offset ?")) {
            statement.setString(1, id.value());
            statement.setInt(2, limit);
            statement.setLong(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    positions.add(new Position(
                            AccountId.of(rows.getString(1)),
                            SecurityId.of(rows.getString(2)),
                            new Quantity(rows.getBigDecimal(3))));
                }
            }
        } catch (SQLException failure) {
            throw new PersistenceException("could not read positions for fund " + id, failure);
        }
        return positions;
    }
}
```

**LOOK — the transaction:**

```java
                writeFund(connection, fund);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
```

**WHAT:** Everything is written, then saved in one step. Any failure undoes all of it.

**CRITICAL CONCEPT — all or nothing.**

---

**LOOK — `findById` runs exactly 4 queries:**

```
1. select base_currency from funds        where fund_id = ?
2. select account_id    from accounts     where fund_id = ?
3. select ... from positions      join accounts ... where a.fund_id = ?
4. select ... from cash_balances  join accounts ... where a.fund_id = ?
```

**CRITICAL CONCEPT — the N+1 query problem.**

**WRONG WAY:**

```java
for (AccountId accountId : accountIds) {
    List<Position> positions = readPositionsForOneAccount(connection, accountId);  // ← 1 query each
}
```

**EXAMPLE — the cost as data grows:**

| Accounts in the fund | Wrong way | Our way |
|---|---|---|
| 2 | 3 queries | **4** |
| 20 | 21 queries | **4** |
| 200 | 201 queries | **4** |

**RIGHT WAY — one query for all accounts, then group in memory:**

```java
Map<AccountId, List<Position>> positions = readPositions(connection, id);
// ...
positions.getOrDefault(accountId, List.of())
```

**TRADE-OFF:** We fetch more rows at once and group them in Java. That uses slightly more memory in exchange for far fewer round trips.

---

**LOOK — `prepareStatement("... where fund_id = ?")`**

**CRITICAL CONCEPT — SQL injection prevention.**

**WHAT:** The `?` is sent separately from the SQL text.

**WRONG WAY:**

```java
"select ... where fund_id = '" + id + "'"     // ← never do this
```

**EXAMPLE — what the wrong way allows:**

```
fundId = "x'; drop table funds; --"
```

With string joining, that becomes real SQL. With `?`, it is only ever a **value**, never code.

---

**LOOK — `limit ? offset ?` in `findPositions`**

**WHAT:** The database returns only one page of rows.
**WHY:** The alternative is loading every position and cutting the list in Java — reading 50,000 rows to return 50.

---

**LOOK — `on conflict do nothing` for securities**

**WHAT:** Insert the security, but do not fail if it already exists.
**WHY:** Many accounts hold the same security. The first insert creates it; later ones are silently skipped.

---

### FILE · `TestDatabase.java` · EXISTING

```java
package dev.hedge.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

final class TestDatabase {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/hedge_trading_test";

    private TestDatabase() {}

    static DataSource start(int poolSize) {
        DataSource dataSource = DataSources.pooled(url(), user(), password(), poolSize);
        SchemaMigrator.migrate(dataSource);
        return dataSource;
    }

    static void clear(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table positions, cash_balances, accounts, securities, funds cascade");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear the test database", failure);
        }
    }

    private static String url() {
        return System.getenv().getOrDefault("HEDGE_DB_URL", DEFAULT_URL);
    }

    private static String user() {
        return System.getenv().getOrDefault("HEDGE_DB_USER", System.getProperty("user.name"));
    }

    private static String password() {
        return System.getenv().getOrDefault("HEDGE_DB_PASSWORD", "");
    }
}
```

**LOOK:** `System.getenv().getOrDefault("HEDGE_DB_URL", DEFAULT_URL)`

**CRITICAL CONCEPT — configuration from the environment.**

**WHAT:** Use the environment variable if set, else the local default.
**WHY:** The same test runs on your laptop and on a build server with a different address, **with no code change**.

**EXAMPLE:**

```bash
HEDGE_DB_URL="jdbc:postgresql://ci-db:5432/test" ./gradlew test
```

**LOOK:** `truncate ... cascade` before each test.

**WHY:** Each test starts from a known empty state. A test that depends on another test's leftovers is a test you cannot trust.

---

### FILE · `FundRepositoryTest.java` · EXISTING

```java
package dev.hedge.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hedge.domain.Account;
import dev.hedge.domain.AccountId;
import dev.hedge.domain.Fund;
import dev.hedge.domain.FundId;
import dev.hedge.domain.Money;
import dev.hedge.domain.Position;
import dev.hedge.domain.Quantity;
import dev.hedge.domain.SecurityId;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundRepositoryTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static DataSource dataSource;
    private FundRepository repository;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.start(5);
    }

    @AfterAll
    static void stopDatabase() {
        DataSources.close(dataSource);
    }

    @BeforeEach
    void cleanDatabase() {
        TestDatabase.clear(dataSource);
        repository = new FundRepository(dataSource);
    }

    private static Fund fundWith(String fundId, String accountId, String quantity, String cash) {
        AccountId account = AccountId.of(accountId);
        return new Fund(
                FundId.of(fundId),
                USD,
                List.of(new Account(
                        account,
                        List.of(new Position(account, SecurityId.of("SEC-1"), Quantity.of(quantity))),
                        List.of(Money.of(cash, "USD")))));
    }

    @Test
    @DisplayName("what is saved comes back exactly, with no value changed by the database")
    void a_fund_survives_a_round_trip() {
        Fund original = fundWith("F-1", "ACC-1", "123.45", "9876.54");

        repository.save(original);

        assertThat(repository.findById(FundId.of("F-1"))).contains(original);
    }

    @Test
    @DisplayName("an unknown fund returns nothing rather than failing")
    void an_unknown_fund_is_empty() {
        assertThat(repository.findById(FundId.of("MISSING"))).isEmpty();
    }

    @Test
    @DisplayName("saving the same fund twice is refused by the primary key")
    void a_duplicate_fund_is_refused() {
        Fund fund = fundWith("F-1", "ACC-1", "10", "100.00");
        repository.save(fund);

        assertThatThrownBy(() -> repository.save(fund))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("could not save fund F-1");
    }

    @Test
    @DisplayName("a failed save leaves nothing behind, not even the rows written before the failure")
    void a_failed_save_writes_nothing() {
        AccountId shared = AccountId.of("ACC-1");
        Account first = new Account(
                shared, List.of(new Position(shared, SecurityId.of("SEC-1"), Quantity.of("1"))), List.of());
        Account duplicate = new Account(
                shared, List.of(new Position(shared, SecurityId.of("SEC-2"), Quantity.of("2"))), List.of());
        Fund broken = new Fund(FundId.of("F-BROKEN"), USD, List.of(first, duplicate));

        assertThatThrownBy(() -> repository.save(broken)).isInstanceOf(PersistenceException.class);

        assertThat(repository.findById(FundId.of("F-BROKEN"))).isEmpty();
    }

    @Test
    @DisplayName("an exact decimal amount is stored and returned without loss")
    void money_keeps_its_exact_value() {
        repository.save(fundWith("F-2", "ACC-2", "0.00000001", "0.01"));

        Optional<Fund> loaded = repository.findById(FundId.of("F-2"));

        assertThat(loaded).isPresent();
        Account account = loaded.orElseThrow().accounts().get(0);
        assertThat(account.positions().get(0).quantity()).isEqualTo(Quantity.of("0.00000001"));
        assertThat(account.cashBalances().get(0)).isEqualTo(Money.of("0.01", "USD"));
    }
}
```

**LOOK:** `a_failed_save_writes_nothing` — **this is the most important test in Layer 3.**

**EXAMPLE DATA it uses:** a fund whose two accounts share the same id — impossible, so the second insert fails.

| Expected after the failure | Why |
|---|---|
| `findById("F-BROKEN")` → empty | Rollback removed the fund **and** the first account |

**LOOK:** `money_keeps_its_exact_value` uses `0.00000001` — the smallest value `numeric(28,8)` can hold. **Edge values prove the column type, not round numbers.**

---

### FILE · `DbProbe.java` · EXISTING

```java
package dev.hedge.persistence;

import dev.hedge.domain.Fund;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DbProbe {

    @Test
    void run() {
        DataSource ds = TestDatabase.start(5);
        TestDatabase.clear(ds);
        FundRepository repository = new FundRepository(ds);

        Fund fund = new PortfolioGenerator().generate(WorkloadSpec.tiny(42)).funds().get(0);

        long start = System.nanoTime();
        repository.save(fund);
        long saveMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        Fund loaded = repository.findById(fund.id()).orElseThrow();
        long loadMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("PROBE saved    : " + fund.id() + " positions=" + fund.positionCount() + " in " + saveMs + " ms");
        System.out.println("PROBE loaded   : " + loaded.id() + " positions=" + loaded.positionCount() + " in " + loadMs + " ms");
        System.out.println("PROBE identical: " + fund.equals(loaded));
        DataSources.close(ds);
    }
}
```

**EXAMPLE — measured output:**

```
PROBE saved    : FUND-00000 positions=6 in 4 ms
PROBE loaded   : FUND-00000 positions=6 in 2 ms
PROBE identical: true
```

**`identical: true` is the proof that Layers 1, 2 and 3 agree.** The generator built it, the database stored it, and Layer 1's types rebuilt it to an equal object.

---

## B3.3 · Run the migration and see the tables

### STEP — Build and let the code create the tables

**WHAT:** Run the tests, which run the migration.
**WHY:** The schema is created by code, not by hand.

**DO:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew test --write-locks
```

**SEE:** `BUILD SUCCESSFUL`

**INSIDE:**
```
1. FundRepositoryTest starts
2. TestDatabase.start(5) opens 5 connections
3. SchemaMigrator.migrate() runs
4. Liquibase reads db.changelog-master.sql
5. 5 changesets run → 5 tables created
6. The tests save and load funds
```

---

### STEP — See the tables appear in DBeaver

**WHAT:** Confirm the migration really created them.
**WHY:** Never trust that a command worked. Look.

**GO TO:** DBeaver

**DO:** Click **Tables** in the left panel. Press **F5**.

**SEE:**

```
accounts
cash_balances
databasechangelog
databasechangeloglock
funds
positions
securities
```

**CHECK:** 7 tables. You wrote 5. The 2 `databasechange...` tables belong to Liquibase.

**ARCHITECT:** Liquibase keeps its records in the same database it changes. History cannot drift from the schema it describes.

---

### STEP — Inspect the positions table

**DO:** Double-click **positions**. Stay on the **Properties** tab.

**SEE:**

| Column | Type | Not Null |
|---|---|---|
| account_id | text | yes |
| security_id | text | yes |
| quantity | numeric(28,8) | yes |

**DO:** Click **Constraints** in the list on the tab's left edge.

**SEE:** `positions_pkey` — PRIMARY KEY on `account_id, security_id`

**DO:** Click **Foreign Keys**.

**SEE:**
```
positions_account_id_fkey   →  accounts
positions_security_id_fkey  →  securities
```

---

### STEP — See the migration history

**DO:** Double-click **databasechangelog** → click the **Data** tab.

**SEE:** 5 rows:

| ID | AUTHOR |
|---|---|
| 0001-funds | hedge |
| 0002-accounts | hedge |
| 0003-securities | hedge |
| 0004-positions | hedge |
| 0005-cash-balances | hedge |

**DO:** Scroll right to **MD5SUM**.

**ARCHITECT:** `MD5SUM` is a fingerprint of the changeset text. Edit a changeset that already ran and Liquibase stops the build. **Applied history cannot be rewritten.**

---

# LAYER 4 + 5 — THE API AND ITS METRICS

**WHAT:** Serve portfolios over HTTP, and report health and numbers about itself.
**WHY:** Something outside the program must be able to read the data, and you must be able to see whether it is healthy.
**INSIDE:** Spring receives the request, the service checks it, the repository reads the database, the result becomes JSON.
**ARCHITECT:** Each component does one job. HTTP knowledge lives in one file. Rules live in another.

**CONNECTS:**
```
HTTP → FundController → FundQueryService → FundRepository → PostgreSQL
                                   ↓
                            ApiResponses (DTOs)
```

---

### FILE · `applications/hedge-api/build.gradle.kts` · EXISTING

```kotlin
plugins {
    id("hedge.java-conventions")
    alias(libs.plugins.spring.boot)
}

description = "HTTP API for reading portfolio state"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":modules:persistence"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":modules:generator"))
}
```

**LOOK:** `implementation(platform(libs.spring.boot.bom))`

**WHAT:** A BOM — Spring's own list of versions that fit together.
**WHY:** You name 5 Spring libraries with no versions. Spring decides which versions work together.

**EXAMPLE — measured cost of a framework:**

| Module | Locked libraries |
|---|---|
| domain | 19 (all test-only) |
| generator | 19 |
| persistence | 36 |
| **hedge-api** | **118** |

**You named 5 Spring libraries. 118 arrived.** That is what a framework costs — and why `domain` still has 19.

---

### FILE · `HedgeApiApplication.java` · EXISTING

```java
package dev.hedge.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HedgeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HedgeApiApplication.class, args);
    }
}
```

**WHAT:** The starting point of the program.
**INSIDE:** `@SpringBootApplication` tells Spring to scan this package, find the `@Component` and `@Configuration` classes, and start a web server.

---

### FILE · `PersistenceConfig.java` · EXISTING

```java
package dev.hedge.api;

import dev.hedge.persistence.DataSources;
import dev.hedge.persistence.FundRepository;
import dev.hedge.persistence.SchemaMigrator;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PersistenceConfig {

    @Bean(destroyMethod = "close")
    DataSource dataSource(
            @Value("${hedge.db.url}") String url,
            @Value("${hedge.db.user}") String user,
            @Value("${hedge.db.password}") String password,
            @Value("${hedge.db.pool-size}") int poolSize,
            MeterRegistry meterRegistry) {
        DataSource dataSource = DataSources.pooled(url, user, password, poolSize, meterRegistry);
        SchemaMigrator.migrate(dataSource);
        return dataSource;
    }

    @Bean
    FundRepository fundRepository(DataSource dataSource) {
        return new FundRepository(dataSource);
    }
}
```

**LOOK:** `DataSource`, not `HikariDataSource`.

**WHY:** Layer 3 hides Hikari. This file never names it.

**LOOK:** `SchemaMigrator.migrate(dataSource);`

**WHAT:** The schema is checked and updated at startup.
**WHY:** The application refuses to start on a wrong schema.

**ARCHITECT DECISION — and a real trade-off:**

**EXAMPLE — measured. I started the API pointing at a port with no database:**

```
Caused by: java.net.ConnectException: Connection refused
APPLICATION FAILED TO START
```

| Design | Good | Bad |
|---|---|---|
| **Fail fast** *(ours)* | Never runs against a wrong schema | A database blip during a deploy means **no instance starts** |
| **Start degraded** | Starts, readiness DOWN, recovers alone | Could serve traffic on an un-migrated schema |

**WHEN WE REVISIT IT:** Phase 7, rolling deployments. At that point, running migrations as a **separate job before** the deploy becomes the better design.

---

### FILE · `ApiResponses.java` · EXISTING

```java
package dev.hedge.api;

import dev.hedge.domain.Account;
import dev.hedge.domain.Fund;
import dev.hedge.domain.Money;
import dev.hedge.domain.Position;
import java.util.List;

final class ApiResponses {

    private ApiResponses() {}

    record MoneyResponse(String amount, String currency) {
        static MoneyResponse from(Money money) {
            return new MoneyResponse(money.amount().toPlainString(), money.currency().getCurrencyCode());
        }
    }

    record PositionResponse(String securityId, String quantity) {
        static PositionResponse from(Position position) {
            return new PositionResponse(position.securityId().value(), position.quantity().value().toPlainString());
        }
    }

    record AccountResponse(String accountId, List<PositionResponse> positions, List<MoneyResponse> cash) {
        static AccountResponse from(Account account) {
            return new AccountResponse(
                    account.id().value(),
                    account.positions().stream().map(PositionResponse::from).toList(),
                    account.cashBalances().stream().map(MoneyResponse::from).toList());
        }
    }

    record FundResponse(
            String fundId, String baseCurrency, int positionCount, List<AccountResponse> accounts) {
        static FundResponse from(Fund fund) {
            return new FundResponse(
                    fund.id().value(),
                    fund.baseCurrency().getCurrencyCode(),
                    fund.positionCount(),
                    fund.accounts().stream().map(AccountResponse::from).toList());
        }
    }

    record PageResponse<T>(List<T> items, int page, int size, long total) {}
}
```

**LOOK:** `money.amount().toPlainString()` — the amount becomes **text**.

**CRITICAL CONCEPT — exact decimals on the wire.**

**EXAMPLE — measured from your running API:**

```json
{"securityId":"SEC-00001","quantity":"390"}
```

Note the **quotes**. It is a string, not a JSON number.

**WHY:** JSON numbers are read as floating point by most clients. `0.1` sent as a JSON number can arrive as `0.1000000000000000055`.

**ARCHITECT:** Layer 1 banned floating point in Java. Layer 3 banned it in SQL. **This line bans it on the wire.** Three layers, one rule.

**LOOK:** These are separate records, not the domain types.

**CRITICAL CONCEPT — the API contract is not the internal model.**

**EXAMPLE — what breaks without DTOs:**

| Change | With DTOs | Returning `Fund` directly |
|---|---|---|
| Rename `Fund.id` to `Fund.identifier` | API unchanged | **Every client breaks** |
| Add an internal field to `Account` | API unchanged | Leaks into every response |

---

### FILE · `FundQueryService.java` · EXISTING

```java
package dev.hedge.api;

import dev.hedge.api.ApiResponses.FundResponse;
import dev.hedge.api.ApiResponses.PageResponse;
import dev.hedge.api.ApiResponses.PositionResponse;
import dev.hedge.domain.FundId;
import dev.hedge.persistence.FundRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class FundQueryService {

    private final FundRepository repository;
    private final int maximumPageSize;

    FundQueryService(FundRepository repository, @Value("${hedge.api.max-page-size}") int maximumPageSize) {
        this.repository = repository;
        this.maximumPageSize = maximumPageSize;
    }

    FundResponse fund(String rawFundId) {
        FundId fundId = FundId.of(rawFundId);
        return repository
                .findById(fundId)
                .map(FundResponse::from)
                .orElseThrow(() -> new FundNotFoundException(rawFundId));
    }

    PageResponse<PositionResponse> positions(String rawFundId, int page, int size) {
        FundId fundId = FundId.of(rawFundId);
        requirePageIsValid(page, size);

        long total = repository.countPositions(fundId);
        if (total == 0 && repository.findById(fundId).isEmpty()) {
            throw new FundNotFoundException(rawFundId);
        }
        return new PageResponse<>(
                repository.findPositions(fundId, size, (long) page * size).stream()
                        .map(PositionResponse::from)
                        .toList(),
                page,
                size,
                total);
    }

    private void requirePageIsValid(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, was " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, was " + size);
        }
        if (size > maximumPageSize) {
            throw new IllegalArgumentException(
                    "size must not exceed " + maximumPageSize + ", was " + size);
        }
    }
}
```

**LOOK:** `FundId.of(rawFundId)` is the **first** line of both methods.

**WHY:** Text from the URL becomes a domain type immediately. A blank id throws **here**, not deeper in the system.

**EXAMPLE — measured:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/funds/%20"
```
```
400
```

**No validation code was written in this file for that case.** `FundId` did it.

**LOOK:** `size > maximumPageSize`

**CRITICAL CONCEPT — unrestricted resource consumption.**

**EXAMPLE — measured:**

```bash
curl -s "http://localhost:8080/api/funds/FUND-00000/positions?size=5000"
```
```json
{"code":"INVALID_INPUT","message":"size must not exceed 200, was 5000","at":"..."}
```

**INSIDE:** The check runs **before** `repository.countPositions`. No connection was taken from the pool. A bad request costs almost nothing.

**PRODUCTION ROLE:** OWASP lists this in its API Top 10. It needs no attacker — one careless client is enough.

---

### FILE · `FundController.java` · EXISTING

```java
package dev.hedge.api;

import dev.hedge.api.ApiResponses.FundResponse;
import dev.hedge.api.ApiResponses.PageResponse;
import dev.hedge.api.ApiResponses.PositionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/funds")
class FundController {

    private final FundQueryService funds;

    FundController(FundQueryService funds) {
        this.funds = funds;
    }

    @GetMapping("/{fundId}")
    FundResponse fund(@PathVariable String fundId) {
        return funds.fund(fundId);
    }

    @GetMapping("/{fundId}/positions")
    PageResponse<PositionResponse> positions(
            @PathVariable String fundId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return funds.positions(fundId, page, size);
    }
}
```

**LOOK:** The controller has **no `if` statements** and no business rules.

**ARCHITECT DECISION — separation of responsibilities:**

| File | Knows about |
|---|---|
| `FundController` | HTTP only — paths, parameters |
| `FundQueryService` | Rules — limits, not-found |
| `FundRepository` | SQL only |
| `Money`, `FundId` | Financial meaning |

**WHY it matters:** to test the rules you do not need HTTP. To change the URL you do not touch the rules.

---

### FILE · `ApiError.java` · EXISTING

```java
package dev.hedge.api;

import java.time.Instant;

public record ApiError(String code, String message, Instant at) {

    static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
```

**LOOK:** `code` and `message` are separate.

**WHY:** `code` is for programs. `message` is for people. A client can check `FUND_NOT_FOUND` without reading English.

---

### FILE · `FundNotFoundException.java` · EXISTING

```java
package dev.hedge.api;

class FundNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    FundNotFoundException(String fundId) {
        super("no fund with id " + fundId);
    }
}
```

---

### FILE · `ApiExceptionHandler.java` · EXISTING

```java
package dev.hedge.api;

import dev.hedge.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidInput(IllegalArgumentException failure) {
        LOG.info("rejected a request: {}", failure.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_INPUT", failure.getMessage()));
    }

    @ExceptionHandler(FundNotFoundException.class)
    ResponseEntity<ApiError> notFound(FundNotFoundException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("FUND_NOT_FOUND", failure.getMessage()));
    }

    @ExceptionHandler(PersistenceException.class)
    ResponseEntity<ApiError> databaseUnavailable(PersistenceException failure) {
        LOG.error("the database could not serve a request", failure);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("DATABASE_UNAVAILABLE", "the service cannot reach its database"));
    }
}
```

**LOOK:** The database handler sends a **fixed** message, and logs the real one.

**CRITICAL CONCEPT — do not leak internals in errors.**

| Goes to the caller | Goes to the log |
|---|---|
| `the service cannot reach its database` | Full stack trace, SQL state, table names |

**WHY:** A database error message can contain table names, column names, and sometimes data. The caller gets what they need to retry. You get what you need to fix it.

**LOOK:** `503 SERVICE_UNAVAILABLE`, not 500.

**WHY:** 503 means *try again later*. Clients and load balancers understand it and retry. A 500 means *something is broken* and a careful client will not retry.

**EXAMPLE — the three statuses this file produces:**

| Situation | Status | Code |
|---|---|---|
| Blank or oversized input | 400 | `INVALID_INPUT` |
| Fund does not exist | 404 | `FUND_NOT_FOUND` |
| Database unreachable | 503 | `DATABASE_UNAVAILABLE` |

---

### FILE · `DatabaseHealthIndicator.java` · EXISTING

```java
package dev.hedge.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("db")
class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    DatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select 1")) {
            return rows.next()
                    ? Health.up().withDetail("database", "PostgreSQL").build()
                    : Health.down().withDetail("reason", "the check query returned nothing").build();
        } catch (SQLException failure) {
            return Health.down(failure).build();
        }
    }
}
```

**WHY this file exists:** my first attempt used Spring's built-in `db` check. The application **refused to start**:

```
Health contributor 'db' defined in
'management.endpoint.health.group.readiness.include' does not exist
```

Spring only creates that check when the JDBC starter is present — which we deliberately do not use.

**LOOK:** `executeQuery("select 1")`

**WHY a real query, not just `getConnection()`:** a pooled connection can look open while the database behind it is gone. Running a query proves the database actually answers.

**LOOK:** `@Component("db")` — the name must be exactly `db`, because `application.yaml` refers to it by that name.

---

### FILE · `application.yaml` · EXISTING

```yaml
server:
  port: 8080
  shutdown: graceful

spring:
  application:
    name: hedge-api
  lifecycle:
    timeout-per-shutdown-phase: 25s

hedge:
  db:
    url: ${HEDGE_DB_URL:jdbc:postgresql://localhost:5432/hedge_trading_test}
    user: ${HEDGE_DB_USER:${user.name}}
    password: ${HEDGE_DB_PASSWORD:}
    pool-size: ${HEDGE_DB_POOL_SIZE:10}
  api:
    max-page-size: 200

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
  metrics:
    tags:
      application: hedge-api
    distribution:
      percentiles-histogram:
        http.server.requests: true
      slo:
        http.server.requests: 10ms,50ms,100ms,200ms,500ms,1s
```

**LOOK — the six most important lines in Phase 1:**

```yaml
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
```

**CRITICAL CONCEPT — liveness is not readiness.**

| Question | If it fails | Consequence |
|---|---|---|
| **liveness** | Is the process broken? | **Restart it** |
| **readiness** | Can it serve right now? | **Stop sending traffic** |

**WRONG WAY:**

```yaml
        liveness:
          include: livenessState,db      # ← db in liveness
```

**WHAT GOES WRONG:** The database goes down. Every instance reports "not alive". The platform restarts **all of them at once**. Restarting does not fix a database. When it comes back, every instance reconnects at the same moment and hits it again.

**One database problem becomes a total outage plus a restart storm.**

**EXAMPLE — measured on your running API:**

```json
liveness : {"components":{"livenessState":{"status":"UP"}},"status":"UP"}
readiness: {"components":{"db":{...},"readinessState":{"status":"UP"}},"status":"UP"}
```

The word `db` appears in readiness and **not** in liveness.

---

**LOOK:** `${HEDGE_DB_URL:jdbc:postgresql://localhost:5432/hedge_trading_test}`

**WHAT:** Use the environment variable if set, else this default.
**WHY:** The same jar runs on your laptop and on a server. **No passwords in the file.**

**EXAMPLE — measured, changing the port with no code change:**

```bash
SERVER_PORT=8099 ./gradlew :applications:hedge-api:bootRun
```
```
Tomcat started on port 8099
```

---

**LOOK:** `shutdown: graceful` + `timeout-per-shutdown-phase: 25s`

**WHAT:** On stop, finish in-flight requests, then exit. Wait up to 25 seconds.
**WHY:** Without it, stopping the service drops requests that were already being handled.

---

**LOOK:** `percentiles-histogram` and `slo`

**CRITICAL CONCEPT — you cannot average percentiles.**

**EXAMPLE — this is why those 5 lines exist.** Without them:

```
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_bucket  →  0 series
```

A `summary` publishes only count, sum and max. The query `histogram_quantile(...)` returns **nothing**.

**With them — measured:**

```
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_bucket  →  150 series
```

**The cost — measured:**

| | Total metric series |
|---|---|
| Without buckets | **131** |
| With buckets | **370** |

**TRADE-OFF:** 239 extra series per instance. Worth it, because only buckets can be added across instances. **A pre-computed p95 from 8 servers cannot be averaged — that is mathematically meaningless.**

---

### FILE · `FundControllerTest.java` · EXISTING

```java
package dev.hedge.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.Fund;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import dev.hedge.persistence.FundRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FundControllerTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private FundRepository repository;

    private final HttpClient client = HttpClient.newHttpClient();
    private Fund fund;

    @BeforeEach
    void seed() {
        fund = new PortfolioGenerator().generate(WorkloadSpec.tiny(2024)).funds().get(0);
        if (repository.findById(fund.id()).isEmpty()) {
            repository.save(fund);
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a known fund is returned with its accounts and positions")
    void a_known_fund_is_returned() throws Exception {
        HttpResponse<String> response = get("/api/funds/" + fund.id().value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"fundId\":\"" + fund.id().value() + "\"");
        assertThat(response.body()).contains("\"positionCount\":" + fund.positionCount());
    }

    @Test
    @DisplayName("an unknown fund returns 404 with a readable error code")
    void an_unknown_fund_returns_404() throws Exception {
        HttpResponse<String> response = get("/api/funds/NO-SUCH-FUND");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"FUND_NOT_FOUND\"");
    }

    @Test
    @DisplayName("a page larger than the limit is refused before the database is touched")
    void an_oversized_page_is_refused() throws Exception {
        HttpResponse<String> response = get("/api/funds/" + fund.id().value() + "/positions?size=5000");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_INPUT\"");
        assertThat(response.body()).contains("must not exceed 200");
    }

    @Test
    @DisplayName("positions are returned one page at a time with the full total")
    void positions_are_paged() throws Exception {
        HttpResponse<String> response = get("/api/funds/" + fund.id().value() + "/positions?page=0&size=2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"page\":0", "\"size\":2", "\"total\":" + fund.positionCount());
    }

    @Test
    @DisplayName("a blank fund id is refused by the domain type, not by the controller")
    void a_blank_fund_id_is_refused() throws Exception {
        HttpResponse<String> response = get("/api/funds/%20");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_INPUT\"");
    }

    @Test
    @DisplayName("the health endpoint reports the database separately from liveness")
    void liveness_does_not_depend_on_the_database() throws Exception {
        assertThat(get("/actuator/health/liveness").body()).doesNotContain("\"db\"");
        assertThat(get("/actuator/health/readiness").body()).contains("\"db\"");
    }
}
```

**LOOK:** `HttpClient` from the JDK, not Spring's test tools.

**WHY:** Spring Boot 4 split its test modules — `MockMvc` and `TestRestTemplate` are no longer in `spring-boot-starter-test`. The JDK client works on any version and tests **real HTTP over a real socket**, which is a stronger test.

**LOOK:** `liveness_does_not_depend_on_the_database`

**ARCHITECT:** This test protects a **configuration** decision, not code. Someone adding `db` to the liveness group will break this test and read why.

---

### FILE · `infrastructure/prometheus/prometheus.yml` · EXISTING

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: hedge-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["localhost:8080"]
```

**WHAT:** Tells Prometheus to ask your API for its numbers every 5 seconds.
**WHY:** Your API only reports what is true **right now**. Prometheus keeps history.
**ARCHITECT:** Without history you cannot answer *"when did it start getting slow?"*

---

## B5.1 · Install Prometheus

### STEP — Install

**DO:**

```bash
brew install prometheus
```

### STEP — Start it

**WHAT:** Start the program that collects and stores your metrics.
**WHY:** You need history to diagnose anything.

**GO TO:** A new terminal window (**Cmd + N**).

**DO:**

```bash
prometheus --config.file=$HOME/Desktop/Hedge_Trading/infrastructure/prometheus/prometheus.yml --web.listen-address=:9090
```

**SEE:** lines ending with `Server is ready to receive web requests.`

**CHECK:** Open a browser at `localhost:9090` → **Status** → **Target health** → one target `hedge-api`, state **UP**.

**IF it says DOWN:** your API is not running.

---

## B.FINAL · Commands to start everything

**Three terminals, in this order:**

**Terminal 1 — PostgreSQL** (already running as a background service):

```bash
pg_isready
```

**Terminal 2 — the API:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :applications:hedge-api:bootRun
```

Leave it. It stays at `92% EXECUTING` for as long as the server runs.

**Terminal 3 — Prometheus:**

```bash
prometheus --config.file=$HOME/Desktop/Hedge_Trading/infrastructure/prometheus/prometheus.yml --web.listen-address=:9090
```

**Terminal 4 — your working terminal**, for curl and Gradle commands.

---

## Build and test everything

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew test --write-locks
```

**Expect:** `BUILD SUCCESSFUL` and **74 tests**.

| Module | Tests |
|---|---|
| domain | 39 |
| generator | 23 |
| persistence | 6 |
| hedge-api | 6 |
| **total** | **74** |

---

## Layers 3–5 — what is already measured

| Check | Measured |
|---|---|
| API startup | 1.455 seconds |
| `GET /api/funds/FUND-00000` | 200, 6 positions |
| Unknown fund | 404 `FUND_NOT_FOUND` |
| `size=5000` | 400 `must not exceed 200` |
| Blank fund id | 400 |
| Paging `size=2` | 2 items, `total: 6` |
| Round trip `identical` | `true` |
| Save / load | 4 ms / 2 ms |
| Migration run twice | 20 ms, no changes |
| Metrics with buckets | 370 series |
| `hikaricp_connections_pending` | present |
| liveness contains `db` | **no** |
| readiness contains `db` | **yes** |
| Locked libraries, hedge-api | 118 |

---

**END OF PART 2.**

Say **CONTINUE** for **Part 3**: PART C (run and see everything, step by step with DBeaver and Prometheus), PART D (run each file on its own with JShell), and PART E (architecture experiments).
