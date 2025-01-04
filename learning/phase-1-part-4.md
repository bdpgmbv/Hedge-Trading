# PHASE 1 — PART 4 of 4

Your system right now: API `200` · Prometheus `200` · **150 bucket series** · 298 total series · pool max 10. The histogram fix is live.

---

# PART F — CRITICAL ARCHITECT CONCEPT LAB

Nine concepts live in your code. Each has a wrong way, a right way, and measured evidence.

---

## CONCEPT 1 — Connection pool saturation

**1. WHAT:** The pool holds a fixed number of database connections. When all are busy, requests wait.

**2. WHY:** This is the most common cause of a slow API that has nothing wrong with its code.

**3. WHERE:** `modules/persistence/src/main/java/dev/hedge/persistence/DataSources.java`

```java
config.setMaximumPoolSize(maximumPoolSize);
config.setConnectionTimeout(5_000);
```

**4. OUR DESIGN:** Pool size from configuration, default 10. A thread waits at most 5 seconds, then fails.

**5. WRONG WAY:**

```java
config.setMaximumPoolSize(500);     // "more is faster"
config.setConnectionTimeout(0);     // "wait forever"
```

**6. WHAT GOES WRONG:** 500 connections overload PostgreSQL itself. `connectionTimeout(0)` means a request never gives up — threads pile up until the server runs out of memory.

**7. RIGHT WAY:** A small bounded pool with a timeout, so saturation appears in **your** metrics rather than inside the database.

**8. DIFFERENCE:**

| | Wrong | Ours |
|---|---|---|
| Pool size | 500 | 10 |
| Timeout | never | 5s |
| Where the queue is visible | inside PostgreSQL | in `hikaricp_connections_pending` |

**9. RUN THE WRONG WAY — a tiny pool makes the queue obvious:**

**DO — terminal 1, stop the API, then:**

```bash
cd ~/Desktop/Hedge_Trading && HEDGE_DB_POOL_SIZE=2 ./gradlew :applications:hedge-api:bootRun
```

**DO — create the load script:**

```bash
cat > /tmp/load.js <<'EOF'
import http from 'k6/http';
export const options = { vus: 40, duration: '20s' };
export default function () {
  http.get('http://localhost:8080/api/funds/FUND-00000');
}
EOF
```

**DO — terminal 2:**

```bash
k6 run /tmp/load.js
```

**DO — terminal 4, while it runs:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "hikaricp_connections_(active|idle|pending)\{" | sed 's/{application[^}]*}//'
```

**10. SEE — measured:**

```
hikaricp_connections_active   2.0
hikaricp_connections_idle     0.0
hikaricp_connections_pending  37.0
```

**11. RUN THE RIGHT WAY — pool of 10, identical load:**

```bash
cd ~/Desktop/Hedge_Trading && HEDGE_DB_POOL_SIZE=10 ./gradlew :applications:hedge-api:bootRun
```

Run the same k6 command and sample again.

**12. VERIFY — measured comparison:**

| | Pool 2 | Pool 10 |
|---|---|---|
| active | 2 | 10 |
| pending | **37** | **28** |
| p95 latency | 6.78 ms | 5.34 ms |
| throughput | 9,934/s | 12,803/s |

**🧠 THE SURPRISE: 5× the connections gave 1.29× the throughput, and the queue did not go away.**

**13. TRADE-OFF:** The pool was never the real limit — the database and CPU under it were. A bigger pool moved the queue from your application into PostgreSQL, where you cannot see it.

**14. PRODUCTION IMPACT:** `pending` is a **leading indicator**. In the pool-2 run, latency was still fine at 6.78 ms while 37 threads waited. **The metric warned before any user noticed.**

**15. INTERVIEW VALUE: Very strong.** The standard answer is "increase the pool". The measured answer is far better.

**16. RESET:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :applications:hedge-api:bootRun
```

---

## CONCEPT 2 — Unrestricted resource consumption

**1. WHAT:** A client asks for more data than the service should ever return.

**2. WHY:** One careless client can stop the service for everyone. No attacker needed.

**3. WHERE:** `applications/hedge-api/src/main/java/dev/hedge/api/FundQueryService.java`

```java
if (size > maximumPageSize) {
    throw new IllegalArgumentException(
            "size must not exceed " + maximumPageSize + ", was " + size);
}
```

**4. OUR DESIGN:** Cap of 200, checked **before** any database call.

**5. WRONG WAY:**

```java
PageResponse<PositionResponse> positions(String id, int page, int size) {
    return repository.findPositions(FundId.of(id), size, (long) page * size);  // no limit
}
```

**6. WHAT GOES WRONG:** `?size=1000000` loads a million rows into memory and serialises them to JSON.

**8. DIFFERENCE:**

| Request | Wrong way | Ours |
|---|---|---|
| `size=50` | 50 rows | 50 rows |
| `size=1000000` | **1,000,000 rows read** | 400 in microseconds |

**9. RUN THE WRONG WAY:** Delete those 4 lines, restart the API.

```bash
curl -s -w "\n%{http_code}\n" "http://localhost:8080/api/funds/FUND-00000/positions?size=1000000"
```

**10. SEE:** `200`, with `"size":1000000`. **With only 6 rows nothing bad happens — which is exactly the danger.**

**11. RUN THE RIGHT WAY:** Put the 4 lines back, restart.

