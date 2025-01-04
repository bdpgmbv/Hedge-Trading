# PHASE 1 — PART 3 of 4

**Your system is live.** API `200`, Prometheus `200`, 1 fund, 6 positions. Every example below uses **your actual data**.

### Your data, for reference

| Account | Security | Quantity |
|---|---|---|
| ACC-00000-000 | SEC-00001 | 390 |
| ACC-00000-000 | SEC-00003 | 776 |
| ACC-00000-000 | SEC-00004 | 660 |
| ACC-00000-001 | SEC-00000 | 303 |
| ACC-00000-001 | SEC-00002 | 488 |
| ACC-00000-001 | SEC-00004 | 412 |

| Account | Cash |
|---|---|
| ACC-00000-000 | 27,247.06 EUR |
| ACC-00000-001 | 37,335.08 USD |

**Notice:** `SEC-00004` appears in **both** accounts. That is allowed. The rule is one position per security **per account**, not per fund.

---

# PART C — RUN AND SEE EVERYTHING

Keep terminal 1 (API) and terminal 3 (Prometheus) running. Use a separate terminal for everything below.

---

## STEP C1 — Read one fund

**WHAT:** Ask the API for a portfolio.
**WHY:** This is the whole point of Phase 1 — data in the database, readable from outside.

**DO:**

```bash
curl -s http://localhost:8080/api/funds/FUND-00000 | python3 -m json.tool
```

**SEE:**

```json
{
    "fundId": "FUND-00000",
    "baseCurrency": "USD",
    "positionCount": 6,
    "accounts": [
        {
            "accountId": "ACC-00000-000",
            "positions": [
                {"securityId": "SEC-00001", "quantity": "390"},
                {"securityId": "SEC-00003", "quantity": "776"},
                {"securityId": "SEC-00004", "quantity": "660"}
            ],
            "cash": [{"amount": "27247.06", "currency": "EUR"}]
        },
        ...
    ]
}
```

**CHECK three things:**

| Look at | Should be |
|---|---|
| `"quantity": "390"` | In **quotes** — a string |
| Order of securities | `SEC-00001, SEC-00003, SEC-00004` — sorted |
| `"amount": "27247.06"` | 2 decimals, because EUR has 2 |

**INSIDE:**

```
1. curl  →  GET /api/funds/FUND-00000
        ↓   FundController.fund()
2. FundQueryService.fund("FUND-00000")
        ↓   FundId.of() — blank would throw here
3. FundRepository.findById()
        ↓   takes 1 connection from the pool of 10
4. 4 SQL queries
        ↓
5. Account rebuilds lists — sorted, no duplicates
        ↓   Layer 1 rules run again on the way back
6. FundResponse.from(fund) — amounts become strings
        ↓
7. JSON out, connection returned to the pool
```

**ARCHITECT LESSON:** The database stored `390.00000000`. The API returned `"390"`. `Quantity.stripTrailingZeros()` did that. **Without it, the round-trip test would fail.**

**CONNECTS:**
```
PostgreSQL → FundRepository → Account (Layer 1) → ApiResponses → JSON
```

---

## STEP C2 — Prove the database stores more decimals than the API shows

**WHAT:** Compare what the database holds with what the API returns.
**WHY:** To see where normalisation happens.

**DO:**

```bash
psql -d hedge_trading_test -tAc "select quantity from positions where security_id='SEC-00001';"
```

**SEE:**

```
390.00000000
```

**DO:**

```bash
curl -s http://localhost:8080/api/funds/FUND-00000 | grep -o '"securityId":"SEC-00001","quantity":"[^"]*"'
```

**SEE:**

```
"securityId":"SEC-00001","quantity":"390"
```

**INSIDE:** `numeric(28,8)` always returns 8 decimals. `Quantity` strips the trailing zeros when the object is rebuilt.

**ARCHITECT LESSON:** The database stores one fixed shape. The domain decides the canonical form. **Both are correct; they answer different questions.**

---

## STEP C3 — Ask for a fund that does not exist

**WHAT:** See the error contract.
**WHY:** Errors are part of your API, not an accident.

**DO:**

```bash
curl -s -w "\nstatus=%{http_code}\n" http://localhost:8080/api/funds/NOPE
```

**SEE:**

```json
{"code":"FUND_NOT_FOUND","message":"no fund with id NOPE","at":"2026-10-11T..."}
status=404
```

**CHECK:** 404, and there is a `code` field.

**INSIDE:** `FundQueryService` threw `FundNotFoundException`. `ApiExceptionHandler.notFound` caught it and built the JSON.

