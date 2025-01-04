# PHASE 1 — COMPLETE PACKAGE

**59 files. 6 layers. Delivered in 4 parts.** This is Part 1.

| Part | Contains |
|---|---|
| **1** ← here | PART A (understand) · PART B Layers 0, 1, 2 (all code) |
| **2** | PART B Layers 3, 4, 5 (all code) · install steps |
| **3** | PART C (run and see) · D (file experiments) · E (architecture experiments) |
| **4** | PART F (concept lab) · G (testing) · H (failure lab) · I · J · K |

Your API is running (`200`). All 59 files exist and are correct.

---

# PART A — UNDERSTAND THE PHASE

## A1 · What we are building

A service that stores portfolios in a database and answers web requests about them.

## A2 · Why the application needs it

Three things must exist before any calculation can be trusted:

| Need | Why | Without it |
|---|---|---|
| Correct money types | A wrong number is worse than no number | Silent financial errors |
| Durable storage | Data must survive a restart | Everything lost on stop |
| A way to look inside | You cannot fix what you cannot see | Guessing during incidents |

## A3 · What already exists

**Nothing.** Phase 1 starts from an empty folder.

## A4 · What we are adding

| Layer | Adds | Question it answers |
|---|---|---|
| **0** | Build system | Can this build the same way every time? |
| **1** | Money, FX rate, Fund, Position | Is 100 dollars different from 100 yen? |
| **2** | Data generator | Can I reproduce last month's bug? |
| **3** | PostgreSQL + Liquibase | Does data survive a restart? |
| **4** | HTTP API | Can something outside read it? |
| **5** | Metrics + health | Is it healthy right now? |

## A5 · Architecture

```
                 curl / browser
                       │ HTTP
                       ▼
     ┌─────────────────────────────────┐
     │ applications/hedge-api   L4+L5  │
     │   FundController    HTTP only   │
     │        ↓                        │
     │   FundQueryService  rules       │
     │        ↓                        │
     │   ApiResponses      public shape│
     │   ApiExceptionHandler  errors   │
     │   DatabaseHealthIndicator       │
     └────────────────┬────────────────┘
                      ▼
     ┌─────────────────────────────────┐
     │ modules/persistence        L3   │
     │   FundRepository                │
     │   SchemaMigrator  DataSources   │
     └────────────────┬────────────────┘
                      ▼
                PostgreSQL 17
        funds · accounts · securities
        positions · cash_balances

     modules/generator  ──►  modules/domain      L2 → L1
     build-logic  (shared build rules)           L0

     Prometheus ──scrapes──► /actuator/prometheus
```

**The arrows only point down.** `domain` does not know `persistence` exists. That single fact is the most important thing in this design.

## A6 · What you will run and see at the end

```bash
curl http://localhost:8080/api/funds/FUND-00000
```

```json
{"fundId":"FUND-00000","baseCurrency":"USD","positionCount":6,"accounts":[...]}
```

Read from PostgreSQL. Every value re-checked by Layer 1's rules on the way out.

---

# PART B — BUILD THE PHASE

## Complete folder structure

```
Hedge_Trading/
├── settings.gradle.kts
├── build.gradle.kts                    (empty, on purpose)
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/                     (generated)
├── build-logic/                                           LAYER 0
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/main/kotlin/hedge.java-conventions.gradle.kts
├── infrastructure/prometheus/prometheus.yml               LAYER 5
├── modules/
│   ├── domain/                                            LAYER 1
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/java/dev/hedge/domain/     11 files
│   │       └── test/java/dev/hedge/domain/      9 files
│   ├── generator/                                         LAYER 2
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/java/dev/hedge/generator/   3 files
│   │       └── test/java/dev/hedge/generator/   4 files
│   └── persistence/                                       LAYER 3
│       ├── build.gradle.kts
│       └── src/
│           ├── main/java/dev/hedge/persistence/ 4 files
│           ├── main/resources/db/changelog/     1 file
│           └── test/java/dev/hedge/persistence/ 3 files
└── applications/hedge-api/                                LAYER 4 + 5
    ├── build.gradle.kts
    └── src/
        ├── main/java/dev/hedge/api/             9 files
        ├── main/resources/application.yaml
        └── test/java/dev/hedge/api/             1 file
```

---

# LAYER 0 — THE BUILD SYSTEM

**WHAT:** Make the project build identically on any machine.
**WHY:** If two machines build differently, no measurement means anything.
**INSIDE:** Gradle reads `settings.gradle.kts` first, finds the modules, applies shared rules from `build-logic`, then compiles.
**ARCHITECT:** The build is part of the system, not a tool beside it.

**CONNECTS:**
```
settings.gradle.kts → build-logic → every module
```

---

### FILE · `settings.gradle.kts` · EXISTING

```kotlin
pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "hedge-trading"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(":modules:domain")
include(":modules:generator")
include(":modules:persistence")
include(":applications:hedge-api")
```

**LOOK:** `include(":modules:domain")`

**WHAT:** Lists every part of the system.
**WHY:** A folder not listed here is not in the project, even if its files exist.
**HOW:** Gradle reads this before anything else.
**CONNECTS TO:** Each `build.gradle.kts`.
**PRODUCTION ROLE:** Defines the deployable unit boundaries.
**ARCHITECT DECISION:** Parts are **declared**, never discovered by scanning folders.

**LOOK:** `RepositoriesMode.FAIL_ON_PROJECT_REPOS`

**WHAT:** No module may add its own library download source.
**CRITICAL CONCEPT — supply-chain control.** A module that can choose its own source can pull in anything. One approved source, declared once.

**EXAMPLE — what it blocks:**

```kotlin
// in modules/domain/build.gradle.kts — this would be REFUSED
repositories { maven("https://some-other-server/repo") }
```

---

### FILE · `gradle/libs.versions.toml` · EXISTING

```toml
[versions]
java = "21"
junit = "5.14.4"
assertj = "3.27.7"
archunit = "1.5.1"
postgresql = "42.7.14"
liquibase = "5.0.4"
hikari = "7.1.0"
springBoot = "4.1.1"
micrometer = "1.17.1"

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
liquibase-core = { module = "org.liquibase:liquibase-core", version.ref = "liquibase" }
hikari = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
spring-boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "springBoot" }
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus" }
micrometer-core = { module = "io.micrometer:micrometer-core", version.ref = "micrometer" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
```

**LOOK:** `springBoot = "4.1.1"`

**CRITICAL CONCEPT — never pin to a pre-release.**

Maven Central reports `4.2.0-M2` as newest. **`M` means milestone** — a preview that can change or be withdrawn.

**EXAMPLE — what I actually ran to find the stable one:**

```bash
curl -s "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-gradle-plugin/maven-metadata.xml" \
 | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' \
 | grep -vE 'M[0-9]+$|RC[0-9]+$|SNAPSHOT' | tail -3
```