```bash
curl -s -w "\n%{http_code}\n" "http://localhost:8080/api/funds/FUND-00000/positions?size=5000"
```

**12. VERIFY — measured:**

```json
{"code":"INVALID_INPUT","message":"size must not exceed 200, was 5000","at":"..."}
400
```

**13. TRADE-OFF:** A client needing 500 rows makes 3 calls. That is the correct trade.

**14. PRODUCTION IMPACT:** OWASP lists this in its API Top 10. **The harm is invisible with small data and appears only once the data grows** — by which time the fix is a breaking API change.

**15. INTERVIEW VALUE: Strong.** Most candidates mention authentication and forget resource limits.

---

## CONCEPT 3 — The N+1 query problem

**1. WHAT:** Loading a parent, then running one extra query per child.

**2. WHY:** It looks fine with 2 accounts and destroys the service with 200.

**3. WHERE:** `FundRepository.findById`

**4. OUR DESIGN — 4 queries, always:**

```
1. select base_currency from funds        where fund_id = ?
2. select account_id    from accounts     where fund_id = ?
3. select ... from positions     join accounts ... where a.fund_id = ?
4. select ... from cash_balances join accounts ... where a.fund_id = ?
```

**5. WRONG WAY:**

```java
for (AccountId accountId : accountIds) {
    List<Position> positions = readPositionsForOneAccount(connection, accountId);  // 1 query each
    accounts.add(new Account(accountId, positions, List.of()));
}
```

**6. WHAT GOES WRONG:**

```
2 accounts    →  1 + 2   =   3 queries    fine
200 accounts  →  1 + 200 = 201 queries    not fine
```

**7. RIGHT WAY — one query for all accounts, grouped in memory:**

```java
Map<AccountId, List<Position>> positions = readPositions(connection, id);
// ...
positions.getOrDefault(accountId, List.of())
```

**8. DIFFERENCE:**

| Accounts | Wrong way | Ours |
|---|---|---|
| 2 | 3 | **4** |
| 20 | 21 | **4** |
| 200 | 201 | **4** |

**9. RUN AND COUNT — turn on query logging:**

```bash
psql -d hedge_trading_test -c "alter database hedge_trading_test set log_statement = 'all';"
```

**DO — restart the API, then make one request:**

```bash
curl -s -o /dev/null http://localhost:8080/api/funds/FUND-00000
```

**DO — count the selects:**

```bash
tail -40 $(brew --prefix)/var/log/postgresql@17.log | grep -c "select"
```

**10. SEE:** a small number — around 4 to 6 for the whole fund, **not 1 per account**.

**12. VERIFY:** Add a second fund with more accounts and repeat. The count stays the same.

**16. RESET:**

```bash
psql -d hedge_trading_test -c "alter database hedge_trading_test reset log_statement;"
```

**13. TRADE-OFF:** We fetch more rows at once and group them in Java — slightly more memory, far fewer round trips.

**15. INTERVIEW VALUE: Strong**, and extremely common.

---

## CONCEPT 4 — Transaction boundaries

**1. WHAT:** Several writes either all succeed or all disappear.

**2. WHY:** A half-saved fund is worse than no fund, because nothing reports it.

**3. WHERE:** `FundRepository.save` and `DataSources`

```java
try {
    writeFund(connection, fund);
    connection.commit();
} catch (SQLException failure) {
    connection.rollback();
    throw new PersistenceException("could not save fund " + fund.id(), failure);
}
```

```java
config.setAutoCommit(false);
```

**5. WRONG WAY:**

```java
config.setAutoCommit(true);   // every insert saves immediately
```

**6. WHAT GOES WRONG — step by step:**

| Step | auto-commit ON | auto-commit OFF |
|---|---|---|
| insert fund | **saved** | pending |
| insert account 1 | **saved** | pending |
| insert account 2 | fails | fails |
| caller sees | an error | an error |
| database holds | **fund + account 1** | **nothing** |

**9. RUN THE WRONG WAY:** Delete the line `connection.rollback();`

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:persistence:test
```

**10. SEE:**

```
FundRepositoryTest > a failed save leaves nothing behind ... FAILED
```

**11. RUN THE RIGHT WAY:** Put the line back. Run again.

**12. VERIFY:** `BUILD SUCCESSFUL`.

**13. TRADE-OFF:** A transaction holds a connection for its whole duration. **This is why transactions must be short** — a long one occupies a connection that others are waiting for, which connects directly back to Concept 1.

**14. PRODUCTION IMPACT:** The dangerous case is not the error. It is the **data left behind that nobody knows about**.

**15. INTERVIEW VALUE: Strong**, especially stated as the failure mode rather than the mechanism.

---

## CONCEPT 5 — The database as the last line of defence

**1. WHAT:** The database enforces its own rules, even against tools that bypass your code.

**3. WHERE:** `db.changelog-master.sql`

```sql
primary key (account_id, security_id)
account_id  text not null references accounts (account_id)
```

**5. WRONG WAY:** No constraints. "The application checks it."

**6. WHAT GOES WRONG:** Any bug, script, migration or direct SQL edit can write data your application would reject.

**9. RUN THE WRONG WAY — bypass your application completely.** In DBeaver:

```sql
insert into positions (account_id, security_id, quantity)
values ('NO-SUCH-ACCOUNT', 'SEC-00000', 1);
```

**10. SEE:**

```
ERROR: insert or update on table "positions" violates foreign key constraint
       "positions_account_id_fkey"