**ARCHITECT LESSON:** `code` is for programs, `message` is for people. A client checks `FUND_NOT_FOUND` without parsing English.

---

## STEP C4 — Ask for too much data

**WHAT:** Try to make the service load a huge result.
**WHY:** One careless client must not be able to stop the service.

**DO:**

```bash
curl -s -w "\nstatus=%{http_code}\n" "http://localhost:8080/api/funds/FUND-00000/positions?size=5000"
```

**SEE:**

```json
{"code":"INVALID_INPUT","message":"size must not exceed 200, was 5000","at":"..."}
status=400
```

**CHECK:** 400, not 500. The message names **both** numbers.

**INSIDE:** `requirePageIsValid` threw **before** `repository.countPositions`. **No connection was taken from the pool.**

**ARCHITECT LESSON:** Check limits before doing work. A rejected request should cost almost nothing.

---

## STEP C5 — Read positions one page at a time

**WHAT:** Walk through results in pages.
**WHY:** A fund can have 50,000 positions. You never return them all at once.

**DO — page 0:**

```bash
curl -s "http://localhost:8080/api/funds/FUND-00000/positions?page=0&size=2"
```

**SEE:**

```json
{"items":[{"securityId":"SEC-00001","quantity":"390"},
          {"securityId":"SEC-00003","quantity":"776"}],
 "page":0,"size":2,"total":6}
```

**DO — page 1:**

```bash
curl -s "http://localhost:8080/api/funds/FUND-00000/positions?page=1&size=2"
```

**SEE:** `SEC-00004` and `SEC-00000`. Same `"total":6`.

**DO — page 2:**

```bash
curl -s "http://localhost:8080/api/funds/FUND-00000/positions?page=2&size=2"
```

**SEE:** `SEC-00002` and `SEC-00004`.

**DO — page 3, past the end:**

```bash
curl -s "http://localhost:8080/api/funds/FUND-00000/positions?page=3&size=2"
```

**SEE:**

```json
{"items":[],"page":3,"size":2,"total":6}
```

**CHECK:** An empty list, **not** an error. Asking past the end is a valid question with an empty answer.

**INSIDE:** Page 1 ran `limit 2 offset 2`. **Only 2 rows left the database.**

**ARCHITECT LESSON:** `total` lets the client know there is more without fetching it.

---

## STEP C6 — See the service count its own work

**WHAT:** Look at the numbers the service publishes about itself.
**WHY:** Everything in the failure lab depends on these.

**DO:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep "^http_server_requests_seconds_count" | sed 's/application="hedge-api",//'
```

**SEE:** one line per endpoint and status, for example:

```
http_server_requests_seconds_count{...status="404",uri="/api/funds/{fundId}"} 1
http_server_requests_seconds_count{...status="200",uri="/api/funds/{fundId}"} 3
http_server_requests_seconds_count{...status="400",uri="/api/funds/{fundId}/positions"} 1
```

**CHECK — the important detail:** `uri="/api/funds/{fundId}"`, **not** `/api/funds/FUND-00000`.

**ARCHITECT LESSON — metric cardinality.** If the real id went into the label, 4,000 funds would create 4,000 separate metrics. Prometheus would run out of memory. Spring uses the **route pattern** instead.

---

## STEP C7 — Make a number move

**WHAT:** Send requests and watch a counter rise.
**WHY:** To prove the measurement is real.

**DO — note the current count:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'status="200",uri="/api/funds/{fundId}"'
```

**DO — send 5 requests:**

```bash
for i in 1 2 3 4 5; do curl -s -o /dev/null http://localhost:8080/api/funds/FUND-00000; done
```

**DO — check again:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'status="200",uri="/api/funds/{fundId}"'
```

**SEE:** The number at the end went up by exactly **5**.

**ARCHITECT LESSON:** The service measures itself. You did not add any counting code — Spring counts every request through the controller.

---

## STEP C8 — Look at the connection pool

**WHAT:** See how many database connections exist and how many are in use.
**WHY:** This is the first place to look when an API is slow.

**DO:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "^hikaricp_connections(_active|_idle|_pending)?\{" | sed 's/{application[^}]*}//'
```

**SEE:**

```
hikaricp_connections  10.0
hikaricp_connections_active  0.0
hikaricp_connections_idle  10.0
hikaricp_connections_pending  0.0
```

**CHECK:** `pending` is `0.0`.

**INSIDE:** 10 connections exist. None are in use. Nobody is waiting.