**Result:** `4.0.8`, `4.1.0`, `4.1.1` → we use **4.1.1**.

**LOOK:** `junit-jupiter` has **no version**.

**WHAT:** It takes its version from `junit-bom`.
**WHY:** A BOM is a list published by the library authors saying which of their parts fit together.
**EXAMPLE:** You set `junit = "5.14.4"` once. JUnit decides what version `junit-jupiter` and `junit-platform-launcher` should be.

---

### FILE · `build-logic/settings.gradle.kts` · EXISTING

```kotlin
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
```

**WHAT:** Lets the shared build rules read the same version list the modules use.
**WHY:** One version list for the whole project, including the build itself.

---

### FILE · `build-logic/build.gradle.kts` · EXISTING

```kotlin
plugins {
    `kotlin-dsl`
}
```

**WHAT:** Lets us write build rules in Kotlin.

---

### FILE · `build-logic/src/main/kotlin/hedge.java-conventions.gradle.kts` · EXISTING

```kotlin
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.getByType

plugins {
    id("java-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion.toInt()
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testImplementation"(libs.findLibrary("archunit-junit5").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

dependencyLocking {
    lockAllConfigurations()
}
```

**LOOK:** `"-Werror"`

**WHAT:** Any compiler warning becomes an error and stops the build.
**WHY:** Warnings allowed to stay will grow. A real problem then hides among forty others.
**ARCHITECT DECISION:** Zero, or it rises. There is no stable number in between.

**EXAMPLE — what triggers it.** Paste this into any domain file:

```java
    public static java.util.List names() {
        java.util.List list = new java.util.ArrayList();
        list.add("x");
        return list;
    }
```

**Measured result:**
```
warning: [rawtypes] found raw type: List
error: warnings found and -Werror specified
1 error
4 warnings
```

That code is **valid Java**. It compiles fine on most projects. You chose this strictness.

**LOOK:** `toolchain { languageVersion = ... 21 }`

**WHAT:** Fixes the Java version used to compile.
**CONNECTS TO:** `gradle/libs.versions.toml`, which holds `21`.
**PRODUCTION ROLE:** Your laptop and a build server must produce the same bytecode.

**LOOK:** `dependencyLocking { lockAllConfigurations() }`

**CRITICAL CONCEPT — reproducible dependencies.**

**WHAT:** Records every library that actually arrived into a `gradle.lockfile`.
**WHY:** If a benchmark took 50 ms last month and 300 ms today, you must be able to prove your code changed and not a library underneath you.

**EXAMPLE — this guard fired twice for real in this project:**

```
Resolved 'org.springframework:spring-web:7.0.9' which is not part of the dependency lock state
Resolved 'io.micrometer:micrometer-core:1.17.1' which is not part of the dependency lock state
```

**ARCHITECT DECISION — why this file exists:** these 40 lines were duplicated in 3 module files. We extracted at the **third** copy. Two copies can be coincidence; three is a pattern, and by then you know which parts are genuinely shared.

**CONNECTS:**
```
hedge.java-conventions.gradle.kts
        ↓ applied by
domain · generator · persistence · hedge-api
```

---

### FILE · `gradle.properties` · EXISTING

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m
```

**WHAT:** Lets Gradle skip work already done.
**INSIDE:** Gradle records each task's inputs. Unchanged inputs mean the previous result is reused.
**EXAMPLE:** Second run of `./gradlew test` with no changes — **310 ms** instead of 7 seconds.

---

### FILE · `build.gradle.kts` (root) · EXISTING — **empty**

```
```

**WHAT:** Nothing. The file is empty.
**WHY:** This file is for rules true of **every** module. Today none exist.

**ARCHITECT DECISION — you found this yourself.** It previously held two settings for reproducible jars. You removed them and the jar checksums did not change.

**CRITICAL CONCEPT — dead configuration.** Settings duplicating a default do nothing, but nobody deletes them because nobody knows what they do. **The test: remove it. If nothing changes, it was doing nothing.**

**Measured proof:** `preserveFileTimestamps = false` is already the Gradle 9 default. Jar entries are stamped `1980-02-01` with or without those lines.

---

### FILE · `.gitignore` · EXISTING

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
!gradle/wrapper/gradle-wrapper.properties

.idea/
*.iml
.vscode/
out/

.DS_Store

*.jfr
*.hprof
hs_err_pid*
```

**LOOK:** `gradle.lockfile` is **not** ignored.
**WHY:** Lockfiles must be committed. They are what makes a recorded result reproducible.

---

### FILE · `modules/domain/build.gradle.kts` · EXISTING

```kotlin
plugins {
    id("hedge.java-conventions")
}

description = "FX domain model: financial value types and their invariants"
```

**LOOK:** There is **no** `dependencies` block.

**CRITICAL CONCEPT — a dependency-free core.**

**Measured:**
```bash
./gradlew :modules:domain:dependencies --configuration runtimeClasspath
```
```
No dependencies
```

**PRODUCTION ROLE:** The financial rules can be run with no database, no Spring, no server. You will do exactly that in Part 3 using JShell.

---

# LAYER 1 — THE FINANCIAL TYPES

**WHAT:** Build types that refuse to hold wrong values.
**WHY:** `100` is not a fact. `100 USD` is.
**INSIDE:** Each type checks itself at creation. A wrong value means no object exists.
**ARCHITECT:** Validation lives in the type, so no later layer repeats it.

**CONNECTS:**
```
text from URL → Layer 1 type → every later layer trusts it
```

---

### FILE · `Identifiers.java` · EXISTING

`modules/domain/src/main/java/dev/hedge/domain/Identifiers.java`

```java
package dev.hedge.domain;

final class Identifiers {

    private Identifiers() {}

    static String validated(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
```

**ARCHITECT DECISION:** The **rule** is shared by three types. The **types stay separate**.

**EXAMPLE — why separate types matter:**

```java
new Position(FundId.of("F-1"), SecurityId.of("S-1"), Quantity.of("1"))
```

**Measured compile error:**
```
error: incompatible types: FundId cannot be converted to AccountId
```

The mistake is caught before the code can run.

---

### FILE · `FundId.java` · EXISTING

```java
package dev.hedge.domain;

public record FundId(String value) {

    public FundId {
        value = Identifiers.validated(value, "a fund id");
    }

    public static FundId of(String value) {
        return new FundId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
```

### FILE · `AccountId.java` · EXISTING

```java
package dev.hedge.domain;

public record AccountId(String value) {

    public AccountId {
        value = Identifiers.validated(value, "an account id");
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
```

### FILE · `SecurityId.java` · EXISTING

```java
package dev.hedge.domain;

public record SecurityId(String value) {

    public SecurityId {
        value = Identifiers.validated(value, "a security id");
    }

    public static SecurityId of(String value) {
        return new SecurityId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
```

