package dev.hedge.calculation.incremental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hedge.calculation.CalculationException;
import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.calculation.TestMarket;
import dev.hedge.calculation.full.FullExposureCalculator;
import dev.hedge.calculation.incremental.InputChange.CashChange;
import dev.hedge.calculation.incremental.InputChange.PriceChange;
import dev.hedge.calculation.incremental.InputChange.QuantityChange;
import dev.hedge.domain.exposure.HedgePolicy;
import dev.hedge.domain.exposure.PortfolioExposure;
import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ForwardId;
import dev.hedge.domain.id.FundId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.FxRate;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Price;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.portfolio.Security;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every case applies a change incrementally and compares the answer with a full recalculation of
 * the same end state. The full calculator is the oracle: if these two ever disagree, the
 * incremental engine is wrong by definition.
 */
class IncrementalExposureEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");
    private static final HedgePolicy POLICY = HedgePolicy.fullyHedged();

    private final MarketSnapshot market = TestMarket.snapshot();
    private final FullExposureCalculator full = new FullExposureCalculator();

    private static Fund fundWith(List<Position> positions, List<Money> cash) {
        return new Fund(
                FundId.of("F-1"), TestMarket.USD, List.of(new Account(ACCOUNT, positions, cash)));
    }

    private static Fund twoCurrencyFund() {
        return fundWith(
                List.of(
                        new Position(ACCOUNT, TestMarket.US_STOCK, Quantity.of("10")),
                        new Position(ACCOUNT, TestMarket.EU_STOCK, Quantity.of("20"))),
                List.of(Money.of("250.00", "EUR")));
    }

    private IncrementalExposureEngine engineFor(Fund fund) {
        return IncrementalExposureEngine.start(fund, List.of(), market, POLICY, TODAY);
    }

    private PortfolioExposure fullAnswerFor(Fund fund, MarketSnapshot withMarket) {
        return full.calculate(fund, List.of(), withMarket, POLICY, TODAY);
    }

    /** The fixed market with one security repriced, for comparing against a full recalculation. */
    private MarketSnapshot marketWithEuStockAt(String price) {
        return MarketSnapshot.builder()
                .security(Security.denominatedIn(TestMarket.US_STOCK, "USD"))
                .security(Security.denominatedIn(TestMarket.EU_STOCK, "EUR"))
                .security(Security.denominatedIn(TestMarket.JP_STOCK, "JPY"))
                .security(market.security(TestMarket.GLOBAL_ETF))
                .price(Price.of("SEC-US", "100.00", "USD"))
                .price(new Price(TestMarket.EU_STOCK, Money.of(price, "EUR")))
                .price(Price.of("SEC-JP", "2000", "JPY"))
                .price(Price.of("SEC-ETF", "25.00", "USD"))
                .rate(FxRate.of("EUR", "USD", "1.10"))
                .rate(FxRate.of("JPY", "USD", "0.0065"))
                .build();
    }

    @Test
    @DisplayName("before any change the engine already agrees with the full calculation")
    void the_cold_state_matches_the_full_calculation() {
        Fund fund = twoCurrencyFund();

        assertThat(engineFor(fund).report()).isEqualTo(fullAnswerFor(fund, market));
    }

    @Test
    @DisplayName("after a price change the incremental answer equals a full recalculation")
    void a_price_change_matches_the_full_calculation() {
        Fund fund = twoCurrencyFund();

        PortfolioExposure incremental =
                engineFor(fund).apply(new PriceChange(TestMarket.EU_STOCK, Money.of("60.00", "EUR")));

        assertThat(incremental).isEqualTo(fullAnswerFor(fund, marketWithEuStockAt("60.00")));
    }

    @Test
    @DisplayName("a quantity change matches a full recalculation of the changed fund")
    void a_quantity_change_matches_the_full_calculation() {
        Fund after = fundWith(
                List.of(
                        new Position(ACCOUNT, TestMarket.US_STOCK, Quantity.of("10")),
                        new Position(ACCOUNT, TestMarket.EU_STOCK, Quantity.of("35"))),
                List.of(Money.of("250.00", "EUR")));

        PortfolioExposure incremental = engineFor(twoCurrencyFund())
                .apply(new QuantityChange(ACCOUNT, TestMarket.EU_STOCK, Quantity.of("35")));

        assertThat(incremental).isEqualTo(fullAnswerFor(after, market));
    }

    @Test
    @DisplayName("a cash change matches a full recalculation of the changed fund")
    void a_cash_change_matches_the_full_calculation() {
        Fund after = fundWith(
                List.of(
                        new Position(ACCOUNT, TestMarket.US_STOCK, Quantity.of("10")),
                        new Position(ACCOUNT, TestMarket.EU_STOCK, Quantity.of("20"))),
                List.of(Money.of("900.00", "EUR")));

        PortfolioExposure incremental =
                engineFor(twoCurrencyFund()).apply(new CashChange(ACCOUNT, Money.of("900.00", "EUR")));

        assertThat(incremental).isEqualTo(fullAnswerFor(after, market));
    }

    @Test
    @DisplayName("applying many changes never drifts away from the full answer")
    void repeated_changes_do_not_drift() {
        Fund fund = twoCurrencyFund();
        IncrementalExposureEngine engine = engineFor(fund);

        // Walk the price up and down in uneven steps, ending exactly where it started.
        for (String price : List.of("51.37", "49.01", "73.33", "50.00")) {
            engine.apply(new PriceChange(TestMarket.EU_STOCK, Money.of(price, "EUR")));
        }

        assertThat(engine.report()).isEqualTo(fullAnswerFor(fund, market));
    }

    @Test
    @DisplayName("a price for a security the fund does not hold changes nothing")
    void an_unheld_security_is_ignored() {
        IncrementalExposureEngine engine = engineFor(twoCurrencyFund());
        PortfolioExposure before = engine.report();

        assertThat(engine.apply(new PriceChange(SecurityId.of("SEC-JP"), Money.of("1", "JPY"))))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a quantity change for a position that does not exist is refused, not guessed")
    void a_structural_change_is_refused() {
        IncrementalExposureEngine engine = engineFor(twoCurrencyFund());

        assertThatThrownBy(() ->
                        engine.apply(new QuantityChange(ACCOUNT, TestMarket.JP_STOCK, Quantity.of("5"))))
                .isInstanceOf(CalculationException.class)
                .hasMessageContaining("rebuild");
    }

    @Test
    @DisplayName("a mapped security's delta reaches every currency it is mapped to")
    void a_mapped_security_updates_all_its_currencies() {
        Fund before =
                fundWith(List.of(new Position(ACCOUNT, TestMarket.GLOBAL_ETF, Quantity.of("100"))), List.of());
        Fund after =
                fundWith(List.of(new Position(ACCOUNT, TestMarket.GLOBAL_ETF, Quantity.of("250"))), List.of());

        PortfolioExposure incremental = engineFor(before)
                .apply(new QuantityChange(ACCOUNT, TestMarket.GLOBAL_ETF, Quantity.of("250")));

        assertThat(incremental).isEqualTo(fullAnswerFor(after, market));
    }

    @Test
    @DisplayName("a live forward is carried into the incremental state unchanged")
    void forwards_are_included() {
        Fund fund = twoCurrencyFund();
        Forward hedge = new Forward(
                ForwardId.of("FWD-1"),
                Money.of("500.00", "EUR"),
                Money.of("550.00", "USD"),
                TODAY.plusMonths(3));

        IncrementalExposureEngine engine =
                IncrementalExposureEngine.start(fund, List.of(hedge), market, POLICY, TODAY);

        assertThat(engine.report()).isEqualTo(full.calculate(fund, List.of(hedge), market, POLICY, TODAY));
    }

    @Test
    @DisplayName("one change touches a handful of nodes, not the whole graph")
    void a_change_touches_only_a_few_nodes() {
        IncrementalExposureEngine engine = engineFor(twoCurrencyFund());

        int closure =
                engine.closureSizeFor(new PriceChange(TestMarket.EU_STOCK, Money.of("60.00", "EUR")));

        assertThat(closure).isPositive();
        assertThat(closure).isLessThan(engine.nodeCount());
    }
}
