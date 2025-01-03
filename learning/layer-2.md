Every output below I measured. Layer 2 adds your **second module** — which triggers the decision Layer 0 deliberately postponed.

---

# PART 1 — BUILD

## Layer goal

Layer 2 builds a generator that creates fake portfolios for testing.

The same number always produces the same data.

A bug found today can be reproduced exactly next year.

## Architecture

```
  modules/generator          NEW MODULE
  ┌──────────────────────┐
  │  WorkloadSpec        │  the recipe (seed, sizes)
  │        ↓             │
  │  PortfolioGenerator  │  builds the data
  │        ↓             │
  │  GeneratedPortfolio  │  the result
  └──────────┬───────────┘
             │ depends on
             ▼
  modules/domain
  ┌──────────────────────┐
  │ Fund, Account   NEW  │
  │ Position, Money,     │
  │ FxRate, Quantity     │
  └──────────────────────┘

  ONE WAY ONLY.
  domain does not know the generator exists.
```

## Folder structure

```
Hedge_Trading/
├── settings.gradle.kts                 UPDATE
└── modules/
    ├── domain/
    │   └── src/
    │       ├── main/java/dev/hedge/domain/
    │       │   ├── Account.java        NEW
    │       │   └── Fund.java           NEW
    │       └── test/java/dev/hedge/domain/
    │           └── FundTest.java       NEW
    └── generator/                      NEW MODULE
        ├── build.gradle.kts            NEW
        └── src/
            ├── main/java/dev/hedge/generator/
            │   ├── WorkloadSpec.java        NEW
            │   ├── GeneratedPortfolio.java  NEW
            │   └── PortfolioGenerator.java  NEW
            └── test/java/dev/hedge/generator/
                ├── PortfolioGeneratorTest.java  NEW
                ├── WorkloadSpecTest.java        NEW
                └── GeneratorBoundaryTest.java   NEW
```

---

## 1 · `settings.gradle.kts` — UPDATE

```kotlin
rootProject.name = "hedge-trading"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(":modules:domain")
include(":modules:generator")
```

## 2 · `modules/domain/src/main/java/dev/hedge/domain/Account.java` — NEW

```java
package dev.hedge.domain;

import java.util.List;
import java.util.Objects;

public record Account(AccountId id, List<Position> positions, List<Money> cashBalances) {

    public Account {
        Objects.requireNonNull(id, "an account must have an id");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
        cashBalances = List.copyOf(Objects.requireNonNull(cashBalances, "cash balances must not be null"));
    }

    public static Account empty(AccountId id) {
        return new Account(id, List.of(), List.of());
    }
}
```

## 3 · `modules/domain/src/main/java/dev/hedge/domain/Fund.java` — NEW

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

## 4 · `modules/generator/build.gradle.kts` — NEW

```kotlin
plugins {
    id("java-library")
}

description = "Deterministic synthetic portfolio generator used for tests and benchmarks"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = libs.versions.java.get().toInt()
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

dependencies {
    api(project(":modules:domain"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencyLocking {
    lockAllConfigurations()
}
```

**Notice:** this file is almost identical to `modules/domain/build.gradle.kts`. That duplication is deliberate. Exercise 6 deals with it.

## 5 · `modules/generator/src/main/java/dev/hedge/generator/WorkloadSpec.java` — NEW

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

## 6 · `modules/generator/src/main/java/dev/hedge/generator/GeneratedPortfolio.java` — NEW

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

## 7 · `modules/generator/src/main/java/dev/hedge/generator/PortfolioGenerator.java` — NEW

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
            List<Position> positions = new ArrayList<>(spec.positionsPerAccount());
            for (int i = 0; i < spec.positionsPerAccount(); i++) {
                SecurityId security = securities.get(random.nextInt(securities.size()));
                positions.add(new Position(accountId, security, Quantity.of(String.valueOf(1 + random.nextInt(1000)))));
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

## 8 · `modules/generator/src/test/java/dev/hedge/generator/PortfolioGeneratorTest.java` — NEW

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
}
```

## 9 · `modules/generator/src/test/java/dev/hedge/generator/WorkloadSpecTest.java` — NEW

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
}
```