**LOOK:** `value.trim()` inside `Identifiers`.

**CRITICAL CONCEPT — one real thing, one stored form.**

**EXAMPLE:**

| Input | Without trim | With trim |
|---|---|---|
| `"FUND-1"` | key `FUND-1` | key `FUND-1` |
| `"FUND-1 "` | key `FUND-1 ` ← **different row** | key `FUND-1` |

You will meet this rule twice more: `Quantity` strips trailing zeros, `Account` sorts positions.

---

### FILE · `Money.java` · EXISTING

```java
package dev.hedge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "an amount must not be null");
        Objects.requireNonNull(currency, "a currency must not be null");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(String currencyCode) {
        return new Money(BigDecimal.ZERO, Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multipliedBy(BigDecimal factor, RoundingMode rounding) {
        return new Money(
                amount.multiply(factor).setScale(currency.getDefaultFractionDigits(), rounding), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("cannot combine "
                    + currency.getCurrencyCode() + " with " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
```

**LOOK:** `record Money(BigDecimal amount, Currency currency)`

**CRITICAL CONCEPT — the value and its unit are one thing.**

**EXAMPLE — measured with the guard removed:**

```
Money.of("100.00","USD").plus(Money.of("50.00","EUR"))
  →  150.00 USD        ← wrong, and nothing crashed
```

If currency were a separate variable, `usdTotal + eurTotal` would compile.

**LOOK:** `setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY)`

**WHAT:** Store the amount at the number of decimals that currency uses.

**EXAMPLE — measured:**

```
Money.of("100", "USD")   →  100.00 USD     (USD has 2 decimals)
Money.of("100", "JPY")   →  100 JPY        (JPY has 0 decimals)
Money.of("1.005", "USD") →  ArithmeticException: Rounding necessary
```

**WHY `UNNECESSARY`:** the half cent is **refused**, not silently removed.

**EXAMPLE — measured with `HALF_UP` instead:**

```
Money.of("1.005", "USD")  →  1.01 USD     ← the half cent vanished, nobody told
```

**ARCHITECT DECISION:** Silent rounding hides where precision was lost. The caller must decide.

**LOOK:** `multipliedBy(BigDecimal factor, RoundingMode rounding)`

**CRITICAL CONCEPT — no hidden defaults.**

**EXAMPLE — measured, same inputs:**

```
10.00 USD × 1.0049  HALF_UP  →  10.05 USD
10.00 USD × 1.0049  DOWN     →  10.04 USD
```

One cent apart. Over millions of rows that is real money. There is no default, because a default silently applies one person's choice everywhere.

---

### FILE · `FxRate.java` · EXISTING

```java
package dev.hedge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record FxRate(Currency base, Currency quote, BigDecimal rate) {

    private static final int INVERSE_SCALE = 10;

    public FxRate {
        Objects.requireNonNull(base, "a base currency must not be null");
        Objects.requireNonNull(quote, "a quote currency must not be null");
        Objects.requireNonNull(rate, "a rate must not be null");
        if (base.equals(quote)) {
            throw new IllegalArgumentException("base and quote currency must differ");
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("an fx rate must be positive, was " + rate.toPlainString());
        }
    }

    public static FxRate of(String base, String quote, String rate) {
        return new FxRate(Currency.getInstance(base), Currency.getInstance(quote), new BigDecimal(rate));
    }

    public Money convert(Money money, RoundingMode rounding) {
        if (!money.currency().equals(base)) {
            throw new IllegalArgumentException("this rate converts " + base.getCurrencyCode()
                    + " to " + quote.getCurrencyCode()
                    + ", but was given " + money.currency().getCurrencyCode());
        }
        return new Money(
                money.amount().multiply(rate).setScale(quote.getDefaultFractionDigits(), rounding), quote);
    }

    public FxRate inverse() {
        return new FxRate(quote, base, BigDecimal.ONE.divide(rate, INVERSE_SCALE, RoundingMode.HALF_UP));
    }

    @Override
    public String toString() {
        return base.getCurrencyCode() + "/" + quote.getCurrencyCode() + " " + rate.toPlainString();
    }
}
```

**LOOK:** The 5-line check at the top of `convert`.

**CRITICAL CONCEPT — make the expensive bug impossible.** This is the most important code in Layer 1.

`EUR/USD = 1.10` means *one EUR is worth 1.10 USD*. Using it on USD money is meaningless.

**EXAMPLE — I deleted those 5 lines and measured:**

```
EXAMPLE INPUT:  100.00 USD, rate EUR/USD 1.10

WRONG (no check):  110.00 USD
RIGHT (inverse) :   90.91 EUR
```

**21% off. Wrong currency. No crash. No log.** A report would print `110.00` and look normal.

**ARCHITECT DECISION:** You cannot test your way out of this, because the wrong answer is a plausible number. **The only reliable defence is a type that refuses.**

**LOOK:** `INVERSE_SCALE = 10`

**CRITICAL CONCEPT — calculated data is not source data.**

**EXAMPLE — measured:**

```
original        : 1.10
inverted twice  : 1.1000000000
same?           : false
```

`1 ÷ 1.10` never ends. 10 decimals were kept, and going back gives a different stored value. **Store the rate you were given, never one you computed.**

---

### FILE · `Quantity.java` · EXISTING

```java
package dev.hedge.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Quantity(BigDecimal value) {

    public Quantity {
        Objects.requireNonNull(value, "a quantity must not be null");
        value = value.stripTrailingZeros();
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    public boolean isShort() {
        return value.signum() < 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
```

**LOOK:** `stripTrailingZeros()`

**EXAMPLE — measured:**

```
Quantity.of("100").equals(Quantity.of("100.00"))  →  true
```

**WHY it matters later:** the database stores `numeric(28,8)`, so `100` comes back as `100.00000000`. Without this line, saving and loading would not produce an equal object.

**LOOK:** `isShort()`

**WHAT:** A negative quantity is allowed.
**WHY:** A negative position is a **short** position. Normal in trading. The type does not reject it — it **names** it.

---

### FILE · `Position.java` · EXISTING

```java
package dev.hedge.domain;

import java.util.Objects;

public record Position(AccountId accountId, SecurityId securityId, Quantity quantity) {

    public Position {
        Objects.requireNonNull(accountId, "a position must belong to an account");
        Objects.requireNonNull(securityId, "a position must refer to a security");
        Objects.requireNonNull(quantity, "a position must have a quantity");
    }
}
```

**LOOK:** There is **no** method that calculates value.

**ARCHITECT DECISION:** Valuation needs a price, an FX rate, and rules for missing data. That is calculation, and it belongs to Phase 2.

**Layer 1 answers *what the data is*, not *what it is worth*.** Mixing them makes both harder to test.

