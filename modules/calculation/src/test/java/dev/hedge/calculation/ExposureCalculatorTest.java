package dev.hedge.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hedge.domain.Account;
import dev.hedge.domain.AccountId;
import dev.hedge.domain.CurrencyExposure;
import dev.hedge.domain.Forward;
import dev.hedge.domain.ForwardId;
import dev.hedge.domain.Fund;
import dev.hedge.domain.FundId;
import dev.hedge.domain.HedgePolicy;
import dev.hedge.domain.HedgeRequirement;
import dev.hedge.domain.Money;
import dev.hedge.domain.PortfolioExposure;
import dev.hedge.domain.Position;
import dev.hedge.domain.Quantity;
import dev.hedge.domain.SecurityId;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExposureCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");

    private final MarketSnapshot market = TestMarket.snapshot();
    private final ExposureCalculator calculator = new ExposureCalculator();

    private static Fund fundHolding(SecurityId security, String quantity, List<Money> cash) {
        return new Fund(
                FundId.of("F-1"),
                TestMarket.USD,
                List.of(new Account(
                        ACCOUNT,
                        List.of(new Position(ACCOUNT, security, Quantity.of(quantity))),
                        cash)));
    }

    private PortfolioExposure report(Fund fund, List<Forward> forwards, HedgePolicy policy) {
        return calculator.calculate(fund, forwards, market, policy, TODAY);
    }

    @Test
    @DisplayName("a position in the base currency produces exposure only in the base currency")
    void a_base_currency_position_produces_one_exposure() {
        Fund fund = fundHolding(TestMarket.US_STOCK, "10", List.of());

        PortfolioExposure report = report(fund, List.of(), HedgePolicy.fullyHedged());

        assertThat(report.totalValue()).isEqualTo(Money.of("1000.00", "USD"));
        assertThat(report.exposures()).hasSize(1);
        CurrencyExposure usd = report.exposureIn(TestMarket.USD).orElseThrow();
        assertThat(usd.securities()).isEqualTo(Money.of("1000.00", "USD"));
        assertThat(usd.gross()).isEqualTo(Money.of("1000.00", "USD"));
        assertThat(usd.net()).isEqualTo(Money.of("1000.00", "USD"));
        assertThat(report.hedgeRequirements()).isEmpty();
    }

    @Test
    @DisplayName("a foreign position is exposed in its own currency but valued in the base currency")
    void a_foreign_position_is_exposed_in_its_own_currency() {
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());

        PortfolioExposure report = report(fund, List.of(), HedgePolicy.fullyHedged());

        assertThat(report.totalValue()).isEqualTo(Money.of("550.00", "USD"));
        CurrencyExposure eur = report.exposureIn(TestMarket.EUR).orElseThrow();
        assertThat(eur.gross()).isEqualTo(Money.of("500.00", "EUR"));
    }

    @Test
    @DisplayName("a full hedge on an unhedged foreign exposure asks for the whole amount")
    void a_full_hedge_asks_for_the_whole_exposure() {
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());

        HedgeRequirement hedge = report(fund, List.of(), HedgePolicy.fullyHedged())
                .hedgeIn(TestMarket.EUR)
                .orElseThrow();

        assertThat(hedge.gross()).isEqualTo(Money.of("500.00", "EUR"));
        assertThat(hedge.hedged()).isEqualTo(Money.of("0.00", "EUR"));
        assertThat(hedge.target()).isEqualTo(Money.of("-500.00", "EUR"));
        assertThat(hedge.required()).isEqualTo(Money.of("-500.00", "EUR"));
        assertThat(hedge.actionable()).isTrue();
    }

    @Test
    @DisplayName("a live forward that exactly offsets the exposure leaves nothing to do")
    void a_matching_forward_satisfies_the_policy() {
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());
        Forward hedgeTrade = new Forward(
                ForwardId.of("FWD-1"),
                Money.of("500.00", "EUR"),
                Money.of("550.00", "USD"),
                TODAY.plusMonths(3));

        PortfolioExposure report = report(fund, List.of(hedgeTrade), HedgePolicy.fullyHedged());

        CurrencyExposure eur = report.exposureIn(TestMarket.EUR).orElseThrow();
        assertThat(eur.gross()).isEqualTo(Money.of("500.00", "EUR"));
        assertThat(eur.forwards()).isEqualTo(Money.of("-500.00", "EUR"));
        assertThat(eur.net()).isEqualTo(Money.of("0.00", "EUR"));

        HedgeRequirement hedge = report.hedgeIn(TestMarket.EUR).orElseThrow();
        assertThat(hedge.required()).isEqualTo(Money.of("0.00", "EUR"));
        assertThat(hedge.actionable()).isFalse();
        assertThat(report.actionableHedges()).isEmpty();
    }

    @Test
    @DisplayName("the bought leg of a forward creates exposure of its own")
    void the_bought_leg_creates_exposure() {
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());
        Forward hedgeTrade = new Forward(
                ForwardId.of("FWD-1"),
                Money.of("500.00", "EUR"),
                Money.of("550.00", "USD"),
                TODAY.plusMonths(3));

        CurrencyExposure usd = report(fund, List.of(hedgeTrade), HedgePolicy.fullyHedged())
                .exposureIn(TestMarket.USD)
                .orElseThrow();

        assertThat(usd.securities()).isEqualTo(Money.of("0.00", "USD"));
        assertThat(usd.forwards()).isEqualTo(Money.of("550.00", "USD"));
        assertThat(usd.net()).isEqualTo(Money.of("550.00", "USD"));
    }

    @Test
    @DisplayName("a forward that has already matured has no effect at all")
    void a_matured_forward_is_ignored() {
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());
        Forward expired = new Forward(
                ForwardId.of("FWD-OLD"),
                Money.of("500.00", "EUR"),
                Money.of("550.00", "USD"),
                TODAY.minusDays(1));

        assertThat(report(fund, List.of(expired), HedgePolicy.fullyHedged()))
                .isEqualTo(report(fund, List.of(), HedgePolicy.fullyHedged()));
    }

    @Test
    @DisplayName("a security with an exposure mapping is split across currencies by weight")
    void a_mapped_security_is_split_by_weight() {
        Fund fund = fundHolding(TestMarket.GLOBAL_ETF, "100", List.of());

        PortfolioExposure report = report(fund, List.of(), HedgePolicy.fullyHedged());

        assertThat(report.totalValue()).isEqualTo(Money.of("2500.00", "USD"));
        assertThat(report.exposureIn(TestMarket.USD).orElseThrow().gross())
                .isEqualTo(Money.of("1500.00", "USD"));
        assertThat(report.exposureIn(TestMarket.EUR).orElseThrow().gross())
                .isEqualTo(Money.of("681.82", "EUR"));
        assertThat(report.exposureIn(TestMarket.JPY).orElseThrow().gross())
                .isEqualTo(Money.of("38462", "JPY"));
    }

    @Test
    @DisplayName("cash adds to the exposure of its own currency")
    void cash_adds_to_its_own_currency() {
        Fund fund = fundHolding(TestMarket.US_STOCK, "10", List.of(Money.of("250.00", "EUR")));

        PortfolioExposure report = report(fund, List.of(), HedgePolicy.fullyHedged());

        CurrencyExposure eur = report.exposureIn(TestMarket.EUR).orElseThrow();
        assertThat(eur.securities()).isEqualTo(Money.of("0.00", "EUR"));
        assertThat(eur.cash()).isEqualTo(Money.of("250.00", "EUR"));
        assertThat(eur.gross()).isEqualTo(Money.of("250.00", "EUR"));
        assertThat(report.totalValue()).isEqualTo(Money.of("1275.00", "USD"));
    }

    @Test
    @DisplayName("a half hedge asks for half of the exposure")
    void a_half_hedge_asks_for_half() {
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());

        HedgeRequirement hedge = report(fund, List.of(), HedgePolicy.of("0.50", "0.01"))
                .hedgeIn(TestMarket.EUR)
                .orElseThrow();

        assertThat(hedge.target()).isEqualTo(Money.of("-250.00", "EUR"));
        assertThat(hedge.required()).isEqualTo(Money.of("-250.00", "EUR"));
    }

    @Test
    @DisplayName("a tiny residual inside the tolerance is reported but not actionable")
    void a_residual_inside_the_tolerance_is_not_actionable() {
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());
        Forward almostEnough = new Forward(
                ForwardId.of("FWD-1"),
                Money.of("498.00", "EUR"),
                Money.of("547.80", "USD"),
                TODAY.plusMonths(3));

        HedgeRequirement hedge = report(fund, List.of(almostEnough), HedgePolicy.fullyHedged())
                .hedgeIn(TestMarket.EUR)
                .orElseThrow();

        assertThat(hedge.required()).isEqualTo(Money.of("-2.00", "EUR"));
        assertThat(hedge.actionable()).isFalse();
    }

    @Test
    @DisplayName("an empty fund calculates to nothing rather than failing")
    void an_empty_fund_produces_an_empty_report() {
        Fund fund = new Fund(FundId.of("F-EMPTY"), TestMarket.USD, List.of());

        PortfolioExposure report = report(fund, List.of(), HedgePolicy.fullyHedged());

        assertThat(report.totalValue()).isEqualTo(Money.of("0.00", "USD"));
        assertThat(report.exposures()).isEmpty();
        assertThat(report.hedgeRequirements()).isEmpty();
    }

    @Test
    @DisplayName("an unknown security fails loudly, naming what is missing")
    void an_unknown_security_is_refused() {
        Fund fund = fundHolding(SecurityId.of("SEC-UNKNOWN"), "10", List.of());

        assertThatThrownBy(() -> report(fund, List.of(), HedgePolicy.fullyHedged()))
                .isInstanceOf(CalculationException.class)
                .hasMessageContaining("SEC-UNKNOWN");
    }
}