## 10 · `modules/generator/src/test/java/dev/hedge/generator/GeneratorBoundaryTest.java` — NEW

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

## 11 · `modules/domain/src/test/java/dev/hedge/domain/FundTest.java` — NEW

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
}
```

---

## Setup — one command

The new module needs its own lockfile:

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew test --write-locks
```

**Expect:** `BUILD SUCCESSFUL` and **56 tests** (including your `Probe`).

---

# PART 2 — LEARN BY DOING

## Setup — a probe for the new module

**📁 CREATE:** `modules/generator/src/test/java/dev/hedge/generator/GenProbe.java`

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

**▶️ RUN (this is the command for every exercise below):**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:generator:test --tests "*GenProbe*" -i 2>&1 | grep "PROBE"
```

---

## Exercise 1 — The same number gives the same data

**🎯 LEARN:** A result you cannot reproduce is not evidence.

**📁 OPEN:** `PortfolioGenerator.java`

**👀 FIND:**

```java
Random random = new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE);
```

**✏️ DO:** Put this in `GenProbe.run()`:

```java
        System.out.println("PROBE same seed   : "
                + g.generate(WorkloadSpec.tiny(42)).equals(g.generate(WorkloadSpec.tiny(42))));
        System.out.println("PROBE other seed  : "
                + g.generate(WorkloadSpec.tiny(42)).equals(g.generate(WorkloadSpec.tiny(43))));
```

**🔍 SEE:**

```
    PROBE same seed   : true
    PROBE other seed  : false
```

**🧠 WHY:** Seed 42 always builds the same portfolio. If a test fails on seed 42, you can get that exact data back tomorrow.

**↩️ RESET:** Nothing changed.

---

## Exercise 2 — Each fund is built on its own

**🎯 LEARN:** Changing one part of a workload should not change the others.

**📁 OPEN:** `PortfolioGenerator.java`

**👀 FIND:**

```java
private static final long FUND_SEED_STRIDE = 1_000_003L;
```

Each fund gets its own number, so each fund is built separately.

**✏️ DO:** Put this in `GenProbe.run()`:

```java
        WorkloadSpec one = new WorkloadSpec(42, 1, 2, 3, 5, List.of("USD", "EUR"));
        WorkloadSpec many = new WorkloadSpec(42, 10, 2, 3, 5, List.of("USD", "EUR"));
        Fund a = g.generate(one).funds().get(0);
        Fund b = g.generate(many).funds().get(0);
        System.out.println("PROBE fund0 same? : " + a.equals(b));
```

**🔍 SEE:**

```
    PROBE fund0 same? : true
```

**🧠 WHY:** Fund 0 is the same whether you build 1 fund or 10. You can test with 1 fund and know it matches the big run.

**↩️ RESET:** Nothing changed.

---

## Exercise 3 — Break the seeding (the important one)

**🎯 LEARN:** One shared random number source destroys everything.

**📁 OPEN:** `PortfolioGenerator.java`

**✏️ DO:** Two changes.

Add this line just after `public final class PortfolioGenerator {`:

```java
    private static final Random SHARED = new Random(42);
```

Then replace this line inside `fund(...)`:

```java
        Random random = new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE);
```

with:

```java
        Random random = SHARED;
```

Keep the same `GenProbe` code from Exercises 1 and 2 together.

**🔍 SEE:**

```
    PROBE same seed   : false
    PROBE other seed  : false
    PROBE fund0 same? : false
```

**🧠 WHY:** Look at the first line. **The same seed now gives different data every time you call it.** One shared source keeps moving forward, so nothing can ever be repeated.

**↩️ RESET:** Delete the `SHARED` line, put `new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE)` back.

---

## Exercise 4 — A currency with no decimals

**🎯 LEARN:** A rule made in Layer 1 is enforced in Layer 2.

**📁 OPEN:** `PortfolioGenerator.java`

**👀 FIND:**

```java
return new Money(raw.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP), currency);
```

**✏️ DO:** Delete `.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP)` so the line becomes:

```java
        return new Money(raw, currency);
