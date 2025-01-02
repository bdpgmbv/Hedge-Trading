Let me check what you've already created, so I don't repeat code you have:

All 15 files exist. Let me verify your baseline is green before giving you exercises:

Baseline confirmed: **37 tests, all green.** Let me verify the one command the exercises depend on:

Every output below I measured in a copy of your project. Nothing is invented.

---

# PART 1 — BUILD

## Layer goal

Layer 1 adds the types the system is made of: money, exchange rates, quantities, positions, identifiers.

Each type refuses to exist in a wrong state.

Layer 1 only holds data. It does not calculate yet.

## Architecture

```
            Identifiers.java
          (one blank check)
                 │
      ┌──────────┼──────────┐
      ▼          ▼          ▼
   FundId   AccountId   SecurityId
                 │          │
                 └────┬─────┘
                      ▼
                  Position ◄── Quantity

      Money ◄──────── FxRate
   (amount +        converts Money
    currency)       ONE direction only
```

## Your build is already correct

```
✅ 15 files created
✅ 37 tests passing
✅ 0 new libraries
```

**No code to paste.** Everything you need already exists.

---

# PART 2 — LEARN BY DOING

## Setup — create one Probe file

You will edit this file for most exercises.

**📁 CREATE:** `modules/domain/src/test/java/dev/hedge/domain/Probe.java`

```java
package dev.hedge.domain;

import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class Probe {

    @Test
    void run() {
        System.out.println("PROBE hello");
    }
}
```

**▶️ RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:test --tests "*Probe*" -i 2>&1 | grep "PROBE"
```

**🔍 SEE:**

```
    PROBE hello
```

If you see that, every exercise below will work.

---

## Exercise 1 — The currency decides the decimals

**🎯 LEARN:** How many decimals an amount has is data, not a fixed number.

**📁 OPEN:** `modules/domain/src/main/java/dev/hedge/domain/Money.java`

**👀 FIND:**

```java
amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
```

**✏️ DO:** Replace the body of `run()` in `Probe.java`:

```java
        System.out.println("PROBE " + Money.of("100", "USD"));
        System.out.println("PROBE " + Money.of("100", "JPY"));
```

**▶️ RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:test --tests "*Probe*" -i 2>&1 | grep "PROBE"
```

**🔍 SEE:**

```
    PROBE 100.00 USD
    PROBE 100 JPY
```

**🧠 WHY:** USD has 2 decimals. JPY has 0. The code never says "2" — it asks the currency. A fixed 2 would be wrong for JPY.

**↩️ RESET:** Nothing changed.

---

## Exercise 2 — Add two currencies together

**🎯 LEARN:** A number and its unit must stay joined.

**📁 OPEN:** `Money.java`

**👀 FIND:**

```java
public Money plus(Money other) {
    requireSameCurrency(other);
```

**✏️ DO:** Delete the line `requireSameCurrency(other);` **inside `plus` only**. Then set `Probe.run()` to:

```java
        System.out.println("PROBE " + Money.of("100.00", "USD").plus(Money.of("50.00", "EUR")));
```

**▶️ RUN:** same command as above

**🔍 SEE:**

```
    PROBE 150.00 USD
```

**🧠 WHY:** 100 USD plus 50 EUR became 150 USD. That is wrong, and nothing crashed. The guard you deleted is the only thing that catches it.

**↩️ RESET:** Put `requireSameCurrency(other);` back as the first line of `plus`.

---

## Exercise 3 — Silent rounding

**🎯 LEARN:** Refusing is safer than guessing.

**📁 OPEN:** `Money.java`

**👀 FIND:**

```java
RoundingMode.UNNECESSARY
```

**✏️ DO:** Change it to `RoundingMode.HALF_UP`. Set `Probe.run()` to:

```java
        System.out.println("PROBE " + Money.of("1.005", "USD"));
```

**▶️ RUN:** same command

**🔍 SEE:**

```
    PROBE 1.01 USD
```

**🧠 WHY:** You asked for 1.005. You got 1.01. The half cent disappeared and nobody was told. `UNNECESSARY` throws an error instead, so the caller must decide.

**↩️ RESET:** Change `HALF_UP` back to `UNNECESSARY`.

---

## Exercise 4 — The wrong direction

**🎯 LEARN:** The most expensive bug in currency software produces a normal-looking number.

**📁 OPEN:** `modules/domain/src/main/java/dev/hedge/domain/FxRate.java`

**👀 FIND:**

```java
if (!money.currency().equals(base)) {
    throw new IllegalArgumentException("this rate converts " + base.getCurrencyCode()
            + " to " + quote.getCurrencyCode()
            + ", but was given " + money.currency().getCurrencyCode());
}
```