---

### FILE · `Account.java` · EXISTING

```java
package dev.hedge.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Account(AccountId id, List<Position> positions, List<Money> cashBalances) {

    public Account {
        Objects.requireNonNull(id, "an account must have an id");
        Objects.requireNonNull(positions, "positions must not be null");
        Objects.requireNonNull(cashBalances, "cash balances must not be null");
        requireOnePositionPerSecurity(id, positions);
        requireOneBalancePerCurrency(id, cashBalances);
        positions = positions.stream()
                .sorted(Comparator.comparing(position -> position.securityId().value()))
                .toList();
        cashBalances = cashBalances.stream()
                .sorted(Comparator.comparing(balance -> balance.currency().getCurrencyCode()))
                .toList();
    }

    public static Account empty(AccountId id) {
        return new Account(id, List.of(), List.of());
    }

    private static void requireOnePositionPerSecurity(AccountId id, List<Position> positions) {
        Set<SecurityId> seen = new HashSet<>();
        for (Position position : positions) {
            if (!seen.add(position.securityId())) {
                throw new IllegalArgumentException("account " + id + " holds " + position.securityId()
                        + " twice; a position is the whole holding of one security, so there can be only one");
            }
        }
    }

    private static void requireOneBalancePerCurrency(AccountId id, List<Money> cashBalances) {
        Set<String> seen = new HashSet<>();
        for (Money balance : cashBalances) {
            if (!seen.add(balance.currency().getCurrencyCode())) {
                throw new IllegalArgumentException("account " + id + " holds "
                        + balance.currency().getCurrencyCode() + " cash twice");
            }
        }
    }
}
```

**LOOK:** `requireOnePositionPerSecurity`

**CRITICAL CONCEPT — a rule that lives in only one place is a surprise waiting at a boundary.**

**This rule was added in Layer 3, not Layer 1.** The real sequence:

```
Layer 1   Account accepted duplicates      ← no rule in Java
Layer 2   generator produced them          ← nothing stopped it
Layer 3   the database refused them        ← the rule existed only here
```

**EXAMPLE — the actual error that found it:**

```
Batch entry 2 insert into positions ... ('ACC-00000-000'), ('SEC-00000'), (526)
ERROR: duplicate key value violates unique constraint "positions_pkey"
```

**EXAMPLE — why duplicates are meaningless:**

| Row | account | security | quantity |
|---|---|---|---|
| 1 | ACC-00000-000 | SEC-00000 | 526 |
| 2 | ACC-00000-000 | SEC-00000 | 318 |

How much does the account hold? **526, 318, or 844?** Three programs, three answers, same data.

**LOOK:** `.sorted(Comparator.comparing(...))`

**EXAMPLE — measured before this line existed:**

```
saved to database:  SEC-00001, SEC-00000, SEC-00002
read back:          SEC-00000, SEC-00001, SEC-00002   ← database sorted it
equal?              false
```

Same positions, different order. **One real thing, one stored form** — the third time you meet that rule.

---

### FILE · `Fund.java` · EXISTING

```java
package dev.hedge.domain;

import java.util.Currency;
import java.util.List;
import java.util.Objects;

public record Fund(FundId id, Currency baseCurrency, List<Account> accounts) {

    public Fund {
        Objects.requireNonNull(id, "a fund must have an id");
        Objects.requireNonNull(baseCurrency, "a fund must have a base currency");
        accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts must not be null"));
    }

    public int positionCount() {
        return accounts.stream().mapToInt(account -> account.positions().size()).sum();
    }
}
```

**LOOK:** `List.copyOf(...)`

**CRITICAL CONCEPT — a record holding a mutable list is not immutable.**

**EXAMPLE:**

```java
List<Account> mutable = new ArrayList<>(List.of(accountA));
Fund fund = new Fund(id, USD, mutable);
mutable.add(accountB);          // without copyOf, fund now has 2 accounts
fund.accounts().size();         // with copyOf → still 1
```

---

### FILE · `BadMoney.java` · EXISTING — your commented-out experiment

```java
//package dev.hedge.domain;
//
//public record BadMoney(double amount, String currencyCode) {
//}
```

**Keep it.** It is your record of the architecture rule firing. Fully commented out, it compiles to nothing.

---

## LAYER 1 — TEST FILES

### FILE · `MoneyTest.java` · EXISTING

```java
package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("decimal arithmetic is exact, floating point is not")
    void floating_point_cannot_represent_money_exactly() {
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);

        assertThat(Money.of("0.10", "USD").plus(Money.of("0.20", "USD")))
                .isEqualTo(Money.of("0.30", "USD"));
    }

    @Test
    @DisplayName("an amount is stored at the number of decimals its currency uses")
    void the_scale_comes_from_the_currency() {
        assertThat(Money.of("100", "USD").amount().scale()).isEqualTo(2);
        assertThat(Money.of("100", "JPY").amount().scale()).isZero();
    }

    @Test
    @DisplayName("the same amount written differently is the same money")
    void amounts_are_normalised() {
        assertThat(Money.of("100", "USD")).isEqualTo(Money.of("100.00", "USD"));
    }

    @Test
    @DisplayName("an amount with more decimals than the currency allows is refused, never silently rounded")
    void extra_decimals_are_refused() {
        assertThatThrownBy(() -> Money.of("1.005", "USD"))
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> Money.of("100.5", "JPY"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("two different currencies cannot be added")
    void currencies_cannot_be_mixed() {
        assertThatThrownBy(() -> Money.of("100.00", "USD").plus(Money.of("100.00", "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot combine USD with EUR");
    }

    @Test
    @DisplayName("the same currency adds and subtracts normally")
    void same_currency_arithmetic_works() {
        assertThat(Money.of("100.00", "USD").plus(Money.of("25.50", "USD")))
                .isEqualTo(Money.of("125.50", "USD"));
        assertThat(Money.of("100.00", "USD").minus(Money.of("125.50", "USD")))
                .isEqualTo(Money.of("-25.50", "USD"));
    }

    @Test
    @DisplayName("multiplication requires the caller to choose how to round")
    void rounding_is_chosen_by_the_caller() {
        Money price = Money.of("10.00", "USD");

        assertThat(price.multipliedBy(new BigDecimal("1.0049"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("10.05", "USD"));
        assertThat(price.multipliedBy(new BigDecimal("1.0049"), RoundingMode.DOWN))
                .isEqualTo(Money.of("10.04", "USD"));
    }

    @Test
    @DisplayName("an unknown currency code is refused")
    void unknown_currency_is_refused() {
        assertThatThrownBy(() -> Money.of("1.00", "XXXX"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative amount is allowed, because an account can be overdrawn")
    void negative_amounts_are_allowed() {
        assertThat(Money.of("-5.00", "USD").isNegative()).isTrue();
        assertThat(Money.zero("USD").isZero()).isTrue();
    }
}
```