**ARCHITECT LESSON:** **`pending` is the number to watch.** It counts threads waiting for a free connection. Above zero means requests are queueing. In Part 4 you will push it to 37.

---

## STEP C9 — Open Prometheus and check it is collecting

**WHAT:** Confirm Prometheus is reading your API.
**WHY:** A monitoring tool that is not collecting is worse than none, because you trust it.

**GO TO:** Browser, `localhost:9090`

**DO:** Click **Status** in the top menu → **Target health**.

**SEE:** One target, `hedge-api`, state **UP**, with a recent scrape time.

**CHECK:** If it says DOWN, your API is not running.

---

## STEP C10 — Ask Prometheus a question

**WHAT:** Query stored history instead of the current value.
**WHY:** Your API only knows *now*. Prometheus knows *before*.

**GO TO:** `localhost:9090` → click **Query**

**DO:** Type and press Enter:

```
hikaricp_connections_idle
```

**SEE:** A table with the value `10`.

**DO:** Click the **Graph** tab.

**SEE:** A flat line at 10.

**ARCHITECT LESSON:** That flat line is your system being healthy. In Part 4 you will watch it drop to 0 while `pending` climbs — and that picture is the incident.

---

## STEP C11 — Measure requests per second

**WHAT:** Turn a total that only rises into a rate.
**WHY:** "Traffic" is one of the four signals that describe a service.

**DO — in Prometheus:**

```
rate(http_server_requests_seconds_count[1m])
```

**SEE:** A row per endpoint with a small number.

**DO — in your terminal, generate traffic:**

```bash
for i in $(seq 1 100); do curl -s -o /dev/null http://localhost:8080/api/funds/FUND-00000; done
```

**DO:** Wait 10 seconds. Click **Execute** in Prometheus again.

**SEE:** The number for `/api/funds/{fundId}` is clearly higher.

**ARCHITECT LESSON:** `rate(...)` converts a counter into events per second. Counters only ever go up; rates are what you alert on.

---

## STEP C12 — Measure latency properly

**WHAT:** Find the 95th percentile response time.
**WHY:** Averages hide the slow requests that users actually notice.

**DO — in Prometheus:**

```
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[1m])))
```

**SEE:** A row per endpoint with a small number of seconds, around `0.002` to `0.01`.

**IF YOU SEE "Empty query result":** your API is running the old config. Check `application.yaml` contains `percentiles-histogram`, then restart the API.

**INSIDE:** This reads the bucket series and works out the value below which 95% of requests finished.

**ARCHITECT LESSON — never use the average.** If 99 requests take 10 ms and one takes 10 seconds, the average is 110 ms — which describes **no real request**. The 95th percentile describes your slowest normal users.

---

## STEP C13 — Change data in DBeaver and see the API respond

**WHAT:** Edit a row by hand, then read it through the API.
**WHY:** To prove the API reads live data, not a cached copy.

**GO TO:** DBeaver → SQL Editor → **New SQL script**

**DO:**

```sql
update positions set quantity = 999 where security_id = 'SEC-00001';
```

Press **Cmd + Enter**.

**SEE:** `Updated Rows: 1`

**DO — in DBeaver, commit the change.** Look at the toolbar for the **Commit** button, or press **Cmd + Shift + Enter**.

**CHECK:**

```bash
psql -d hedge_trading_test -tAc "select quantity from positions where security_id='SEC-00001';"
```

**SEE:** `999.00000000`

**DO — now read it through the API:**

```bash
curl -s http://localhost:8080/api/funds/FUND-00000 | grep -o '"securityId":"SEC-00001","quantity":"[^"]*"'
```

**SEE:**

```
"securityId":"SEC-00001","quantity":"999"
```

**ARCHITECT LESSON:** No cache exists yet. Every request reads the database. **That is correct for Phase 1** — a cache is only worth adding once you have measured that reads are too slow.

**↩️ RESET:**

```bash
psql -d hedge_trading_test -c "update positions set quantity = 390 where security_id = 'SEC-00001';"
```

**CHECK:**

```bash
curl -s http://localhost:8080/api/funds/FUND-00000 | grep -o '"securityId":"SEC-00001","quantity":"[^"]*"'
```

**SEE:** back to `"390"`.

---

## STEP C14 — Try to insert bad data by hand

**WHAT:** Bypass your application completely and try to write invalid data.
**WHY:** To see that the database protects itself.

**GO TO:** DBeaver SQL Editor

**DO:**

```sql
insert into positions (account_id, security_id, quantity)
values ('NO-SUCH-ACCOUNT', 'SEC-00000', 1);
```