**✏️ DO:** Delete those 5 lines. Set `Probe.run()` to:

```java
        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");
        System.out.println("PROBE wrong way: " + eurUsd.convert(Money.of("100.00", "USD"), RoundingMode.HALF_UP));
        System.out.println("PROBE right way: " + eurUsd.inverse().convert(Money.of("100.00", "USD"), RoundingMode.HALF_UP));
```

**▶️ RUN:** same command

**🔍 SEE:**

```
    PROBE wrong way: 110.00 USD
    PROBE right way: 90.91 EUR
```

**🧠 WHY:** The wrong answer is 21% too high and has the wrong currency. It does not crash. It would print on a report and look fine. **This is why the check exists.**

**↩️ RESET:** Put the 5 lines back.

---

## Exercise 5 — Who chooses the rounding

**🎯 LEARN:** No hidden default.

**📁 OPEN:** `Money.java`

**👀 FIND:**

```java
public Money multipliedBy(BigDecimal factor, RoundingMode rounding) {
```

**✏️ DO:** Set `Probe.run()` to:

```java
        Money price = Money.of("10.00", "USD");
        System.out.println("PROBE HALF_UP: " + price.multipliedBy(new java.math.BigDecimal("1.0049"), RoundingMode.HALF_UP));
        System.out.println("PROBE DOWN   : " + price.multipliedBy(new java.math.BigDecimal("1.0049"), RoundingMode.DOWN));
```

**▶️ RUN:** same command

**🔍 SEE:**

```
    PROBE HALF_UP: 10.05 USD
    PROBE DOWN   : 10.04 USD
```

**🧠 WHY:** Same inputs, two different answers. The method has no default, so the caller must choose. A default would silently apply one person's choice everywhere.

**↩️ RESET:** Nothing changed.

---

## Exercise 6 — Wrong identifier type

**🎯 LEARN:** The compiler is a test that runs on every build.

**📁 CREATE:** `modules/domain/src/test/java/dev/hedge/domain/TypeProbe.java`

```java
package dev.hedge.domain;

class TypeProbe {
    void wrong() {
        Position p = new Position(FundId.of("F-1"), SecurityId.of("S-1"), Quantity.of("1"));
    }
}
```

**▶️ RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:compileTestJava
```

**🔍 SEE:**

```
error: incompatible types: FundId cannot be converted to AccountId
```

**🧠 WHY:** `FundId` and `AccountId` hold the same kind of text. They are still different types. The mistake is stopped before the code can run.

**↩️ RESET:** Delete `TypeProbe.java`.

---

## Exercise 7 — Money as a floating point number

**🎯 LEARN:** A rule that nothing checks is not a rule.

**📁 CREATE:** `modules/domain/src/main/java/dev/hedge/domain/BadMoney.java`

```java
package dev.hedge.domain;

public record BadMoney(double amount, String currencyCode) {
}
```

**▶️ RUN:**

```bash
cd ~/Desktop/Hedge_Trading && ./gradlew :modules:domain:test
```

**🔍 SEE:**

```
DomainBoundaryTest > no_money_is_held_in_floating_point FAILED
Field <dev.hedge.domain.BadMoney.amount> has raw type double
```

**🧠 WHY:** "Never use double for money" is usually written in a document nobody reads. Here the build fails and names the exact field.

**↩️ RESET:** Delete `BadMoney.java`.

---

## Exercise 8 — Inverting a rate twice

**🎯 LEARN:** Calculated data is not the same as original data.

**📁 OPEN:** `FxRate.java`

**👀 FIND:**

```java
private static final int INVERSE_SCALE = 10;
```

**✏️ DO:** Set `Probe.run()` to:

```java
        FxRate r = FxRate.of("EUR", "USD", "1.10");
        System.out.println("PROBE original : " + r.rate().toPlainString());
        System.out.println("PROBE inverted twice: " + r.inverse().inverse().rate().toPlainString());
        System.out.println("PROBE same?    : " + r.equals(r.inverse().inverse()));
```

**▶️ RUN:** same command

**🔍 SEE:**

```
    PROBE original : 1.10
    PROBE inverted twice: 1.1000000000
    PROBE same?    : false