**LOOK:** the first test.

**CRITICAL CONCEPT — prove the problem, not just the solution.** The first line shows `0.1 + 0.2 != 0.3` in plain Java. The second shows our type getting it right. **The test documents why the design exists.**

---

### FILE · `FxRateTest.java` · EXISTING

```java
package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FxRateTest {

    @Test
    @DisplayName("a rate converts money in its base currency into its quote currency")
    void conversion_works_in_the_declared_direction() {
        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");

        assertThat(eurUsd.convert(Money.of("100.00", "EUR"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("110.00", "USD"));
    }

    @Test
    @DisplayName("converting in the wrong direction is refused, it does not silently return a wrong number")
    void the_wrong_direction_is_refused() {
        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");

        assertThatThrownBy(() -> eurUsd.convert(Money.of("100.00", "USD"), RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("converts EUR to USD, but was given USD");
    }

    @Test
    @DisplayName("the inverse rate converts the other way")
    void inverse_converts_the_other_way() {
        FxRate usdEur = FxRate.of("EUR", "USD", "1.25").inverse();

        assertThat(usdEur.base().getCurrencyCode()).isEqualTo("USD");
        assertThat(usdEur.quote().getCurrencyCode()).isEqualTo("EUR");
        assertThat(usdEur.convert(Money.of("125.00", "USD"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    @DisplayName("a zero or negative rate is refused")
    void a_rate_must_be_positive() {
        assertThatThrownBy(() -> FxRate.of("EUR", "USD", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> FxRate.of("EUR", "USD", "-1.10"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a rate between a currency and itself is refused")
    void base_and_quote_must_differ() {
        assertThatThrownBy(() -> FxRate.of("USD", "USD", "1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("converting into a zero decimal currency rounds to whole units")
    void conversion_uses_the_target_currency_decimals() {
        FxRate usdJpy = FxRate.of("USD", "JPY", "151.37");

        assertThat(usdJpy.convert(Money.of("10.00", "USD"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("1514", "JPY"));
    }
}
```

**EXAMPLE — the last test:** `10.00 USD × 151.37 = 1513.70`, and JPY has 0 decimals, so it becomes `1514 JPY`. **The target currency decides the rounding, not the source.**

---

### FILE · `QuantityTest.java` · EXISTING

```java
package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuantityTest {

    @Test
    @DisplayName("the same quantity written differently is the same quantity")
    void trailing_zeros_do_not_create_a_second_quantity() {
        assertThat(Quantity.of("100")).isEqualTo(Quantity.of("100.00"));
    }

    @Test
    @DisplayName("a negative quantity is a short position, not an error")
    void negative_means_short() {
        assertThat(Quantity.of("-500").isShort()).isTrue();
        assertThat(Quantity.of("500").isShort()).isFalse();
        assertThat(Quantity.of("0").isZero()).isTrue();
    }

    @Test
    @DisplayName("a quantity keeps its fractional part, because some instruments trade in fractions")
    void fractional_quantities_are_kept() {
        assertThat(Quantity.of("0.5").toString()).isEqualTo("0.5");
    }

    @Test
    @DisplayName("a missing quantity is refused")
    void null_is_refused() {
        assertThatThrownBy(() -> new Quantity(null)).isInstanceOf(NullPointerException.class);
    }
}
```

---

### FILE · `IdentifierTest.java` · EXISTING

```java
package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifierTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("every identifier rejects a blank value, using one shared rule")
    void all_identifiers_reject_blank(String value) {
        assertThatThrownBy(() -> AccountId.of(value)).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> SecurityId.of(value)).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> FundId.of(value)).hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("each identifier names what was wrong, so the message points at the right field")
    void the_message_names_the_identifier() {
        assertThatThrownBy(() -> AccountId.of("")).hasMessageContaining("an account id");
        assertThatThrownBy(() -> SecurityId.of("")).hasMessageContaining("a security id");
        assertThatThrownBy(() -> FundId.of("")).hasMessageContaining("a fund id");
    }

    @Test
    @DisplayName("identifiers with the same text are still different types")
    void identifiers_are_not_interchangeable() {
        assertThat(AccountId.of("X-1").value()).isEqualTo(SecurityId.of("X-1").value());
        assertThat((Object) AccountId.of("X-1")).isNotEqualTo(SecurityId.of("X-1"));
    }
}
```

---

### FILE · `FundIdTest.java` · EXISTING

```java
package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FundIdTest {

    @Test
    @DisplayName("a fund id keeps the value it was given")
    void a_valid_identifier_is_kept() {
        assertThat(FundId.of("FUND-00001").value()).isEqualTo("FUND-00001");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("a blank fund id is rejected at construction, so no later layer has to check for it")
    void a_blank_identifier_is_rejected(String value) {
        assertThatThrownBy(() -> FundId.of(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("surrounding whitespace is removed, so one fund cannot become two keys")
    void the_identifier_is_trimmed() {
        assertThat(FundId.of("  FUND-00001  ")).isEqualTo(FundId.of("FUND-00001"));
    }

    @Test
    @DisplayName("two fund ids with the same value are the same identity")
    void equality_is_by_value() {
        assertThat(FundId.of("FUND-00001"))
                .isEqualTo(FundId.of("FUND-00001"))
                .hasSameHashCodeAs(FundId.of("FUND-00001"))
                .isNotEqualTo(FundId.of("FUND-00002"));
    }
}
```

---

### FILE · `PositionTest.java` · EXISTING

```java
package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PositionTest {

    private static final AccountId ACCOUNT = AccountId.of("ACC-1");
    private static final SecurityId SECURITY = SecurityId.of("SEC-1");

    @Test
    @DisplayName("a position joins an account, a security and a quantity")
    void a_position_holds_its_three_parts() {
        Position position = new Position(ACCOUNT, SECURITY, Quantity.of("100"));

        assertThat(position.accountId()).isEqualTo(ACCOUNT);
        assertThat(position.securityId()).isEqualTo(SECURITY);
        assertThat(position.quantity()).isEqualTo(Quantity.of("100"));
    }

    @Test
    @DisplayName("a position without an account is refused")
    void every_part_is_required() {
        assertThatThrownBy(() -> new Position(null, SECURITY, Quantity.of("1")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must belong to an account");
        assertThatThrownBy(() -> new Position(ACCOUNT, null, Quantity.of("1")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Position(ACCOUNT, SECURITY, null))
                .isInstanceOf(NullPointerException.class);
    }
}
```

---

### FILE · `FundTest.java` · EXISTING

