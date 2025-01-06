package dev.hedge.calculation.incremental;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.calculation.TestMarket;
import dev.hedge.calculation.full.FullExposureCalculator;
import dev.hedge.calculation.incremental.InputChange.CashChange;
import dev.hedge.calculation.incremental.InputChange.PriceChange;
import dev.hedge.calculation.incremental.InputChange.QuantityChange;
import dev.hedge.domain.exposure.HedgePolicy;
import dev.hedge.domain.exposure.PortfolioExposure;
import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.FundId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.FxRate;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Price;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.portfolio.Security;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Layer 14. The incremental engine is only trustworthy if it cannot be told apart from the full
 * recalculation, so these properties drive a random fund through a random sequence of changes and
 * demand that the two agree exactly at the end. Any disagreement, even one cent, fails.
 *
 * <p>This is also the machinery the later phases reuse: once a second implementation exists, the
 * question is always the same shape — does the fast path still equal the oracle?
 */
class DifferentialProperties {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final HedgePolicy POLICY = HedgePolicy.fullyHedged();
    private static final MarketSnapshot REFERENCE = TestMarket.snapshot();

    private static final List<Security> SECURITIES = List.of(
            REFERENCE.security(TestMarket.US_STOCK),
            REFERENCE.security(TestMarket.EU_STOCK),
            REFERENCE.security(TestMarket.JP_STOCK),
            REFERENCE.security(TestMarket.GLOBAL_ETF));

    private static final List<FxRate> RATES =
            List.of(FxRate.of("EUR", "USD", "1.10"), FxRate.of("JPY", "USD", "0.0065"));

    private static final Map<SecurityId, Money> INITIAL_PRICES = Map.of(
            TestMarket.US_STOCK, Money.of("100.00", "USD"),
            TestMarket.EU_STOCK, Money.of("50.00", "EUR"),
            TestMarket.JP_STOCK, Money.of("2000", "JPY"),
            TestMarket.GLOBAL_ETF, Money.of("25.00", "USD"));

    /** The state the full calculator sees, kept in step with the changes fed to the engine. */
    private record World(Fund fund, Map<SecurityId, Money> prices) {

        MarketSnapshot market() {
            MarketSnapshot.Builder builder = MarketSnapshot.builder().securities(SECURITIES).rates(RATES);
            prices.forEach((security, price) -> builder.price(new Price(security, price)));
            return builder.build();
        }

        World with(InputChange change) {
            return switch (change) {
                case PriceChange price -> {
                    Map<SecurityId, Money> next = new LinkedHashMap<>(prices);
                    next.put(price.security(), price.price());
                    yield new World(fund, next);
                }
                case QuantityChange quantity -> new World(
                        mapAccounts(account -> account.id().equals(quantity.account())
                                ? withQuantity(account, quantity)
                                : account),
                        prices);
                case CashChange cash -> new World(
                        mapAccounts(account -> account.id().equals(cash.account())
                                ? withCash(account, cash)
                                : account),
                        prices);
            };
        }

        private Fund mapAccounts(java.util.function.UnaryOperator<Account> change) {
            return new Fund(
                    fund.id(),
                    fund.baseCurrency(),
                    fund.accounts().stream().map(change).toList());
        }

        private static Account withQuantity(Account account, QuantityChange change) {
            return new Account(
                    account.id(),
                    account.positions().stream()
                            .map(position -> position.securityId().equals(change.security())
                                    ? new Position(account.id(), change.security(), change.quantity())
                                    : position)
                            .toList(),
                    account.cashBalances());
        }

        private static Account withCash(Account account, CashChange change) {
            return new Account(
                    account.id(),
                    account.positions(),
                    account.cashBalances().stream()
                            .map(balance -> balance.currency().equals(change.amount().currency())
                                    ? change.amount()
                                    : balance)
                            .toList());
        }
    }

    record Scenario(Fund fund, List<InputChange> changes) {}

    private static PortfolioExposure fullAnswer(World world) {
        return new FullExposureCalculator()
                .calculate(world.fund(), List.of(), world.market(), POLICY, TODAY);
    }

    private static IncrementalExposureEngine engineFor(World world) {
        return IncrementalExposureEngine.start(world.fund(), List.of(), world.market(), POLICY, TODAY);
    }

    @Property
    void the_cold_state_always_equals_the_full_calculation(@ForAll("scenarios") Scenario scenario) {
        World world = new World(scenario.fund(), INITIAL_PRICES);

        assertThat(engineFor(world).report()).isEqualTo(fullAnswer(world));
    }

    @Property
    void after_any_sequence_of_changes_the_two_engines_agree(@ForAll("scenarios") Scenario scenario) {
        World world = new World(scenario.fund(), INITIAL_PRICES);
        IncrementalExposureEngine engine = engineFor(world);

        PortfolioExposure incremental = engine.report();
        for (InputChange change : scenario.changes()) {
            incremental = engine.apply(change);
            world = world.with(change);
        }

        assertThat(incremental).isEqualTo(fullAnswer(world));
    }