Detail: Key (account_id)=(NO-SUCH-ACCOUNT) is not present in table "accounts".
```

**DO — and a duplicate:**

```sql
insert into positions (account_id, security_id, quantity)
values ('ACC-00000-000', 'SEC-00001', 1);
```

**SEE:**

```
ERROR: duplicate key value violates unique constraint "positions_pkey"
```

**12. VERIFY nothing was written:**

```bash
psql -d hedge_trading_test -tAc "select count(*) from positions;"
```

**SEE:** `6`

**🧠 THIS RULE CAUGHT A BUG IN OUR OWN CODE:**

```
Layer 1  Account accepted duplicate securities   ← no rule in Java
Layer 2  the generator produced them             ← nothing stopped it
Layer 3  the database refused them               ← the rule existed only here
```

**EXAMPLE — why duplicates are meaningless:**

| Row | account | security | quantity |
|---|---|---|---|
| 1 | ACC-00000-000 | SEC-00000 | 526 |
| 2 | ACC-00000-000 | SEC-00000 | 318 |

How much does the account hold? **526, 318, or 844?** Three programs, three answers, same data.

**13. TRADE-OFF:** Constraints cost write performance and make some migrations harder. Worth it for financial data.

**15. INTERVIEW VALUE: Very strong.** "The database found a rule our domain model was missing" is a real story with code behind it.

---

## CONCEPT 6 — Exact decimals, in every layer

**1. WHAT:** Money must never touch floating point.

**3. WHERE — three places:**

| Layer | File | Rule |
|---|---|---|
| Java | `DomainBoundaryTest.java` | bans `double` and `float` fields |
| Database | `db.changelog-master.sql` | `numeric(28,8)`, not `float8` |
| API | `ApiResponses.java` | `"quantity": "390"` — a **string** |

**9. RUN — see why, in the database:**

```bash
psql -d hedge_trading_test -c "select (0.1::float8 + 0.2::float8 = 0.3::float8) as float_equal, (0.1::numeric + 0.2::numeric = 0.3::numeric) as numeric_equal;"
```

**10. SEE:**

```
 float_equal | numeric_equal
-------------+---------------
 f           | t
```

**RUN — see why, on the wire:**

```bash
curl -s http://localhost:8080/api/funds/FUND-00000 | grep -o '"quantity":"[0-9]*"' | head -3
```

**SEE:** `"quantity":"390"` — with quotes.

**WHY the quotes:** JSON numbers are read as floating point by most clients. **`0.1` sent as a JSON number can arrive as `0.1000000000000000055`.**

**11. RUN THE WRONG WAY — prove the Java rule fires.** Create `BadMoney.java`:

```java
package dev.hedge.domain;

public record BadMoney(double amount, String currencyCode) {
}
```

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:test
```

**12. SEE:**

```
no_money_is_held_in_floating_point FAILED
Field <dev.hedge.domain.BadMoney.amount> has raw type double
```

**16. RESET:** Delete `BadMoney.java` or comment its contents out.

**15. INTERVIEW VALUE: Moderate.** Everyone knows "don't use double for money". **Showing it enforced in three layers by three different mechanisms is what makes it senior.**

---

## CONCEPT 7 — Liveness is not readiness

**1. WHAT:** Two different health questions with two different consequences.

**3. WHERE:** `application.yaml`

```yaml
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
```

**4. OUR DESIGN:**

| Question | If it fails | Consequence |
|---|---|---|
| **liveness** | Is the process broken? | **Restart it** |
| **readiness** | Can it serve now? | **Stop sending traffic** |

**5. WRONG WAY:**

```yaml
        liveness:
          include: livenessState,db
```

**6. WHAT GOES WRONG:**

```
database goes down
        ↓
every instance reports "not alive"
        ↓
the platform restarts ALL of them at once
        ↓
restarting does not fix a database
        ↓
database recovers → every instance reconnects at once → hits it again
```

**One database problem becomes a total outage plus a restart storm.**