```java
package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");

    @Test
    @DisplayName("changing the caller's list afterwards does not change the account")
    void an_account_copies_its_positions() {
        List<Position> mutable = new ArrayList<>();
        mutable.add(new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("10")));

        Account account = new Account(ACCOUNT, mutable, List.of());
        mutable.add(new Position(ACCOUNT, SecurityId.of("S-2"), Quantity.of("20")));

        assertThat(account.positions()).hasSize(1);
    }

    @Test
    @DisplayName("the list inside an account cannot be changed either")
    void the_stored_list_is_read_only() {
        Account account = Account.empty(ACCOUNT);

        assertThatThrownBy(() -> account.positions().add(
                        new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("1"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a fund counts the positions held across all of its accounts")
    void a_fund_counts_its_positions() {
        Account first = new Account(ACCOUNT, List.of(
                new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("1")),
                new Position(ACCOUNT, SecurityId.of("S-2"), Quantity.of("2"))), List.of());
        AccountId second = AccountId.of("ACC-2");
        Account other = new Account(second, List.of(
                new Position(second, SecurityId.of("S-3"), Quantity.of("3"))), List.of());

        Fund fund = new Fund(FundId.of("F-1"), USD, List.of(first, other));

        assertThat(fund.positionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a fund must have a base currency, because its exposure is measured against one")
    void a_fund_requires_a_base_currency() {
        assertThatThrownBy(() -> new Fund(FundId.of("F-1"), null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("base currency");
    }

    @Test
    @DisplayName("one account cannot hold the same security twice, because a position is the whole holding")
    void a_security_cannot_appear_twice_in_one_account() {
        assertThatThrownBy(() -> new Account(
                        ACCOUNT,
                        List.of(
                                new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("10")),
                                new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("20"))),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("one account cannot hold two balances in the same currency")
    void a_currency_cannot_appear_twice_in_one_account() {
        assertThatThrownBy(() -> new Account(
                        ACCOUNT, List.of(), List.of(Money.of("1.00", "USD"), Money.of("2.00", "USD"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cash twice");
    }

    @Test
    @DisplayName("the same holdings in a different order are the same account")
    void positions_are_stored_in_one_fixed_order() {
        Position first = new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("10"));
        Position second = new Position(ACCOUNT, SecurityId.of("S-2"), Quantity.of("20"));

        Account oneWay = new Account(ACCOUNT, List.of(first, second), List.of());
        Account otherWay = new Account(ACCOUNT, List.of(second, first), List.of());

        assertThat(oneWay).isEqualTo(otherWay);
        assertThat(oneWay.positions()).containsExactly(first, second);
    }
}
```

---

### FILE · `DomainBoundaryTest.java` · EXISTING

```java
package dev.hedge.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.hedge.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainBoundaryTest {

    @ArchTest
    static final ArchRule the_domain_depends_only_on_the_jdk = noClasses()
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("java..", "javax..", "dev.hedge.domain..")
            .because("the domain carries the financial meaning of the system; if it can reach a framework,"
                    + " a web annotation or a database type, that meaning becomes impossible to test in"
                    + " isolation and impossible to reuse from another layer");

    @ArchTest
    static final ArchRule no_money_is_held_in_floating_point = fields()
            .should()
            .notHaveRawType(double.class)
            .andShould()
            .notHaveRawType(float.class)
            .andShould()
            .notHaveRawType(Double.class)
            .andShould()
            .notHaveRawType(Float.class)
            .because("floating point cannot represent decimal amounts exactly, so a financial value stored"
                    + " this way is wrong before any calculation has even happened");
}
```

**CRITICAL CONCEPT — architecture as a test, not a document.**

**LOOK:** `.because("...")` — that text is **printed inside the failure message**.

**EXAMPLE — measured when I removed `"java.."` from the allowed list:**

```
Architecture Violation [Priority: MEDIUM] - Rule 'no classes should depend on classes
that reside outside of packages ['javax..', 'dev.hedge.domain..'], because the domain
carries the financial meaning of the system; ...' was violated (11 times):
Class <dev.hedge.domain.FundId> extends class <java.lang.Record>
```

The person who breaks the rule reads **why it exists**, at the moment they break it.

**EXAMPLE — measured when I added a `double` field:**

```
no_money_is_held_in_floating_point FAILED
Field <dev.hedge.domain.BadMoney.amount> has raw type double
```

**It names the exact field.**

---

# LAYER 2 — THE DATA GENERATOR

**WHAT:** Build fake portfolios from one number.
**WHY:** A bug you cannot reproduce cannot be investigated.
**INSIDE:** The same seed drives the same random sequence.
**ARCHITECT:** A benchmark whose input cannot be rebuilt is not evidence.

**CONNECTS:**
```
WorkloadSpec(seed) → PortfolioGenerator → Layer 1 types → tests · database · benchmarks
```

---

### FILE · `modules/generator/build.gradle.kts` · EXISTING

```kotlin
plugins {
    id("hedge.java-conventions")
}

description = "Deterministic synthetic portfolio generator used for tests and benchmarks"

dependencies {
    api(project(":modules:domain"))
}
```

**CRITICAL CONCEPT — one-way dependencies.**

**EXAMPLE — measured:**

```
domain      →  No dependencies
generator   →  \--- project ':modules:domain'
```

You could delete the whole generator module and `domain` would still build.

---

### FILE · `WorkloadSpec.java` · EXISTING

```java
package dev.hedge.generator;

import java.util.List;
import java.util.Objects;

public record WorkloadSpec(
        long seed,
        int funds,
        int accountsPerFund,
        int positionsPerAccount,
        int securities,
        List<String> currencies) {

    public WorkloadSpec {
        currencies = List.copyOf(Objects.requireNonNull(currencies, "currencies must not be null"));
        requirePositive(funds, "funds");
        requirePositive(accountsPerFund, "accountsPerFund");
        requirePositive(positionsPerAccount, "positionsPerAccount");
        requirePositive(securities, "securities");
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("at least one currency is required");
        }
        if (positionsPerAccount > securities) {
            throw new IllegalArgumentException("an account cannot hold " + positionsPerAccount
                    + " distinct securities when only " + securities + " exist");
        }
    }

    public static WorkloadSpec tiny(long seed) {
        return new WorkloadSpec(seed, 1, 2, 3, 5, List.of("USD", "EUR"));
    }

    public static WorkloadSpec typical(long seed) {
        return new WorkloadSpec(seed, 20, 4, 25, 200, List.of("USD", "EUR", "GBP", "JPY"));
    }

    public int totalPositions() {
        return funds * accountsPerFund * positionsPerAccount;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero, was " + value);
        }
    }
}
```

**EXAMPLE — the two presets:**

| Preset | Funds | Accounts | Positions each | Total positions |
|---|---|---|---|---|
| `tiny(seed)` | 1 | 2 | 3 | **6** |
| `typical(seed)` | 20 | 4 | 25 | **2000** |

**LOOK:** `positionsPerAccount > securities`