```

Put this in `GenProbe.run()`:

```java
        WorkloadSpec jpy = new WorkloadSpec(5, 1, 1, 1, 2, List.of("JPY", "USD"));
        System.out.println("PROBE " + g.generate(jpy).prices());
```

**🔍 SEE:** An `ArithmeticException` — the test fails.

**🧠 WHY:** JPY has 0 decimals. `Money` refuses an amount with 2 decimals rather than rounding it quietly. The generator must ask the currency how many decimals to use.

**↩️ RESET:** Put `.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP)` back.

---

## Exercise 5 — The one-way dependency

**🎯 LEARN:** The direction of a dependency is a design decision.

**▶️ RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:dependencies --configuration runtimeClasspath
```

**🔍 SEE:**

```
No dependencies
```

**▶️ THEN RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:generator:dependencies --configuration runtimeClasspath
```

**🔍 SEE:**

```
runtimeClasspath - Runtime classpath of source set 'main'.
\--- project ':modules:domain'
```

**🧠 WHY:** The generator knows about the domain. The domain does not know the generator exists. You could delete the whole generator module and the domain would still build.

**↩️ RESET:** Nothing changed.

---

## Exercise 6 — The duplication you were warned about

**🎯 LEARN:** When to remove duplication, and when to wait.

**📁 OPEN:** Both build files side by side:

```
modules/domain/build.gradle.kts
modules/generator/build.gradle.kts
```

**✏️ DO:** Compare them. Count the identical lines.

**🔍 SEE:** About 20 lines are the same — `java { toolchain ... }`, the compiler settings, the 5 test dependencies, `tasks.test { ... }`, `dependencyLocking { ... }`.

**🧠 WHY:** In Layer 0 I said: *"When a second module arrives, those settings will be duplicated. At that point we extract them."*

The second module has arrived. **We are still not extracting them.**

| Option | Cost |
|---|---|
| Extract now (2 modules) | A new build-logic project, extra moving parts, to remove 20 lines |
| Wait for the 3rd module | 20 more duplicated lines |

**Two copies is a coincidence. Three is a pattern.** Extracting after two often produces a shared rule that turns out to fit only those two. We extract in Layer 3, when the third module appears.

**↩️ RESET:** Nothing changed.

---

## Exercise 7 — Measure the generator

**🎯 LEARN:** Know the cost of your test tools.

**✏️ DO:** Put this in `GenProbe.run()`:

```java
        WorkloadSpec big = new WorkloadSpec(1, 200, 5, 50, 500, List.of("USD", "EUR", "GBP", "JPY"));
        long start = System.nanoTime();
        GeneratedPortfolio p = g.generate(big);
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("PROBE " + p.positionCount() + " positions in " + ms + " ms");
```

**🔍 SEE (my machine):**

```
    PROBE 50000 positions in 21 ms