**SEE:**

```
ERROR: insert or update on table "positions" violates foreign key constraint
       "positions_account_id_fkey"
Detail: Key (account_id)=(NO-SUCH-ACCOUNT) is not present in table "accounts".
```

**DO — now try a duplicate:**

```sql
insert into positions (account_id, security_id, quantity)
values ('ACC-00000-000', 'SEC-00001', 1);
```

**SEE:**

```
ERROR: duplicate key value violates unique constraint "positions_pkey"
```

**CHECK — nothing was written:**

```bash
psql -d hedge_trading_test -tAc "select count(*) from positions;"
```

**SEE:** `6`

**ARCHITECT LESSON:** No Java ran in either case. **The database protects the data even from tools that bypass your application.** Your Java rules protect your application. The database rules protect everything.

---

## STEP C15 — Change configuration without changing code

**WHAT:** Start the service on a different port using only an environment variable.
**WHY:** The same build must run anywhere, with only the environment changing.

**DO:** In terminal 1, press **Ctrl + C** to stop the API.

**DO:**

```bash
cd ~/Desktop/Hedge_Trading && SERVER_PORT=8099 ./gradlew :applications:hedge-api:bootRun
```

**SEE:** the log line now says `Tomcat started on port 8099`.

**CHECK:**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8099/actuator/health
```

**SEE:** `200`

**INSIDE:** Spring read `SERVER_PORT` from the environment. No file changed. No rebuild happened.

**ARCHITECT LESSON:** The same jar runs on your laptop and on a server. **Only the environment differs.** That is why no passwords or addresses are written into the code.

**↩️ RESET:** Press **Ctrl + C**, then:

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :applications:hedge-api:bootRun
```

---

# PART D — RUN EACH FILE ON ITS OWN

Java files are not scripts. You cannot run `Money.java`. **JShell** is Java's interactive mode and is the smallest real way to run your code.

---

## FILE EXPERIMENT — `Money.java`

**WHAT:** Holds an amount together with its currency, and refuses wrong values.
**WHY:** A number with no currency is not a fact.

**NEEDS:** Only the compiled domain module. **No database. No API. No Spring.**

**RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:classes -q && jshell --class-path "$PWD/modules/domain/build/classes/java/main"
```

**SEE:** a prompt — `jshell>`

**EXAMPLE INPUT — type these one at a time, pressing Enter after each:**

```java
import dev.hedge.domain.*
import java.math.RoundingMode
Money.of("100", "USD")
Money.of("100", "JPY")
```

**SEE:**

```
$3 ==> 100.00 USD
$4 ==> 100 JPY
```

**TRACE:** `Money` asked `java.util.Currency` how many decimals each currency uses. USD said 2. JPY said 0.

**CHANGE — ask for a value the type refuses:**

```java
Money.of("1.005", "USD")
```

**SEE DIFFERENCE:**

```
java.lang.ArithmeticException: Rounding necessary
```

**CHANGE — try to add two currencies:**

```java
Money.of("100.00","USD").plus(Money.of("50.00","EUR"))
```

**SEE:**

```
java.lang.IllegalArgumentException: cannot combine USD with EUR
```

**CHANGE — see rounding being chosen:**

```java
Money.of("10.00","USD").multipliedBy(new java.math.BigDecimal("1.0049"), RoundingMode.HALF_UP)
Money.of("10.00","USD").multipliedBy(new java.math.BigDecimal("1.0049"), RoundingMode.DOWN)
```

**SEE:**

```
$8 ==> 10.05 USD
$9 ==> 10.04 USD
```

**ARCHITECT LESSON:** Same inputs, two answers. There is no default, because a default silently applies one person's choice to every calculation in the system.

**RESET:** type `/exit`

---

## FILE EXPERIMENT — `FxRate.java`

**WHAT:** Converts money from one currency to another, in one direction only.
**WHY:** Converting the wrong way gives a plausible wrong number.

**NEEDS:** domain classes only.

**RUN:** same JShell command as above.

**EXAMPLE INPUT:**

```java
import dev.hedge.domain.*
import java.math.RoundingMode
var eurUsd = FxRate.of("EUR","USD","1.10")
eurUsd.convert(Money.of("100.00","EUR"), RoundingMode.HALF_UP)
```

**SEE:**

```
$4 ==> 110.00 USD
```

**CHANGE — now the wrong direction:**

```java
eurUsd.convert(Money.of("100.00","USD"), RoundingMode.HALF_UP)
```

**SEE DIFFERENCE:**

```
java.lang.IllegalArgumentException: this rate converts EUR to USD, but was given USD
```

**CHANGE — the correct way to go backwards:**

```java
eurUsd.inverse().convert(Money.of("100.00","USD"), RoundingMode.HALF_UP)
```

**SEE:**

```
$6 ==> 90.91 EUR
```

**ARCHITECT LESSON — compare the two numbers:**

| | Result |
|---|---|
| Wrong direction, **if allowed** | 110.00 USD |
| Correct | 90.91 EUR |

**21% off, wrong currency, no crash.** That is why it is a type rule and not a review rule.

**CHANGE — see precision loss:**

```java
eurUsd.rate()
eurUsd.inverse().inverse().rate()
eurUsd.equals(eurUsd.inverse().inverse())
```

**SEE:**

```
$7 ==> 1.10
$8 ==> 1.1000000000
$9 ==> false
```

**ARCHITECT LESSON:** Store the rate you were given, never one you calculated.

**RESET:** `/exit`

---

## FILE EXPERIMENT — `Account.java`

**WHAT:** Holds the positions and cash of one account, with two rules.
**WHY:** Duplicate positions make a portfolio value ambiguous.

**RUN:** same JShell command.

**EXAMPLE INPUT:**

```java
import dev.hedge.domain.*
import java.util.List
var acc = AccountId.of("ACC-1")
var p1 = new Position(acc, SecurityId.of("S-1"), Quantity.of("10"))
var p2 = new Position(acc, SecurityId.of("S-2"), Quantity.of("20"))
new Account(acc, List.of(p1, p2), List.of()).equals(new Account(acc, List.of(p2, p1), List.of()))
```

**SEE:**

```
$7 ==> true
```

**TRACE:** Different input order, same account. `Account` sorted both lists by security id.

**CHANGE — try a duplicate security:**

```java
var dup = new Position(acc, SecurityId.of("S-1"), Quantity.of("99"))
new Account(acc, List.of(p1, dup), List.of())
```

**SEE DIFFERENCE:**

```
java.lang.IllegalArgumentException: account ACC-1 holds S-1 twice;
a position is the whole holding of one security, so there can be only one
```

**ARCHITECT LESSON:** This rule did not exist until Layer 3's database found it missing. **A rule in only one place is a surprise waiting at a boundary.**

**RESET:** `/exit`

---

## FILE EXPERIMENT — `PortfolioGenerator.java`

**WHAT:** Builds fake portfolios from one number.
**WHY:** A bug you cannot reproduce cannot be investigated.

**NEEDS:** domain **and** generator classes.

**RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:generator:classes -q && jshell --class-path "$PWD/modules/domain/build/classes/java/main:$PWD/modules/generator/build/classes/java/main"
```

**EXAMPLE INPUT:**

```java
import dev.hedge.generator.*
import java.util.List
var g = new PortfolioGenerator()
g.generate(WorkloadSpec.tiny(42)).equals(g.generate(WorkloadSpec.tiny(42)))
```

**SEE:**

```
$4 ==> true
```

**CHANGE — a different seed:**

```java
g.generate(WorkloadSpec.tiny(42)).equals(g.generate(WorkloadSpec.tiny(43)))
```

**SEE:** `$5 ==> false`

**CHANGE — one fund stays the same in a bigger run:**

```java
var one = new WorkloadSpec(42, 1, 2, 3, 5, List.of("USD","EUR"))
var many = new WorkloadSpec(42, 10, 2, 3, 5, List.of("USD","EUR"))
g.generate(one).funds().get(0).equals(g.generate(many).funds().get(0))
```

**SEE:** `$8 ==> true`

**ARCHITECT LESSON:** Fund 0 is identical whether you build 1 fund or 10. You can debug with 1 fund and know it matches the large run. That only works because each fund has its own seed.

**CHANGE — ask for something impossible:**

```java
new WorkloadSpec(1, 1, 1, 10, 5, List.of("USD"))
```

**SEE:**

```
an account cannot hold 10 distinct securities when only 5 exist
```

**CHANGE — measure the cost:**

```java
var big = new WorkloadSpec(1, 200, 5, 50, 500, List.of("USD","EUR","GBP","JPY"))
var t0 = System.nanoTime(); var p = g.generate(big); (System.nanoTime()-t0)/1_000_000
p.positionCount()
```

**SEE:** around `21` milliseconds for `50000` positions.

**ARCHITECT LESSON:** Test data is effectively free, so nobody avoids running tests.