**EXAMPLE — measured:**
```
new WorkloadSpec(1, 1, 1, 10, 5, List.of("USD"))
  →  an account cannot hold 10 distinct securities when only 5 exist
```

**ARCHITECT DECISION:** Refuse impossible inputs at the edge, with a message naming the actual numbers.

---

### FILE · `GeneratedPortfolio.java` · EXISTING

```java
package dev.hedge.generator;

import dev.hedge.domain.Fund;
import dev.hedge.domain.FxRate;
import dev.hedge.domain.Money;
import dev.hedge.domain.SecurityId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GeneratedPortfolio(List<Fund> funds, Map<SecurityId, Money> prices, List<FxRate> rates) {

    public GeneratedPortfolio {
        funds = List.copyOf(Objects.requireNonNull(funds, "funds must not be null"));
        prices = Map.copyOf(Objects.requireNonNull(prices, "prices must not be null"));
        rates = List.copyOf(Objects.requireNonNull(rates, "rates must not be null"));
    }

    public int positionCount() {
        return funds.stream().mapToInt(Fund::positionCount).sum();
    }
}
```

---

### FILE · `PortfolioGenerator.java` · EXISTING

```java
package dev.hedge.generator;

import dev.hedge.domain.Account;
import dev.hedge.domain.AccountId;
import dev.hedge.domain.Fund;
import dev.hedge.domain.FundId;
import dev.hedge.domain.FxRate;
import dev.hedge.domain.Money;
import dev.hedge.domain.Position;
import dev.hedge.domain.Quantity;
import dev.hedge.domain.SecurityId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class PortfolioGenerator {

    private static final long FUND_SEED_STRIDE = 1_000_003L;
    private static final long PRICE_SEED_OFFSET = 7_700_017L;
    private static final long RATE_SEED_OFFSET = 9_900_019L;
    private static final int RATE_SCALE = 6;

    public GeneratedPortfolio generate(WorkloadSpec spec) {
        List<SecurityId> securities = securities(spec);
        List<Fund> funds = new ArrayList<>(spec.funds());
        for (int index = 0; index < spec.funds(); index++) {
            funds.add(fund(spec, index, securities));
        }
        return new GeneratedPortfolio(funds, prices(spec, securities), rates(spec));
    }

    private static List<SecurityId> securities(WorkloadSpec spec) {
        List<SecurityId> securities = new ArrayList<>(spec.securities());
        for (int index = 0; index < spec.securities(); index++) {
            securities.add(SecurityId.of(String.format("SEC-%05d", index)));
        }
        return securities;
    }

    private static Fund fund(WorkloadSpec spec, int fundIndex, List<SecurityId> securities) {
        Random random = new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE);
        Currency baseCurrency = Currency.getInstance(spec.currencies().get(0));

        List<Account> accounts = new ArrayList<>(spec.accountsPerFund());
        for (int accountIndex = 0; accountIndex < spec.accountsPerFund(); accountIndex++) {
            AccountId accountId = AccountId.of(String.format("ACC-%05d-%03d", fundIndex, accountIndex));
            List<SecurityId> available = new ArrayList<>(securities);
            Collections.shuffle(available, random);
            List<Position> positions = new ArrayList<>(spec.positionsPerAccount());
            for (int i = 0; i < spec.positionsPerAccount(); i++) {
                positions.add(new Position(
                        accountId, available.get(i), Quantity.of(String.valueOf(1 + random.nextInt(1000)))));
            }
            Currency cashCurrency = Currency.getInstance(spec.currencies().get(random.nextInt(spec.currencies().size())));
            accounts.add(new Account(accountId, positions, List.of(amount(random, cashCurrency, 1_000, 100_000))));
        }
        return new Fund(FundId.of(String.format("FUND-%05d", fundIndex)), baseCurrency, accounts);
    }

    private static Map<SecurityId, Money> prices(WorkloadSpec spec, List<SecurityId> securities) {
        Random random = new Random(spec.seed() + PRICE_SEED_OFFSET);
        Currency priceCurrency = Currency.getInstance(spec.currencies().get(0));
        Map<SecurityId, Money> prices = new LinkedHashMap<>();
        for (SecurityId security : securities) {
            prices.put(security, amount(random, priceCurrency, 10, 500));
        }
        return prices;
    }

    private static List<FxRate> rates(WorkloadSpec spec) {
        Random random = new Random(spec.seed() + RATE_SEED_OFFSET);
        Currency base = Currency.getInstance(spec.currencies().get(0));
        List<FxRate> rates = new ArrayList<>();
        for (String code : spec.currencies()) {
            Currency currency = Currency.getInstance(code);
            if (currency.equals(base)) {
                continue;
            }
            BigDecimal rate = BigDecimal.valueOf(50_000 + random.nextInt(150_000), RATE_SCALE);
            rates.add(new FxRate(currency, base, rate));
        }
        return rates;
    }

    private static Money amount(Random random, Currency currency, int minimum, int maximum) {
        BigDecimal raw = BigDecimal.valueOf(minimum * 100L + random.nextInt((maximum - minimum) * 100), 2);
        return new Money(raw.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP), currency);
    }
}
```

**LOOK:** `new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE)`

**CRITICAL CONCEPT — give each part its own seed.**

**EXAMPLE — I built the obvious version (one shared `Random`) and measured both:**

| | One shared Random | One seed per fund |
|---|---|---|
| Same seed, generated twice | **false** | **true** |
| Fund 0 from 1-fund vs 10-fund spec | **false** | **true** |

**Look at the first row.** With a shared source, **the same seed gave different data every call**, because the generator keeps advancing.

**WHY per-fund seeding works:** fund 5 no longer depends on how many numbers funds 0–4 consumed. Change `accountsPerFund` and only that part changes.

**ARCHITECT DECISION:** It also makes parallel generation possible later — each fund needs nothing from the others.

**LOOK:** `raw.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP)`

**CONNECTS TO LAYER 1:** JPY has 0 decimals and `Money` **refuses** 2. Remove this line and the generator throws `ArithmeticException`. **A Layer 1 rule is enforced on Layer 2 code.**

**EXAMPLE — measured output:**

```
PROBE 50000 positions in 21 ms
```

Test data is effectively free, so nobody avoids running tests.

---

## LAYER 2 — TEST FILES

### FILE · `PortfolioGeneratorTest.java` · EXISTING