    @Property
    void every_intermediate_state_also_agrees(@ForAll("scenarios") Scenario scenario) {
        World world = new World(scenario.fund(), INITIAL_PRICES);
        IncrementalExposureEngine engine = engineFor(world);

        for (InputChange change : scenario.changes()) {
            PortfolioExposure incremental = engine.apply(change);
            world = world.with(change);
            assertThat(incremental).isEqualTo(fullAnswer(world));
        }
    }

    @Property
    void returning_every_price_to_where_it_started_restores_the_original_answer(
            @ForAll("scenarios") Scenario scenario) {
        World world = new World(scenario.fund(), INITIAL_PRICES);
        IncrementalExposureEngine engine = engineFor(world);
        PortfolioExposure original = engine.report();

        for (InputChange change : scenario.changes()) {
            if (change instanceof PriceChange) {
                engine.apply(change);
            }
        }
        // Put every price back exactly where it began; running sums must land on the original cent.
        // Every security, not just one account's: a price is global, and restoring only the ones
        // the first account happens to hold leaves the rest moved.
        INITIAL_PRICES.forEach((security, price) -> engine.apply(new PriceChange(security, price)));

        assertThat(engine.report()).isEqualTo(original);
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return funds().flatMap(fund -> changesFor(fund).map(changes -> new Scenario(fund, changes)));
    }

    private Arbitrary<Fund> funds() {
        return accountDrafts().list().ofMinSize(1).ofMaxSize(2).map(drafts -> {
            List<Account> accounts = new ArrayList<>(drafts.size());
            for (int index = 0; index < drafts.size(); index++) {
                accounts.add(drafts.get(index).toAccount(AccountId.of(String.format("ACC-%03d", index))));
            }
            return new Fund(FundId.of("F-DIFF"), TestMarket.USD, accounts);
        });
    }

    private Arbitrary<Draft> accountDrafts() {
        return Arbitraries.subsetOf(TestMarket.UNIVERSE)
                .filter(securities -> !securities.isEmpty())
                .flatMap(securities -> Arbitraries.integers()
                        .between(1, 1000)
                        .list()
                        .ofSize(securities.size())
                        .flatMap(quantities -> cashBalances()
                                .map(cash -> new Draft(List.copyOf(securities), quantities, cash))));
    }

    private Arbitrary<List<Money>> cashBalances() {
        return Arbitraries.of("USD", "EUR", "JPY")
                .set()
                .ofMinSize(0)
                .ofMaxSize(2)
                .flatMap(codes -> {
                    List<String> ordered = List.copyOf(codes);
                    return Arbitraries.integers()
                            .between(1, 100_000)
                            .list()
                            .ofSize(ordered.size())
                            .map(values -> {
                                List<Money> balances = new ArrayList<>(ordered.size());
                                for (int index = 0; index < ordered.size(); index++) {
                                    balances.add(
                                            Money.of(String.valueOf(values.get(index)), ordered.get(index)));
                                }
                                return balances;
                            });
                });
    }

    /** Only changes that target something this fund actually has; anything else is structural. */
    private Arbitrary<List<InputChange>> changesFor(Fund fund) {
        List<Arbitrary<InputChange>> options = new ArrayList<>();
        for (Account account : fund.accounts()) {
            for (Position position : account.positions()) {
                options.add(Arbitraries.integers()
                        .between(1, 1000)
                        .map(quantity -> (InputChange) new QuantityChange(
                                account.id(), position.securityId(), Quantity.of(String.valueOf(quantity)))));
                options.add(pricesFor(position.securityId()));
            }
            for (Money balance : account.cashBalances()) {
                options.add(Arbitraries.integers()
                        .between(0, 100_000)
                        .map(value -> (InputChange) new CashChange(
                                account.id(),
                                Money.of(
                                        String.valueOf(value),
                                        balance.currency().getCurrencyCode()))));
            }
        }
        return Arbitraries.oneOf(options).list().ofMinSize(1).ofMaxSize(8);
    }

    /** A new price always in the currency the security is already quoted in. */
    private Arbitrary<InputChange> pricesFor(SecurityId security) {
        Currency currency = INITIAL_PRICES.get(security).currency();
        int digits = currency.getDefaultFractionDigits();
        return Arbitraries.integers()
                .between(1, 1_000_000)
                .map(value -> (InputChange) new PriceChange(
                        security,
                        new Money(
                                BigDecimal.valueOf(value)
                                        .movePointLeft(digits)
                                        .setScale(digits, RoundingMode.UNNECESSARY),
                                currency)));
    }

    record Draft(List<SecurityId> securities, List<Integer> quantities, List<Money> cash) {

        Account toAccount(AccountId id) {
            List<Position> positions = new ArrayList<>(securities.size());
            for (int index = 0; index < securities.size(); index++) {
                positions.add(new Position(
                        id, securities.get(index), Quantity.of(String.valueOf(quantities.get(index)))));
            }
            return new Account(id, positions, cash);
        }
    }
}