**RESET:** `/exit`

---

## FILE EXPERIMENT — `FundRepository.java`

**WHAT:** Saves and reads funds.
**WHY:** It is the only file that knows SQL.

**NEEDS:** The database running. **This file cannot run alone** — it needs a `DataSource`.

**RUN — the smallest real path is the probe test:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:persistence:test --tests "*DbProbe*" -i 2>&1 | grep "PROBE"
```

**SEE:**

```
PROBE saved    : FUND-00000 positions=6 in 4 ms
PROBE loaded   : FUND-00000 positions=6 in 2 ms
PROBE identical: true
```

**TRACE:** `DbProbe` built a fund with the generator, saved it, read it back, and compared.

**CHANGE — prove reading changes nothing:**

```bash
psql -d hedge_trading_test -tAc "select count(*) from positions;"
curl -s -o /dev/null "http://localhost:8080/api/funds/FUND-00000/positions?size=2"
psql -d hedge_trading_test -tAc "select count(*) from positions;"
```

**SEE DIFFERENCE:** The count is the same both times.

**ARCHITECT LESSON:** A GET endpoint must not modify data. This is how you check, rather than assuming.

---

## FILE EXPERIMENT — `ApiExceptionHandler.java`

**WHAT:** Turns exceptions into JSON with the right HTTP status.
**WHY:** Errors are part of the API contract.

**NEEDS:** The API running.

**RUN — all three cases:**

```bash
curl -s -w "  ← %{http_code}\n" http://localhost:8080/api/funds/NOPE
curl -s -w "  ← %{http_code}\n" "http://localhost:8080/api/funds/FUND-00000/positions?size=9999"
curl -s -w "  ← %{http_code}\n" "http://localhost:8080/api/funds/%20"
```

**SEE:**

```
{"code":"FUND_NOT_FOUND",...}   ← 404
{"code":"INVALID_INPUT",...}    ← 400
{"code":"INVALID_INPUT",...}    ← 400
```

**TRACE:**

| Request | Thrown by | Handled by |
|---|---|---|
| Unknown fund | `FundQueryService` | `notFound` → 404 |
| Oversized page | `requirePageIsValid` | `invalidInput` → 400 |
| Blank id | **`FundId.of()` in Layer 1** | `invalidInput` → 400 |

**ARCHITECT LESSON:** The third case is the interesting one. **No validation code exists in the API for a blank id.** A Layer 1 type threw, and the handler turned it into 400.

---

## FILE EXPERIMENT — `application.yaml`

**WHAT:** All settings, with no values hard-coded in Java.
**WHY:** The same build must run in any environment.

**RUN:**

```bash
curl -s http://localhost:8080/actuator/health | python3 -c "import sys,json;print(json.load(sys.stdin)['groups'])"
```

**SEE:** `['liveness', 'readiness']`

**CHANGE — see the two groups answer different questions:**

```bash
curl -s http://localhost:8080/actuator/health/liveness; echo
curl -s http://localhost:8080/actuator/health/readiness; echo
```

**SEE:**

```json
{"components":{"livenessState":{"status":"UP"}},"status":"UP"}
{"components":{"db":{"details":{"database":"PostgreSQL"},"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}
```

**SEE DIFFERENCE:** `db` appears in readiness and **not** in liveness.

**ARCHITECT LESSON:** If `db` were in liveness, a database outage would restart every instance at once. Restarting does not fix a database. **This configuration prevents a restart storm.**

---

## FILE EXPERIMENT — `DomainBoundaryTest.java`

**WHAT:** Enforces two architecture rules on every build.
**WHY:** A rule in a document is not checked. A rule in a test is.

**RUN — prove the floating-point rule fires:**

**DO:** Create `modules/domain/src/main/java/dev/hedge/domain/BadMoney.java`:

```java
package dev.hedge.domain;

public record BadMoney(double amount, String currencyCode) {
}
```

**DO:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:test
```

**SEE:**

```
no_money_is_held_in_floating_point FAILED
Field <dev.hedge.domain.BadMoney.amount> has raw type double
```

**It names the exact field.**

**RESET:** Delete `BadMoney.java`, or comment out its contents as you did before.

**RUN — prove a rule that checks nothing fails loudly:**

**DO:** In `DomainBoundaryTest.java`, change `packages = "dev.hedge.domain"` to `packages = "dev.hedge.nothing"`.

**DO:** Run the test again.

**SEE:**

```
failed to check any classes. This means either that no classes have been passed
to the rule at all, or that no classes passed to the rule matched the `that()` clause.
```

**ARCHITECT LESSON:** A rule checking zero classes would otherwise pass silently forever and give false confidence. ArchUnit refuses.

**RESET:** change `dev.hedge.nothing` back to `dev.hedge.domain`.

---

# PART E — ARCHITECTURE EXPERIMENTS

Each one changes real code, shows a real result, then restores.

---

## EXPERIMENT E1 — Remove the currency guard

**WHAT:** Let two currencies be added together.
**WHY:** To see what the guard prevents.

**LOOK:** `Money.java`

```java
    public Money plus(Money other) {
        requireSameCurrency(other);
```

**DO:** Delete the line `requireSameCurrency(other);` **inside `plus` only**.

**RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:classes -q && jshell --class-path "$PWD/modules/domain/build/classes/java/main"
```

```java
import dev.hedge.domain.*
Money.of("100.00","USD").plus(Money.of("50.00","EUR"))
```

**SEE:**

```
$2 ==> 150.00 USD
```

**INSIDE:** The amounts were added. The currency of the first one was kept. 50 EUR became 50 USD.

**ARCHITECT LESSON:** No error. No log. A report would print `150.00 USD` and look completely normal. **The guard is the only thing that catches it.**

**RESET:** Put `requireSameCurrency(other);` back. Run `./gradlew test` and confirm green.

---

## EXPERIMENT E2 — Allow silent rounding

**WHAT:** Change how `Money` handles extra decimals.
**WHY:** To see the difference between refusing and guessing.

**LOOK:** `Money.java`

```java
amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
```

**DO:** Change `UNNECESSARY` to `HALF_UP`.

**RUN:** JShell again.

```java
import dev.hedge.domain.*
Money.of("1.005", "USD")
```

**SEE:**

```
$2 ==> 1.01 USD
```

**INSIDE:** You asked for 1.005. You got 1.01. The half cent disappeared and nothing reported it.

**ARCHITECT LESSON:** `UNNECESSARY` throws instead, so the caller must decide what to do. **Over millions of rows, silent rounding is real money.**

**RESET:** Change `HALF_UP` back to `UNNECESSARY`.

---

## EXPERIMENT E3 — Break the seeding

**WHAT:** Use one shared random source for all funds.
**WHY:** To see determinism destroyed.

**LOOK:** `PortfolioGenerator.java`

```java
Random random = new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE);
```

**DO — two changes.** Add this line just after `public final class PortfolioGenerator {`:

```java
    private static final Random SHARED = new Random(42);
```

Then replace the `Random random = ...` line inside `fund(...)` with:

```java
        Random random = SHARED;
```

**RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:generator:classes -q && jshell --class-path "$PWD/modules/domain/build/classes/java/main:$PWD/modules/generator/build/classes/java/main"
```

```java
import dev.hedge.generator.*
var g = new PortfolioGenerator()
g.generate(WorkloadSpec.tiny(42)).equals(g.generate(WorkloadSpec.tiny(42)))
```

**SEE:**

```
$3 ==> false
```

**INSIDE:** **The same seed gave different data.** The shared source keeps advancing, so the second call starts where the first stopped.

**ARCHITECT LESSON:** Nothing can ever be repeated. A failing test from last week can never be investigated.

**RESET:** Delete the `SHARED` line, put `new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE)` back. Run `./gradlew test`.

---

## EXPERIMENT E4 — Remove the rollback

**WHAT:** Let a failed save keep the rows it already wrote.
**WHY:** This is the worst kind of bug — nothing reports it.

**LOOK:** `FundRepository.java`

```java
            } catch (SQLException failure) {
                connection.rollback();
```

**DO:** Delete the line `connection.rollback();`

**RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:persistence:test
```

**SEE:**

```
FundRepositoryTest > a failed save leaves nothing behind ... FAILED
```

**INSIDE:** The test saved a fund whose second account has a duplicate id. The fund row and the first account were written, then the second failed. Without rollback they stayed.

**ARCHITECT LESSON:** The system now holds a fund that was never successfully saved. **The error was reported to the caller, and the wrong data stayed anyway.**

**RESET:** Put `connection.rollback();` back.

---

## EXPERIMENT E5 — Remove the page limit

**WHAT:** Let a client ask for any number of rows.
**WHY:** To see why resource limits exist.

**LOOK:** `FundQueryService.java`

```java
        if (size > maximumPageSize) {
            throw new IllegalArgumentException(
                    "size must not exceed " + maximumPageSize + ", was " + size);
        }
```

**DO:** Delete those 4 lines. Restart the API.

**RUN:**

```bash
curl -s -w "\n%{http_code}\n" "http://localhost:8080/api/funds/FUND-00000/positions?size=1000000"
```

**SEE:** `200`, with all 6 positions and `"size":1000000`.

**INSIDE:** The database ran `limit 1000000`. With only 6 rows nothing bad happened — **but the limit is now whatever the client asks for.**

**ARCHITECT LESSON:** With 50,000 positions this request would load every one into memory and serialise them to JSON. **The danger is not visible with small data, which is exactly why the limit must be written before the data grows.**

**RESET:** Put the 4 lines back. Restart the API. Confirm:

```bash
curl -s "http://localhost:8080/api/funds/FUND-00000/positions?size=5000" | grep -o '"code":"[^"]*"'
```

**SEE:** `"code":"INVALID_INPUT"`

---

## EXPERIMENT E6 — Put the database in liveness

**WHAT:** Make the restart check depend on the database.
**WHY:** To understand why it must not.

**LOOK:** `application.yaml`

```yaml
        liveness:
          include: livenessState
```

**DO:** Change it to:

```yaml
        liveness:
          include: livenessState,db
```

**DO:** Restart the API.

**RUN:**

```bash
curl -s http://localhost:8080/actuator/health/liveness; echo
```

**SEE:** the response now contains `"db"`.

**RUN — the test that protects this:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :applications:hedge-api:test
```

**SEE:**

```
liveness_does_not_depend_on_the_database FAILED
```

**INSIDE:** Liveness now reports DOWN whenever the database is unreachable.

**ARCHITECT LESSON — what would happen in production:**

```
database goes down
        ↓
every instance reports "not alive"
        ↓
the platform restarts ALL of them
        ↓
restarting does not fix a database
        ↓
database recovers → every instance reconnects at once → hits it again
```

**One database problem becomes a total outage plus a restart storm.**

**RESET:** Change it back to `include: livenessState`. Restart. Run the test again and confirm green.

---

## EXPERIMENT E7 — Delete a module and see what breaks

**WHAT:** Check which parts truly depend on which.
**WHY:** Dependency direction is a design decision, and this proves it.

**DO:** In `settings.gradle.kts`, comment out one line:

```kotlin
// include(":modules:generator")
```

**RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:test
```

**SEE:** `BUILD SUCCESSFUL`

**INSIDE:** The domain does not know the generator exists, so removing it changes nothing.

**RUN — now try the other direction:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew test
```

**SEE:** A failure — the persistence and api tests cannot find the generator they use for test data.

**ARCHITECT LESSON:** You could delete the whole generator module and `domain` would still build. **The reverse is not true.** That asymmetry is the architecture.

**RESET:** Uncomment the line. Run `./gradlew test` and confirm 74 tests pass.

---

## Part 3 checklist

| | Step | Item |
|---|---|---|
| ☐ | C1 | Fund JSON, quantity in quotes, securities sorted |
| ☐ | C2 | `390.00000000` in DB, `"390"` in API |
| ☐ | C4 | 400 `must not exceed 200` |
| ☐ | C5 | Page 3 returns empty list, not an error |
| ☐ | C6 | `uri="/api/funds/{fundId}"` not the real id |
| ☐ | C7 | Counter rose by exactly 5 |
| ☐ | C8 | `pending 0.0` |
| ☐ | C9 | Prometheus target UP |
| ☐ | C12 | **p95 query returns a number** |
| ☐ | C13 | Changed data in DBeaver, API showed `999`, reset to `390` |
| ☐ | C14 | Both bad inserts refused, count still 6 |
| ☐ | C15 | Port 8099 worked, no code change |
| ☐ | D | JShell — `110.00 USD` and the refused direction |
| ☐ | D | JShell — generator `true` then `false` |
| ☐ | D | `BadMoney` named by the arch rule |
| ☐ | E1 | **`150.00 USD` from mixing currencies** |
| ☐ | E3 | **`false` for the same seed** |
| ☐ | E4 | Rollback test fails without it |
| ☐ | E6 | Liveness test fails with `db` added |
| ☐ | E7 | Domain builds without the generator |
| ☐ | — | All resets done, `./gradlew test` green, 74 tests |

---

**END OF PART 3.**

Say **CONTINUE** for **Part 4**, the last one: PART F (critical concept lab with wrong-vs-right for all 9 concepts), PART G (complete testing), PART H (production failure lab), PART I (interview scenarios), PART J (production readiness review), and PART K (final verification and the complete architecture story).