```

**🧠 WHY:** Dividing 1 by 1.10 never ends, so 10 decimals were kept. Going back gives a different stored value. **Always store the rate you were given, never one you calculated.**

**↩️ RESET:** Nothing changed.

---

# PART 3 — RUN ALL TESTS

**Folder for every command:** `~/Desktop/Hedge_Trading`

| Case | Run | Expected | Lesson |
|---|---|---|---|
| All pass | `./gradlew :modules:domain:test` | `BUILD SUCCESSFUL` · 37 tests | Baseline |
| No new libraries | `./gradlew :modules:domain:dependencies --configuration runtimeClasspath` | `No dependencies` | 7 new types, 0 libraries |
| Mixed currencies | Exercise 2 | `150.00 USD` | Wrong, and silent |
| Silent rounding | Exercise 3 | `1.01 USD` | Precision lost quietly |
| Wrong FX direction | Exercise 4 | `110.00` vs `90.91` | 21% error, looks normal |
| Rounding choice | Exercise 5 | `10.05` vs `10.04` | Caller decides |
| Wrong id type | Exercise 6 | `incompatible types` | Stopped at compile |
| Double for money | Exercise 7 | Rule names the field | Structure is enforced |
| Rate inverted twice | Exercise 8 | `false` | Calculated ≠ original |

### Cases we cannot run yet

| Case | Why not | When |
|---|---|---|
| Missing price data | No `Price` type yet | Layer 6 |
| Unauthorized access | No API yet | Layer 4, secured in Layer 46 |
| Component failure | No database yet | Layer 3 |
| Performance | Nothing big enough to measure | Layer 8 |

---

# PART 4 — COMPLETE THE LAYER

## Final exercise — a real portfolio

**✏️ DO:** Set `Probe.run()` to this:

```java
        AccountId account = AccountId.of("ACC-1");
        Position apple = new Position(account, SecurityId.of("AAPL"), Quantity.of("100"));
        Money pricePerShare = Money.of("150.00", "USD");

        Money valueUsd = pricePerShare.multipliedBy(apple.quantity().value(), RoundingMode.HALF_UP);
        System.out.println("PROBE 1 value in USD : " + valueUsd);

        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");
        Money valueEur = eurUsd.inverse().convert(valueUsd, RoundingMode.HALF_UP);
        System.out.println("PROBE 2 value in EUR : " + valueEur);

        Money cash = Money.of("2500.00", "USD");
        System.out.println("PROBE 3 total USD    : " + valueUsd.plus(cash));

        try {
            valueUsd.plus(valueEur);
        } catch (IllegalArgumentException e) {
            System.out.println("PROBE 4 refused      : " + e.getMessage());
        }
```

**▶️ RUN:** same command

**🔍 SEE:**

```
    PROBE 1 value in USD : 15000.00 USD
    PROBE 2 value in EUR : 13636.36 EUR
    PROBE 3 total USD    : 17500.00 USD
    PROBE 4 refused      : cannot combine USD with EUR
```

## What happened inside

```
1. Quantity 100 × Money 150.00 USD
        ↓  rounding chosen by caller
2. 15000.00 USD
        ↓  eurUsd.inverse() → USD/EUR
3. convert checks the currency is USD ✓
        ↓  rounds to EUR decimals (2)
4. 13636.36 EUR
        ↓
5. plus(cash) → same currency ✓ → 17500.00 USD
        ↓
6. plus(valueEur) → different currency ✗ → refused
```

**YOU SAW:** Four correct results and one refusal.

**INSIDE:** Every step checked the currency before doing arithmetic.

**ARCHITECT LESSON:** The types did the checking. No separate validation code was written anywhere.

**↩️ RESET:** Delete `Probe.java`, then run `./gradlew :modules:domain:test`.

---

## Layer 1 checklist

| | Item |
|---|---|
| ☐ | Probe setup works |
| ☐ | Ex 1 — `100.00 USD` and `100 JPY` |
| ☐ | Ex 2 — `150.00 USD` from mixing currencies |
| ☐ | Ex 3 — `1.01 USD` from silent rounding |
| ☐ | **Ex 4 — `110.00` vs `90.91`** |
| ☐ | Ex 5 — `10.05` vs `10.04` |
| ☐ | Ex 6 — compile error on wrong id type |
| ☐ | Ex 7 — rule names `BadMoney.amount` |
| ☐ | Ex 8 — `same? false` |
| ☐ | Final exercise — all 4 lines |
| ☐ | `Probe.java` deleted, 37 tests green |

## The five lessons

1. An amount and its currency are one value, so two currencies cannot be added by mistake.
2. The currency decides the decimals — USD 2, JPY 0.
3. Nothing rounds silently; the caller always chooses.
4. An exchange rate knows its direction, because the wrong answer looks normal.
5. Identifiers share one rule but stay separate types, so they cannot be swapped.

---

**⏸️ STOP.** Exercise 4 is the one to do carefully — it is the whole reason this layer exists.

Say **LAYER 2** when the checklist is done. Layer 2 builds the data generator: portfolios you can reproduce exactly from a number.
