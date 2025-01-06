package dev.hedge.calculation.incremental;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.calculation.TestMarket;
import dev.hedge.calculation.incremental.CalculationNode.CurrencyTotal;
import dev.hedge.calculation.incremental.CalculationNode.HedgeTarget;
import dev.hedge.calculation.incremental.CalculationNode.PortfolioTotal;
import dev.hedge.calculation.incremental.CalculationNode.PositionValue;
import dev.hedge.calculation.incremental.CalculationNode.PriceInput;
import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.FundId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AffectedClosureTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");

    private final MarketSnapshot market = TestMarket.snapshot();
    private DependencyGraph graph;
    private ReverseDependencyIndex index;

    /** Two positions in different currencies, so one price change must not reach the other. */
    @BeforeEach
    void buildGraph() {
        Fund fund = new Fund(
                FundId.of("F-1"),
                TestMarket.USD,
                List.of(new Account(
                        ACCOUNT,
                        List.of(
                                new Position(ACCOUNT, TestMarket.US_STOCK, Quantity.of("10")),
                                new Position(ACCOUNT, TestMarket.EU_STOCK, Quantity.of("20"))),
                        List.of())));
        graph = DependencyGraph.of(fund, List.of(), market, TODAY);
        index = ReverseDependencyIndex.from(graph);
    }

    private AffectedClosure closureOf(CalculationNode changed) {
        return AffectedClosure.of(Set.of(changed), graph, index);
    }

    @Test
    @DisplayName("a price change reaches that position, its currency, its hedge and the total")
    void a_price_change_reaches_its_own_chain() {
        AffectedClosure closure = closureOf(new PriceInput(TestMarket.EU_STOCK));

        assertThat(closure.nodes())
                .containsExactlyInAnyOrder(
                        new PositionValue(ACCOUNT, TestMarket.EU_STOCK),
                        new CurrencyTotal(TestMarket.EUR),
                        new HedgeTarget(TestMarket.EUR),
                        new PortfolioTotal());
    }

    @Test
    @DisplayName("a price change never reaches an unrelated position's value")
    void a_price_change_does_not_reach_other_positions() {
        assertThat(closureOf(new PriceInput(TestMarket.EU_STOCK)).nodes())
                .doesNotContain(new PositionValue(ACCOUNT, TestMarket.US_STOCK));
    }

    @Test
    @DisplayName("the closure is smaller than the graph, which is the whole point")
    void the_closure_is_smaller_than_the_graph() {
        assertThat(closureOf(new PriceInput(TestMarket.EU_STOCK)).size())
                .isLessThan(graph.nodeCount());
    }

    @Test
    @DisplayName("within the closure every dependency comes before the node that reads it")
    void the_closure_is_topologically_ordered() {
        List<CalculationNode> ordered = closureOf(new PriceInput(TestMarket.EU_STOCK)).nodes();

        for (int index = 0; index < ordered.size(); index++) {
            for (CalculationNode dependency : graph.dependenciesOf(ordered.get(index))) {
                int position = ordered.indexOf(dependency);
                if (position >= 0) {
                    assertThat(position).isLessThan(index);
                }
            }
        }
    }

    @Test
    @DisplayName("changing an input nothing reads affects nothing")
    void an_unknown_input_affects_nothing() {
        assertThat(closureOf(new PriceInput(SecurityId.of("SEC-NOT-HELD"))).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("changing every input degenerates to recomputing every derived node")
    void changing_everything_degenerates_to_a_full_recalculation() {
        Set<CalculationNode> allInputs = graph.nodes().stream()
                .filter(CalculationNode.Input.class::isInstance)
                .collect(Collectors.toSet());

        AffectedClosure closure = AffectedClosure.of(allInputs, graph, index);

        long derived = graph.nodes().stream()
                .filter(CalculationNode.Derived.class::isInstance)
                .count();
        assertThat(closure.size()).isEqualTo((int) derived);
    }
}