**9. RUN THE WRONG WAY:** change liveness to `livenessState,db`, restart, then:

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :applications:hedge-api:test
```

**10. SEE:**

```
liveness_does_not_depend_on_the_database FAILED
```

**11. RUN THE RIGHT WAY:** change it back, restart.

**12. VERIFY — measured:**

```bash
curl -s http://localhost:8080/actuator/health/liveness; echo
curl -s http://localhost:8080/actuator/health/readiness; echo
```

```json
{"components":{"livenessState":{"status":"UP"}},"status":"UP"}
{"components":{"db":{"details":{"database":"PostgreSQL"},"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}
```

`db` is in readiness and **not** in liveness.

**14. PRODUCTION IMPACT:** With the split: database down → readiness DOWN → traffic stops → **processes keep running** → database returns → readiness UP → traffic returns. **No restarts at all.**

**15. INTERVIEW VALUE: Very strong.** One of the most common Kubernetes mistakes. Spring's own documentation warns about it.

---

## CONCEPT 8 — Metric cardinality

**1. WHAT:** Every unique combination of labels creates a separate stored series.

**3. WHERE:** `application.yaml`, plus Spring's automatic URI templating.

**9. RUN:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'http_server_requests_seconds_count' | head -2
```

**10. SEE:** `uri="/api/funds/{fundId}"` — the **template**, not `FUND-00000`.

**5. WRONG WAY:** putting the real id in the label.

**6. WHAT GOES WRONG:**

```
4,000 funds → 4,000 separate metrics → Prometheus runs out of memory
```

**AND THE COST YOU CHOSE — measured:**

| | Total series |
|---|---|
| Without histogram buckets | **131** |
| With histogram buckets | **370** |
| Your instance right now | **298** |

**12. VERIFY:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -vc "^#"
```

**13. TRADE-OFF — why we accept it:**

| Option | Can compute p95? | Can combine across 8 servers? |
|---|---|---|
| `summary` (default) | **no** | — |
| `percentiles: [0.95]` | yes, per instance | **no — averaging percentiles is meaningless** |
| `percentiles-histogram` | yes | **yes** |

**Only buckets can be added up.** That is why 239 extra series is the right price.

**15. INTERVIEW VALUE: Strong.** Knowing *why* you cannot average percentiles separates people who have run monitoring from people who have configured it.

---

## CONCEPT 9 — Hiding implementation types

**1. WHAT:** `implementation` hides a library from callers. `api` exposes it.

**3. WHERE:** `modules/persistence/build.gradle.kts`

```kotlin
api(libs.micrometer.core)       // callers CAN see MeterRegistry
implementation(libs.hikari)     // callers CANNOT see HikariDataSource
```

**🔬 THIS CAUGHT A REAL MISTAKE.** My first `PersistenceConfig.java` used `HikariDataSource`:

```
error: package com.zaxxer.hikari does not exist
```

**The design was working.** The fix was to hide Hikari better — `DataSources.pooled` now returns `javax.sql.DataSource`.

**5. WRONG WAY:**

```kotlin
api(libs.hikari)                // every caller now compiles against Hikari
```

**6. WHAT GOES WRONG:** Swapping the connection pool later means changing every caller, not one file.

**12. VERIFY:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:dependencies --configuration runtimeClasspath
```

**SEE:** `No dependencies`

**13. TRADE-OFF:** You must design the public surface deliberately. That is work, and it is the work.

**15. INTERVIEW VALUE: Moderate to strong**, especially with the compile error as evidence.

---

## Critical Concept Summary

| Concept | File | Wrong Approach | Our Approach | Verified? |
|---|---|---|---|---|
| Pool saturation | `DataSources.java` | Huge pool, no timeout | Bounded 10, 5s timeout | ☐ |
| Resource limits | `FundQueryService.java` | No page cap | Cap 200, checked first | ☐ |
| N+1 queries | `FundRepository.java` | 1 query per account | 4 queries total | ☐ |
| Transactions | `FundRepository.java` | autoCommit true | Explicit commit/rollback | ☐ |
| DB constraints | `db.changelog-master.sql` | Trust the app | PK + FK enforced | ☐ |
| Exact decimals | 3 files | `double` / JSON number | `BigDecimal`, `numeric`, string | ☐ |
| Liveness vs readiness | `application.yaml` | `db` in liveness | `db` in readiness only | ☐ |
| Metric cardinality | `application.yaml` | Real id in label | URI template + buckets | ☐ |
| Hiding types | `build.gradle.kts` | `api(hikari)` | `implementation(hikari)` | ☐ |

**None are verified.** Tick each only after you run it.

---

# PART G — COMPLETE TESTING

**Working directory for every command:** `~/Desktop/Hedge_Trading`

## G1 · Success cases

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| All tests pass | `./gradlew test` | `BUILD SUCCESSFUL` · 74 tests | Baseline |
| Read a fund | `curl -s localhost:8080/api/funds/FUND-00000` | 200, 6 positions | Full stack works |
| Read one page | `curl -s "localhost:8080/api/funds/FUND-00000/positions?page=0&size=2"` | 2 items, `total:6` | Paging in SQL |
| Round trip exact | `./gradlew :modules:persistence:test --tests "*DbProbe*" -i \| grep PROBE` | `identical: true` | 3 layers agree |

## G2 · Invalid input

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| Blank fund id | `curl -s -w "\n%{http_code}\n" "localhost:8080/api/funds/%20"` | 400 `INVALID_INPUT` | Layer 1 type threw, not the API |
| Page too big | `curl -s "localhost:8080/api/funds/FUND-00000/positions?size=5000"` | 400 `must not exceed 200` | Checked before the database |
| Negative page | `curl -s "localhost:8080/api/funds/FUND-00000/positions?page=-1"` | 400 `must not be negative` | All bounds checked |
| Size zero | `curl -s "localhost:8080/api/funds/FUND-00000/positions?size=0"` | 400 `at least 1` | Lower bound too |
| Unknown currency | JShell: `Money.of("1.00","XXXX")` | `IllegalArgumentException` | JDK validates ISO codes |
| Extra decimals | JShell: `Money.of("1.005","USD")` | `ArithmeticException` | Refused, not rounded |

## G3 · Missing data

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| Unknown fund | `curl -s -w "\n%{http_code}\n" localhost:8080/api/funds/NOPE` | 404 `FUND_NOT_FOUND` | Missing is not an error |
| Page past the end | `curl -s "localhost:8080/api/funds/FUND-00000/positions?page=99&size=2"` | `{"items":[],...,"total":6}` | Empty list, status 200 |
| Empty repository read | In `FundRepositoryTest` | `Optional.empty()` | No exception for absence |

## G4 · Missing configuration

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| Wrong DB address | `HEDGE_DB_URL="jdbc:postgresql://localhost:5999/x" ./gradlew :applications:hedge-api:bootRun` | `Connection refused` · `APPLICATION FAILED TO START` | Fail fast — see Failure 1 |
| Different port | `SERVER_PORT=8099 ./gradlew :applications:hedge-api:bootRun` | `Tomcat started on port 8099` | Config from environment |

**Reset:** restart normally.

## G5 · Database failure

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| FK violation | DBeaver: insert position for `NO-SUCH-ACCOUNT` | `violates foreign key constraint` | Database refuses orphans |
| PK violation | DBeaver: insert duplicate `(ACC-00000-000, SEC-00001)` | `duplicate key value` | One position per security |
| Rollback works | Delete `connection.rollback();`, run persistence tests | `a failed save leaves nothing behind` FAILS | All or nothing |

**Reset:** restore the line; both inserts wrote nothing anyway.

## G6 · Security cases

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| SQL injection attempt | `curl -s -w "\n%{http_code}\n" "localhost:8080/api/funds/x%27%3B%20drop%20table%20funds%3B%20--"` | **404**, tables intact | `?` parameters are values, never code |
| Verify nothing dropped | `psql -d hedge_trading_test -tAc "select count(*) from funds;"` | `1` | — |
| Error leaks nothing | Stop PostgreSQL, call the API | `the service cannot reach its database` | No table names, no SQL |
| **No authentication** | `curl -s localhost:8080/api/funds/FUND-00000` | **200 — anyone can read** | ⚠️ **Real gap. Phase 7.** |

**🧠 The last row is the most important test in this table.** The API has **no authentication at all**. Anyone who reaches the port reads every fund.

## G7 · Edge cases

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| Smallest value | In `FundRepositoryTest` | `0.00000001` survives | `numeric(28,8)` limit |
| Zero-decimal currency | JShell: `Money.of("100","JPY")` | `100 JPY` | Currency decides scale |
| Trailing zeros | JShell: `Quantity.of("100").equals(Quantity.of("100.00"))` | `true` | One stored form |
| Same security, two accounts | Your data: `SEC-00004` in both | Allowed | Rule is per account |
| Order does not matter | JShell: two Accounts, reversed lists | `true` | Canonical order |

## G8 · Integration

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| Generator → DB → API | `./gradlew :modules:persistence:test --tests "*DbProbe*" -i \| grep PROBE` | `identical: true` | Layers 1–3 agree |
| DB change visible in API | Update a quantity in DBeaver, commit, curl | New value | No cache yet |
| Metrics reflect requests | 5 requests, check counter | Rises by 5 | Self-measurement |
| Prometheus scraping | `localhost:9090` → Target health | `hedge-api` UP | History is being kept |

## G9 · Recovery

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| Migration is repeatable | Run tests twice | Second migrate ≈ 20 ms, no change | Safe to re-run |
| Pool recovers after load | After k6 finishes, check `pending` | Back to `0.0` | No leak |
| Readiness recovers | Stop PostgreSQL, restart it, wait 10s | readiness UP again, **no restart** | See Failure 3 |

## G10 · Performance

| WHAT / WHY | DO | SEE | ARCHITECT LESSON |
|---|---|---|---|
| Generation speed | JShell, 50,000 positions | ~21 ms | Test data is free |
| Save / load | `DbProbe` | 4 ms / 2 ms | Small funds are cheap |
| Throughput | `k6 run /tmp/load.js` | ~12,800 req/s | One laptop, one instance |
| p95 latency | Prometheus `histogram_quantile(0.95, ...)` | 0.002–0.01 s | Tail, not average |

---

# PART H — PRODUCTION FAILURE LAB

Three failures genuinely apply to Phase 1.

---

## FAILURE 1 — The database is unreachable at startup

**1. WHAT:** The service starts while the database cannot be reached.

**2. WHY:** This happens during deploys, network changes and database restarts.

**3. NORMAL:** API starts in ~1.5 s, `status: UP`.

**4. INTRODUCE FAILURE — safely, without touching your real database.** Port 5999 has nothing listening.

**5. RUN:**

```bash
cd ~/Desktop/Hedge_Trading && HEDGE_DB_URL="jdbc:postgresql://localhost:5999/hedge_trading_test" ./gradlew :applications:hedge-api:bootRun
```

**6. SEE FAILURE — measured:**

```
Caused by: java.net.ConnectException: Connection refused
APPLICATION FAILED TO START
```

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
```
```
000
```

Nothing is listening. The process never started.

**7. TRACE:**

```
PersistenceConfig.dataSource()
        ↓
DataSources.pooled(...)          creates the pool
        ↓
SchemaMigrator.migrate(ds)       needs a connection  ← fails here
        ↓
Spring context fails to start
```

**8. ROOT CAUSE:** `PersistenceConfig` runs the migration during startup. A migration needs a working database.

**9. FIX — this is a design choice, not a bug.**

| Design | Good | Bad |
|---|---|---|
| **Fail fast** *(ours)* | Never runs against a wrong schema | A database blip during a deploy means **no instance starts** |
| **Start degraded** | Starts, readiness DOWN, recovers alone | Could serve traffic on an un-migrated schema |

**13. ARCHITECT DECISION:** Fail fast now. Phase 1 is a single instance, and a wrong schema is the worse outcome.

**12. PREVENT:** In production, run migrations as their own job **before** the deploy. The application then only checks the schema version — a read, which can be retried.

**WHEN WE REVISIT:** Phase 7, rolling deployments.

**14. RESET:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :applications:hedge-api:bootRun
```

**15. INTERVIEW VALUE: Strong.** *"Should your service start if its database is down?"* has no single right answer. Having measured both sides is what makes the answer good.

---

## FAILURE 2 — Connection pool exhaustion

**1. WHAT:** All connections busy, requests queue.

**2. WHY:** Most common cause of "the API got slow and nothing changed".

**3. NORMAL:**

```
active 0.0 · idle 10.0 · pending 0.0
```

**4–5. INTRODUCE AND RUN:** start with `HEDGE_DB_POOL_SIZE=2`, run `k6 run /tmp/load.js`, sample the pool.

**6. SEE FAILURE — measured:**

```
hikaricp_connections_active   2.0
hikaricp_connections_idle     0.0
hikaricp_connections_pending  37.0
```

**DIAGNOSE FROM PROMETHEUS — do not read the code.** At `localhost:9090`:

```
hikaricp_connections_pending
```

Click **Graph**. The line climbs from 0 to ~37 and stays there.

**7. TRACE:**

```
k6 (40 users)
     ↓
FundController → FundQueryService → FundRepository.findById()
     ↓
dataSource.getConnection()        ← waits here
     ↓
pool: 2 connections, 0 free
```

**8. ROOT CAUSE:** 40 users want a connection. Only 2 exist.

**9. FIX attempt:** raise the pool to 10, same load.

**11. SEE RECOVERY — measured:**

| | Pool 2 | Pool 10 |
|---|---|---|
| pending | 37 | **28** |
| p95 | 6.78 ms | 5.34 ms |
| throughput | 9,934/s | **12,803/s** |

**🧠 5× the connections gave 1.29× the throughput, and the queue did not go away.**

**ROOT CAUSE, corrected:** the pool was never the real limit. Adding connections moved the queue into PostgreSQL, where it is harder to see.

**12. PREVENT:** Alert on `hikaricp_connections_pending > 0 for 5 minutes`. **Notice p95 was still fine at 6.78 ms while 37 threads waited — the metric warned before any user noticed.**

**13. ARCHITECT DECISION:** Keep the pool small enough that saturation appears in **your** metrics.

**14. RESET:** restart with the default pool.

**15. INTERVIEW VALUE: Very strong.**

---

## FAILURE 3 — The database goes down while running

**1. WHAT:** The database disappears after the service has started.

**2. WHY:** This is the real production case. Failure 1 was only about startup.

**⚠️ I did not run this one.** Stopping PostgreSQL would also stop your other database, `rtat`. **You control your machine — your call.**

**3. NORMAL:** readiness UP, API returns 200.

**4–5. INTRODUCE AND RUN:**

```bash
brew services stop postgresql@17
```

```bash
curl -s http://localhost:8080/actuator/health/readiness; echo
curl -s http://localhost:8080/actuator/health/liveness; echo
curl -s -w "\n%{http_code}\n" http://localhost:8080/api/funds/FUND-00000
```

**6. EXPECT:**

| Check | Expected |
|---|---|
| readiness | **DOWN** |
| liveness | **UP** |
| API call | **503** `DATABASE_UNAVAILABLE` |
| Response body | **no** table names, **no** SQL |

**7. TRACE:**

```
DatabaseHealthIndicator.health()   → "select 1" fails → Health.down()
        ↓
readiness group includes db        → readiness DOWN
liveness group does NOT include db → liveness stays UP
        ↓
FundRepository throws PersistenceException
        ↓
ApiExceptionHandler → 503, generic message, full stack to the log
```

**11. SEE RECOVERY:**

```bash
brew services start postgresql@17
```

Wait 10 seconds, then check readiness again. **It should return to UP with no restart.**

**12. PREVENT:** This is already prevented. The liveness/readiness split means traffic stops but processes survive.

**15. INTERVIEW VALUE: Very strong.** This is the single most valuable experiment in Phase 1 — it proves the configuration works.

---

# PART I — SENIOR ARCHITECT INTERVIEW SCENARIOS

**All three are things you built and measured locally — not production experience. Say so if asked.**

---

### SCENARIO 1 — "How do you prevent a currency bug?"

**SCENARIO:** A conversion done in the wrong direction returns a plausible wrong number.

**DESIGN DECISION:** `FxRate` refuses money that is not in its base currency.

**WHY:** The wrong answer looks normal, so no test catches it by inspection.

**ALTERNATIVE:** Pass currencies as strings and rely on convention and code review.

**TRADE-OFF:** More types to write. In exchange the mistake cannot compile into a wrong number.

**PROOF — measured by removing the check:**

```
WRONG: 110.00 USD
RIGHT:  90.91 EUR
```

**INTERVIEW ANSWER:** *"Money carries its currency, and an FX rate knows its direction. Converting the wrong way throws instead of returning a number. I removed the check to measure the damage — 110.00 instead of 90.91, so 21% off with the wrong currency attached, and no error anywhere. That is why it is a type rule and not a code review rule."*

**FOLLOW-UP:** *"Why not just write a test?"*
*"A test covers the paths you wrote. The type covers every path, including ones written next year by someone who never saw the test."*

**INTERVIEW VALUE:** Shows you think about failures that do not announce themselves.

---

### SCENARIO 2 — "Your API is slow. Where do you look first?"

**SCENARIO:** Latency rises with no code change.

**DESIGN DECISION:** The connection pool publishes `active`, `idle` and `pending`, and `pending` is the alert.

**ALTERNATIVE:** Add application logging on slow requests.

**TRADE-OFF:** Pool metrics cost almost nothing and are a leading indicator. Logging is after the fact and expensive at volume.

**PROOF — measured:**

| | Pool 2 | Pool 10 |
|---|---|---|
| pending | 37 | 28 |
| throughput | 9,934/s | 12,803/s |

**INTERVIEW ANSWER:** *"First `hikaricp_connections_pending`. Above zero means requests are waiting for a connection. I ran it deliberately — pool of 2, 40 users, pending sat at 37 while p95 was still 6.8 ms. So the pool warned me before latency moved. I then raised the pool to 10 and throughput only rose 29%, which told me the pool was not the real bottleneck."*

**FOLLOW-UP:** *"So should you have raised it further?"*
*"No. Pending stayed high at 10, so more connections would move the queue into PostgreSQL where I cannot see it. The next step is to find what the database is actually spending time on."*

**INTERVIEW VALUE:** Shows you changed one variable, measured, and let the data correct your first theory.

---

### SCENARIO 3 — "Should your service start if its database is down?"

**DESIGN DECISION:** Fail fast now; separate the migration later.

**ALTERNATIVE:** Start degraded with readiness DOWN.

**TRADE-OFF:** Fail fast guarantees schema correctness. It also means a database blip during a rolling deploy prevents every instance from starting.

**PROOF — measured:** `Connection refused`, `APPLICATION FAILED TO START`, health endpoint `000`.

**INTERVIEW ANSWER:** *"Today it fails fast, because it runs its Liquibase migration at startup and I would rather not start than run against an unknown schema. The cost is real — a database blip during a rolling deploy means no instance starts. For a multi-instance deployment I would move the migration to a separate job and let the application start, report readiness DOWN, and recover on its own."*

**FOLLOW-UP:** *"What stops it serving traffic before the schema is ready?"*
*"The readiness probe. Traffic only arrives when readiness is UP, and readiness includes the database check."*

**INTERVIEW VALUE:** Shows you can name the cost of your own decision and the condition that would change it.

---

### NOT strong enough to use

| Topic | Why not |
|---|---|
| "I used BigDecimal for money" | Expected |
| "I wrote tests" | Expected |
| "I used a connection pool" | Expected — the **measurement** is the story |
| "I used Liquibase" | Expected |

---

# PART J — PRODUCTION READINESS REVIEW

## ✅ IMPLEMENTED and VERIFIED by me

| Practice | Evidence |
|---|---|
| Layer separation | `domain` → `No dependencies` |
| Exact decimals, 3 layers | `numeric(28,8)` · `BigDecimal` · JSON strings |
| Transactions | `a failed save writes nothing` passes |
| Database constraints | FK and PK refuse rows from DBeaver |
| Input validation | 400 on blank id and oversized page |
| Error contract | 404 / 400 / 503 with codes, no internals leaked |
| SQL injection safe | `?` parameters throughout |
| Health checks | liveness and readiness split, measured |
| Metrics | 298 series including pool and latency buckets |
| Migrations | 5 changesets, each with a rollback |
| Reproducible builds | 4 lockfiles, guard fired twice for real |
| Graceful shutdown | configured, 25 s |
| Configuration external | port and DB from environment |

## ☐ IMPLEMENTED, NOT YET VERIFIED BY YOU

Everything in the Part F summary table, plus Failures 1, 2 and 3.

## ⏳ PENDING — needs later phases

| Missing | Phase |
|---|---|
| **Authentication — the API is fully open** | 7, Layer 46 |
| **Fund isolation — any caller reads any fund** | 7, Layer 46 |
| Request timeouts and load shedding | 7 |
| Rate limiting | 7 |
| Tracing | 7 |
| Log aggregation | 7 |
| Multi-instance behaviour | 7 |
| Real scale (millions of rows) | 3, 8 |
| Migration as a separate job | 7, Layer 53 |

## ⛔ NOT APPLICABLE to Phase 1

Caching, message queues, distributed transactions, idempotency keys, circuit breakers, event ordering. **None exist yet, and adding them now would be complexity without a problem.**

## The honest verdict

**Phase 1 is NOT production-ready.** Two reasons are serious:

1. **No authentication.** Anyone reaching the port reads every fund.
2. **No request timeout.** One slow query can hold a connection indefinitely.

Everything else is sound foundation work.

---

# PART K — FINAL VERIFICATION AND ARCHITECTURE STORY

## K1 · The complete architecture

```
                    curl / browser
                          │
                          ▼
         ┌─────────────────────────────────┐
         │  applications/hedge-api   L4+L5 │
         │  FundController      HTTP only  │
         │  FundQueryService    rules      │
         │  ApiResponses        public shape│
         │  ApiExceptionHandler errors     │
         │  DatabaseHealthIndicator        │
         │  PersistenceConfig   wiring     │
         └───────────────┬─────────────────┘
                         ▼
         ┌─────────────────────────────────┐
         │  modules/persistence       L3   │
         │  FundRepository   SQL only      │
         │  DataSources      pool          │
         │  SchemaMigrator   Liquibase     │
         └───────────────┬─────────────────┘
                         ▼
                   PostgreSQL 17.11
         funds · accounts · securities
         positions · cash_balances
         + databasechangelog

   modules/generator  ──►  modules/domain       L2 → L1
   build-logic  (shared build rules)            L0

   Prometheus :9090 ──scrapes──► /actuator/prometheus
   DBeaver           ──reads───► PostgreSQL
```

## K2 · The complete execution flow

```
 1. curl GET /api/funds/FUND-00000
         ↓   FundController.fund()
 2. FundQueryService.fund("FUND-00000")
         ↓   FundId.of()  — blank throws here
 3. FundRepository.findById()
         ↓   takes 1 connection from the pool of 10
 4. 4 SQL queries, not 1 per account
         ↓
 5. Account rebuilds each list — sorted, no duplicates
         ↓   Layer 1 rules run again on the way back
 6. FundResponse.from(fund) — amounts become strings
         ↓
 7. JSON out, connection returned to the pool
         ↓
 8. Micrometer counts it under uri="/api/funds/{fundId}"
         ↓
 9. Prometheus scrapes it 5 seconds later
```

**WHY THIS FLOW:** each file knows one thing. HTTP knowledge is in one file, rules in another, SQL in a third, financial meaning in a fourth.

**DESIGN BENEFIT:** You can test the rules without HTTP, and run the financial types without a database — which you did in Part 3 with JShell.

**IF SOMETHING FAILS:** step 3 throws `PersistenceException`, `ApiExceptionHandler` turns it into 503, the full error goes to the log, the caller gets a generic message.

## K3 · The important design decisions

1. **The domain depends on nothing.** Runs in JShell with no database, no Spring.
2. **Rules live in types, not in checks.** No layer re-validates what a type guarantees.
3. **The same rule is enforced everywhere it reaches.** Exact decimals exist in Java, in SQL, and on the wire.
4. **Dependencies point one way only.** Delete the generator and the domain still builds.
5. **Build rules were extracted at the third copy, not the second.**
6. **Liveness excludes the database.** Prevents a restart storm.
7. **Fail fast on a missing database** — a decision with a known cost, to revisit in Phase 7.

## K4 · The critical concepts you can now demonstrate

| Concept | Demonstrated by |
|---|---|
| Pool saturation | k6 + `pending` 37 vs 28 |
| Resource limits | `size=5000` → 400 |
| N+1 queries | 4 queries regardless of account count |
| Transaction boundaries | rollback test fails without it |
| Database constraints | FK and PK refuse DBeaver inserts |
| Exact decimals | `float_equal f` / `numeric_equal t` |
| Liveness vs readiness | `db` in one group only |
| Metric cardinality | URI template, 131 → 370 series |
| Hiding implementation types | `No dependencies` + the Hikari compile error |
| Determinism | same seed `true`, shared Random `false` |

## K5 · Final checklist

| | Item | Command |
|---|---|---|
| ☐ | 74 tests pass | `./gradlew test` |
| ☐ | Domain isolated | `./gradlew :modules:domain:dependencies --configuration runtimeClasspath` |
| ☐ | API serves a fund | `curl localhost:8080/api/funds/FUND-00000` |
| ☐ | 404 / 400 behave | PART G2, G3 |
| ☐ | Prometheus target UP | `localhost:9090` → Status → Target health |
| ☐ | **p95 query returns data** | PART C12 |
| ☐ | DBeaver shows 7 tables | PART B3.3 |
| ☐ | 5 rows in `databasechangelog` | PART B3.3 |
| ☐ | JShell — `110.00 USD` and the refusal | PART D |
| ☐ | JShell — generator `true` then `false` | PART D |
| ☐ | **E1 — `150.00 USD` from mixing currencies** | PART E |
| ☐ | **E3 — `false` for the same seed** | PART E |
| ☐ | E4 — rollback test fails without it | PART E |
| ☐ | E6 — liveness test fails with `db` | PART E |
| ☐ | **Failure 1 — `APPLICATION FAILED TO START`** | PART H |
| ☐ | **Failure 2 — `pending 37`, then pool 10 compared** | PART H |
| ☐ | **Failure 3 — readiness DOWN, liveness UP** | PART H — your call |
| ☐ | SQL injection attempt returns 404, tables intact | PART G6 |
| ☐ | All temporary changes reset | — |
| ☐ | Final `./gradlew test` green | — |

## K6 · What Phase 1 now enables

| Phase 2 needs | Phase 1 provides |
|---|---|
| Correct money types | `Money`, `FxRate`, exact decimals in 3 layers |
| Reproducible test data | `PortfolioGenerator` from one seed |
| Somewhere to store results | PostgreSQL + versioned migrations |
| A way to measure | Metrics, k6, Prometheus, p95 |
| A way to see failures | Health groups, error contract, pool metrics |

**Phase 2 builds the calculator** — turning positions and prices into currency exposure. It will use every one of those five.

---

## What you have at the end of Phase 1

```
59 files      ·  4 modules  ·  74 tests
5 tables      ·  5 migrations, each reversible
298 metrics   ·  3 health groups
9 architect concepts, each with a wrong-way experiment
3 production failures, two of them measured
```

---

**⏸️ PHASE 1 DELIVERY COMPLETE.**

Work through the checklist. The six in **bold** are the ones that teach the most — each shows a protection failing when removed.

When the checklist is done, say **PHASE 2** and I will deliver Layers 6, 7 and 8: the authoritative calculator, property-based correctness testing, and the performance baseline that Capstone 1 is measured against.