```java
package dev.hedge.generator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.Account;
import dev.hedge.domain.Fund;
import dev.hedge.domain.Position;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortfolioGeneratorTest {

    private final PortfolioGenerator generator = new PortfolioGenerator();

    @Test
    @DisplayName("the same seed produces the same portfolio, so a failure can be reproduced exactly")
    void generation_is_deterministic() {
        WorkloadSpec spec = WorkloadSpec.tiny(42);

        assertThat(generator.generate(spec)).isEqualTo(generator.generate(spec));
    }

    @Test
    @DisplayName("a different seed produces a different portfolio")
    void a_different_seed_changes_the_data() {
        assertThat(generator.generate(WorkloadSpec.tiny(42)))
                .isNotEqualTo(generator.generate(WorkloadSpec.tiny(43)));
    }

    @Test
    @DisplayName("one fund is identical whether one fund or many are generated")
    void each_fund_is_generated_independently() {
        WorkloadSpec one = new WorkloadSpec(42, 1, 2, 3, 5, List.of("USD", "EUR"));
        WorkloadSpec many = new WorkloadSpec(42, 10, 2, 3, 5, List.of("USD", "EUR"));

        Fund fromSmall = generator.generate(one).funds().get(0);
        Fund fromLarge = generator.generate(many).funds().get(0);

        assertThat(fromSmall).isEqualTo(fromLarge);
    }

    @Test
    @DisplayName("every position refers to a security that has a price")
    void every_position_has_a_price() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(7));

        for (Fund fund : portfolio.funds()) {
            for (Account account : fund.accounts()) {
                for (Position position : account.positions()) {
                    assertThat(portfolio.prices()).containsKey(position.securityId());
                }
            }
        }
    }

    @Test
    @DisplayName("every position belongs to the account that holds it")
    void positions_belong_to_their_account() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(7));

        for (Fund fund : portfolio.funds()) {
            for (Account account : fund.accounts()) {
                assertThat(account.positions())
                        .allSatisfy(position -> assertThat(position.accountId()).isEqualTo(account.id()));
            }
        }
    }

    @Test
    @DisplayName("the generated size matches what the specification asked for")
    void the_size_matches_the_specification() {
        WorkloadSpec spec = WorkloadSpec.typical(1);

        assertThat(generator.generate(spec).positionCount()).isEqualTo(spec.totalPositions());
        assertThat(spec.totalPositions()).isEqualTo(20 * 4 * 25);
    }

    @Test
    @DisplayName("a currency with no decimal part is generated correctly")
    void zero_decimal_currencies_are_supported() {
        WorkloadSpec spec = new WorkloadSpec(5, 2, 2, 2, 4, List.of("JPY", "USD"));

        GeneratedPortfolio portfolio = generator.generate(spec);

        assertThat(portfolio.prices().values())
                .allSatisfy(price -> assertThat(price.amount().scale()).isZero());
    }

    @Test
    @DisplayName("one rate is produced for every currency other than the base")
    void a_rate_exists_for_each_non_base_currency() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(1));

        assertThat(portfolio.rates()).hasSize(3);
        assertThat(portfolio.rates())
                .allSatisfy(rate -> assertThat(rate.quote().getCurrencyCode()).isEqualTo("USD"));
    }

    @Test
    @DisplayName("an account never holds the same security twice")
    void securities_are_distinct_within_an_account() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(99));

        for (Fund fund : portfolio.funds()) {
            for (Account account : fund.accounts()) {
                assertThat(account.positions())
                        .extracting(Position::securityId)
                        .doesNotHaveDuplicates();
            }
        }
    }
}
```

**LOOK:** `every_position_has_a_price` and `positions_belong_to_their_account`

**CRITICAL CONCEPT — referential integrity in test data.** Generated data must be *valid*, not just present. A position pointing at a security with no price would make every later test meaningless.

---

### FILE · `WorkloadSpecTest.java` · EXISTING

```java
package dev.hedge.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkloadSpecTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("a size of zero or less is refused, so an empty workload cannot be measured by mistake")
    void sizes_must_be_positive(int bad) {
        assertThatThrownBy(() -> new WorkloadSpec(1, bad, 1, 1, 1, List.of("USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("funds must be greater than zero");
    }

    @Test
    @DisplayName("a workload with no currency is refused")
    void at_least_one_currency_is_required() {
        assertThatThrownBy(() -> new WorkloadSpec(1, 1, 1, 1, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one currency");
    }

    @Test
    @DisplayName("changing the caller's list afterwards does not change the specification")
    void the_specification_copies_its_currencies() {
        List<String> mutable = new ArrayList<>(List.of("USD", "EUR"));
        WorkloadSpec spec = new WorkloadSpec(1, 1, 1, 1, 1, mutable);

        mutable.add("GBP");

        assertThat(spec.currencies()).containsExactly("USD", "EUR");
    }

    @Test
    @DisplayName("the expected position count can be calculated before generating anything")
    void the_size_is_known_in_advance() {
        assertThat(WorkloadSpec.tiny(1).totalPositions()).isEqualTo(6);
        assertThat(WorkloadSpec.typical(1).totalPositions()).isEqualTo(2000);
    }

    @Test
    @DisplayName("asking for more positions per account than there are securities is refused")
    void a_workload_cannot_ask_for_impossible_positions() {
        assertThatThrownBy(() -> new WorkloadSpec(1, 1, 1, 10, 5, List.of("USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot hold 10 distinct securities when only 5 exist");
    }
}
```

---

### FILE · `GeneratorBoundaryTest.java` · EXISTING

```java
package dev.hedge.generator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.hedge.generator", importOptions = ImportOption.DoNotIncludeTests.class)
class GeneratorBoundaryTest {

    @ArchTest
    static final ArchRule the_generator_uses_only_the_domain_and_the_jdk = noClasses()
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("java..", "javax..", "dev.hedge.domain..", "dev.hedge.generator..")
            .because("the generator produces test data; if it pulls in a framework, every benchmark and"
                    + " test that uses it inherits that dependency");
}
```

---

### FILE · `GenProbe.java` · EXISTING — your experiment file

Keep it as your record. Comment the body out when not in use, as you did with `Probe.java`.

```java
package dev.hedge.generator;

import dev.hedge.domain.Fund;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenProbe {
    @Test
    void run() {
        PortfolioGenerator g = new PortfolioGenerator();
        System.out.println("PROBE hello");
    }
}
```

---

## Layers 0–2 — what is already measured

| Check | Measured result |
|---|---|
| Domain production dependencies | `No dependencies` |
| Generator dependencies | `project ':modules:domain'` only |
| Locked libraries, domain / generator | 19 / 19 — **all test-only** |
| Same seed twice | `true` |
| Different seed | `false` |
| Fund 0, 1-fund vs 10-fund spec | `true` |
| Shared Random instead | `false` — determinism destroyed |
| 50,000 positions generated | 21 ms |
| FX wrong direction | `110.00 USD` vs correct `90.91 EUR` |
| `inverse().inverse()` equals original | `false` |

---

**END OF PART 1.**

Say **CONTINUE** and I will deliver **Part 2**: PART B for Layers 3, 4 and 5 — the PostgreSQL schema and repository, the HTTP API, the metrics configuration, and every install and configuration step.