```

Your number will differ. That is fine — record yours.

**🧠 WHY:** If generating data took 10 seconds, every test would be slow and you would stop running them. 50,000 positions in about 20 ms means test data is effectively free.

**↩️ RESET:** Nothing changed.

---

# PART 3 — RUN ALL TESTS

**Folder for every command:** `~/Desktop/Hedge_Trading`

| Case | Run | Expected | Lesson |
|---|---|---|---|
| All pass | `./gradlew test` | `BUILD SUCCESSFUL` · 56 tests | Baseline |
| Domain untouched | `./gradlew :modules:domain:dependencies --configuration runtimeClasspath` | `No dependencies` | A new module did not pollute the core |
| Generator depends on domain only | `./gradlew :modules:generator:dependencies --configuration runtimeClasspath` | `project ':modules:domain'` | One-way dependency |
| Both modules exist | `./gradlew projects` | `domain` and `generator` | `settings.gradle.kts` defines the system |
| Same seed | Exercise 1 | `true` | Reproducible |
| Fund independence | Exercise 2 | `true` | Parts do not affect each other |
| Shared random | Exercise 3 | `same seed: false` | Determinism destroyed |
| Zero-decimal currency | Exercise 4 | `ArithmeticException` | Layer 1's rule holds in Layer 2 |
| Speed | Exercise 7 | ~20 ms for 50,000 | Test data is cheap |

### Cases we cannot run yet

| Case | Why not | When |
|---|---|---|
| Save a portfolio | No database | Layer 3 |
| Serve it over HTTP | No API | Layer 4 |
| Measure memory properly | No profiling set up | Layer 8 |

---

# PART 4 — COMPLETE THE LAYER

## Final exercise — generate and inspect

**✏️ DO:** Put this in `GenProbe.run()`:

```java
        GeneratedPortfolio portfolio = g.generate(WorkloadSpec.typical(2024));

        System.out.println("PROBE funds     : " + portfolio.funds().size());
        System.out.println("PROBE positions : " + portfolio.positionCount());
        System.out.println("PROBE securities: " + portfolio.prices().size());
        System.out.println("PROBE rates     : " + portfolio.rates());

        Fund first = portfolio.funds().get(0);
        System.out.println("PROBE fund      : " + first.id() + " base=" + first.baseCurrency());
        System.out.println("PROBE accounts  : " + first.accounts().size());
        System.out.println("PROBE 1st pos   : " + first.accounts().get(0).positions().get(0));
```

**▶️ RUN:** the usual command.

**🔍 SEE:** `funds : 20`, `positions : 2000`, `securities: 200`, `rates` with 3 entries, and one real position printed.

## What happened inside

```
1. WorkloadSpec.typical(2024)
        ↓  20 funds × 4 accounts × 25 positions
2. 200 SecurityId values created
        ↓
3. For each fund: its own Random from seed + index
        ↓  positions point at real securities
4. Prices: one Money per security, at the currency's decimals
        ↓
5. Rates: one per currency, all converting into USD
        ↓
6. GeneratedPortfolio — every list copied, nothing can change
```

**YOU SAW:** 2000 positions built from one number.

**INSIDE:** Every value went through Layer 1's types, so every one of them is already valid.

**ARCHITECT LESSON:** The generator wrote no validation code. The types it used refused bad values on their own.

**↩️ RESET:** Keep `GenProbe.java` if you want it as a record, as you did with `Probe.java`. Just comment the body out.

---

## Layer 2 checklist

| | Item |
|---|---|
| ☐ | `settings.gradle.kts` updated |
| ☐ | `Account.java` and `Fund.java` added to domain |
| ☐ | `modules/generator` created with 6 files |
| ☐ | `./gradlew test --write-locks` run once |
| ☐ | 56 tests pass |
| ☐ | Ex 1 — `true` then `false` |
| ☐ | Ex 2 — `fund0 same? true` |
| ☐ | **Ex 3 — `same seed: false` after breaking the seeding** |
| ☐ | Ex 4 — `ArithmeticException` for JPY |
| ☐ | Ex 5 — `No dependencies` and `project ':modules:domain'` |
| ☐ | Ex 6 — compared both build files |
| ☐ | Ex 7 — recorded your own timing |
| ☐ | Final exercise — 20 funds, 2000 positions |
| ☐ | All temporary changes reset, tests green |

## The five lessons

1. Test data must be reproducible from one number, or a failure cannot be investigated.
2. Give each part its own seed, so changing one part does not change the others.
3. Dependencies point one way — the generator knows the domain, never the reverse.
4. Two copies of something is a coincidence; wait for the third before extracting it.
5. Code built from strict types needs no validation of its own.

---

**⏸️ STOP.** Exercise 3 is the one that matters most — seeing `same seed: false` is the whole point of this layer.

**Before Layer 3:** it needs PostgreSQL, which needs Docker, which you removed. Set up the GitHub repo and Codespace while you work through these exercises. Say **LAYER 3** when you are ready and I will start with the Codespace setup.
