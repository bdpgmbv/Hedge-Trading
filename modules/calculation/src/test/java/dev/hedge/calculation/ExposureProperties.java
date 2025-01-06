package dev.hedge.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.calculation.full.FullExposureCalculator;
import dev.hedge.domain.exposure.CurrencyExposure;
import dev.hedge.domain.exposure.HedgePolicy;
import dev.hedge.domain.exposure.HedgeRequirement;
import dev.hedge.domain.exposure.PortfolioExposure;
import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ForwardId;
import dev.hedge.domain.id.FundId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
/**
 * Example tests show that known cases are right. These say what must hold for every portfolio the
 * generator can produce. They are also the machinery Phase 3 needs, when the claim to prove becomes
 * "incremental equals full".
 */
class ExposureProperties {

    private static final MarketSnapshot MARKET = TestMarket.snapshot();
    private static final FullExposureCalculator CALCULATOR = new FullExposureCalculator();
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);

    @Property
    void gross_is_always_securities_plus_cash(@ForAll("funds") Fund fund) {
        for (CurrencyExposure exposure : report(fund, HedgePolicy.fullyHedged()).exposures()) {
            assertThat(exposure.gross()).isEqualTo(exposure.securities().plus(exposure.cash()));
        }
    }

    @Property
    void net_is_always_gross_plus_forwards(@ForAll("funds") Fund fund) {
        for (CurrencyExposure exposure : report(fund, HedgePolicy.fullyHedged()).exposures()) {
            assertThat(exposure.net()).isEqualTo(exposure.gross().plus(exposure.forwards()));
        }
    }

    @Property
    void the_base_currency_is_never_hedged(@ForAll("funds") Fund fund) {
        for (HedgeRequirement hedge : report(fund, HedgePolicy.fullyHedged()).hedgeRequirements()) {
            assertThat(hedge.currency()).isNotEqualTo(TestMarket.USD);
        }
    }

    @Property
    void an_unhedged_policy_never_requires_action(@ForAll("funds") Fund fund) {
        PortfolioExposure report = report(fund, HedgePolicy.unhedged());
        assertThat(report.actionableHedges()).isEmpty();
        for (HedgeRequirement hedge : report.hedgeRequirements()) {
            assertThat(hedge.required().isZero()).isTrue();
        }
    }

    @Property
    void a_full_hedge_always_asks_for_the_opposite_of_the_gross_exposure(@ForAll("funds") Fund fund) {
        for (HedgeRequirement hedge : report(fund, HedgePolicy.fullyHedged()).hedgeRequirements()) {
            assertThat(hedge.required().amount())
                    .isEqualByComparingTo(hedge.gross().amount().negate());
        }
    }

    @Property
    void every_currency_appears_at_most_once(@ForAll("funds") Fund fund) {
        List<CurrencyExposure> exposures = report(fund, HedgePolicy.fullyHedged()).exposures();
        assertThat(exposures.stream().map(CurrencyExposure::currency).distinct().count())
                .isEqualTo(exposures.size());
    }

    @Property
    void the_same_input_always_produces_the_same_answer(@ForAll("funds") Fund fund) {
        assertThat(report(fund, HedgePolicy.fullyHedged()))
                .isEqualTo(report(fund, HedgePolicy.fullyHedged()));
    }

    @Property
    void a_long_only_portfolio_is_never_worth_less_than_nothing(@ForAll("funds") Fund fund) {
        assertThat(report(fund, HedgePolicy.fullyHedged()).totalValue().isNegative()).isFalse();
    }

    @Property
    void a_matured_forward_changes_nothing(@ForAll("funds") Fund fund) {
        Forward expired = new Forward(
                ForwardId.of("FWD-OLD"),
                Money.of("1000.00", "EUR"),
                Money.of("1100.00", "USD"),
                TODAY.minusDays(1));

        assertThat(CALCULATOR.calculate(
                        fund, List.of(expired), MARKET, HedgePolicy.fullyHedged(), TODAY))
                .isEqualTo(report(fund, HedgePolicy.fullyHedged()));
    }

    private PortfolioExposure report(Fund fund, HedgePolicy policy) {
        return CALCULATOR.calculate(fund, List.of(), MARKET, policy, TODAY);
    }

    @Provide
    Arbitrary<Fund> funds() {
        return accountDrafts().list().ofMinSize(0).ofMaxSize(3).map(drafts -> {
            List<Account> accounts = new ArrayList<>(drafts.size());
            for (int index = 0; index < drafts.size(); index++) {
                accounts.add(drafts.get(index).toAccount(AccountId.of(String.format("ACC-%03d", index))));
            }
            return new Fund(FundId.of("F-PROPERTY"), TestMarket.USD, accounts);
        });
    }

    private Arbitrary<Draft> accountDrafts() {
        return Arbitraries.subsetOf(TestMarket.UNIVERSE).flatMap(securities -> Arbitraries.integers()
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
                .ofMaxSize(3)
                .flatMap(codes -> {
                    List<String> ordered = List.copyOf(codes);
                    return Arbitraries.integers()
                            .between(1, 100_000)
                            .list()
                            .ofSize(ordered.size())
                            .map(values -> {
                                List<Money> balances = new ArrayList<>(ordered.size());
                                for (int index = 0; index < ordered.size(); index++) {
                                    balances.add(Money.of(
                                            String.valueOf(values.get(index)), ordered.get(index)));
                                }
                                return balances;
                            });
                });
    }

    private record Draft(List<SecurityId> securities, List<Integer> quantities, List<Money> cash) {

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
