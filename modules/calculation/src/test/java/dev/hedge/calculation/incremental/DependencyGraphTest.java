package dev.hedge.calculation.incremental;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.calculation.TestMarket;
import dev.hedge.calculation.incremental.CalculationNode.CurrencyTotal;
import dev.hedge.calculation.incremental.CalculationNode.ForwardInput;
import dev.hedge.calculation.incremental.CalculationNode.HedgeTarget;
import dev.hedge.calculation.incremental.CalculationNode.PortfolioTotal;
import dev.hedge.calculation.incremental.CalculationNode.PositionValue;
import dev.hedge.calculation.incremental.CalculationNode.PriceInput;
import dev.hedge.calculation.incremental.CalculationNode.QuantityInput;
import dev.hedge.calculation.incremental.CalculationNode.RateInput;
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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");

    private final MarketSnapshot market = TestMarket.snapshot();

    private static Fund fundHolding(SecurityId security, String quantity, List<Money> cash) {
        return new Fund(
                FundId.of("F-1"),
                TestMarket.USD,
                List.of(new Account(
                        ACCOUNT, List.of(new Position(ACCOUNT, security, Quantity.of(quantity))), cash)));
    }

    private DependencyGraph graphFor(Fund fund, List<Forward> forwards) {
        return DependencyGraph.of(fund, forwards, market, TODAY);
    }

    @Test
    @DisplayName("a position's value depends on exactly its quantity and its price")
    void a_position_value_depends_on_quantity_and_price() {
        DependencyGraph graph = graphFor(fundHolding(TestMarket.US_STOCK, "10", List.of()), List.of());

        assertThat(graph.dependenciesOf(new PositionValue(ACCOUNT, TestMarket.US_STOCK)))
                .containsExactlyInAnyOrder(
                        new QuantityInput(ACCOUNT, TestMarket.US_STOCK),
                        new PriceInput(TestMarket.US_STOCK));
    }

    @Test
    @DisplayName("a foreign currency position introduces an fx rate dependency")
    void a_foreign_position_depends_on_a_rate() {
        DependencyGraph graph = graphFor(fundHolding(TestMarket.EU_STOCK, "10", List.of()), List.of());

        assertThat(graph.dependenciesOf(new PortfolioTotal()))
                .contains(new RateInput(TestMarket.EUR, TestMarket.USD));
    }

    @Test
    @DisplayName("a base currency position needs no rate at all")
    void a_base_currency_position_needs_no_rate() {
        DependencyGraph graph = graphFor(fundHolding(TestMarket.US_STOCK, "10", List.of()), List.of());

        assertThat(graph.nodes()).noneMatch(RateInput.class::isInstance);
    }

    @Test
    @DisplayName("the base currency gets no hedge target, a foreign currency does")
    void hedge_targets_exist_only_for_foreign_currencies() {
        DependencyGraph graph = graphFor(fundHolding(TestMarket.EU_STOCK, "10", List.of()), List.of());

        assertThat(graph.nodes()).contains(new HedgeTarget(TestMarket.EUR));
        assertThat(graph.nodes()).doesNotContain(new HedgeTarget(TestMarket.USD));
    }

    @Test
    @DisplayName("a security with an exposure mapping feeds every mapped currency")
    void a_mapped_security_feeds_several_currencies() {
        DependencyGraph graph = graphFor(fundHolding(TestMarket.GLOBAL_ETF, "100", List.of()), List.of());
        PositionValue value = new PositionValue(ACCOUNT, TestMarket.GLOBAL_ETF);

        assertThat(graph.dependenciesOf(new CurrencyTotal(TestMarket.USD))).contains(value);
        assertThat(graph.dependenciesOf(new CurrencyTotal(TestMarket.EUR))).contains(value);
        assertThat(graph.dependenciesOf(new CurrencyTotal(TestMarket.JPY))).contains(value);
    }

    @Test
    @DisplayName("a live forward enters the graph and a matured one does not")
    void only_live_forwards_enter_the_graph() {
        Forward live = new Forward(
                ForwardId.of("FWD-LIVE"),
                Money.of("500.00", "EUR"),
                Money.of("550.00", "USD"),
                TODAY.plusMonths(3));
        Forward matured = new Forward(
                ForwardId.of("FWD-OLD"),
                Money.of("500.00", "EUR"),
                Money.of("550.00", "USD"),
                TODAY.minusDays(1));
        Fund fund = fundHolding(TestMarket.EU_STOCK, "10", List.of());

        assertThat(graphFor(fund, List.of(live)).nodes())
                .contains(new ForwardInput(ForwardId.of("FWD-LIVE"), TestMarket.EUR));
        assertThat(graphFor(fund, List.of(matured)).nodes())
                .doesNotContain(new ForwardInput(ForwardId.of("FWD-OLD"), TestMarket.EUR));
    }

    @Test
    @DisplayName("the graph is acyclic and every dependency is ordered before its dependent")
    void the_graph_is_a_dag() {
        DependencyGraph graph = graphFor(
                fundHolding(TestMarket.GLOBAL_ETF, "100", List.of(Money.of("250.00", "EUR"))), List.of());

        List<CalculationNode> ordered = graph.topologicalOrder();

        assertThat(ordered).hasSize(graph.nodeCount());
        for (int index = 0; index < ordered.size(); index++) {
            for (CalculationNode dependency : graph.dependenciesOf(ordered.get(index))) {
                assertThat(ordered.indexOf(dependency)).isLessThan(index);
            }
        }
    }

    @Test
    @DisplayName("building the same fund twice produces the same graph in the same order")
    void graph_construction_is_deterministic() {
        Fund fund = fundHolding(TestMarket.GLOBAL_ETF, "100", List.of(Money.of("250.00", "EUR")));

        assertThat(graphFor(fund, List.of()).topologicalOrder())
                .isEqualTo(graphFor(fund, List.of()).topologicalOrder());
    }

    @Test
    @DisplayName("the reverse index has exactly as many edges as the forward graph")
    void the_reverse_index_preserves_every_edge() {
        DependencyGraph graph = graphFor(
                fundHolding(TestMarket.GLOBAL_ETF, "100", List.of(Money.of("250.00", "EUR"))), List.of());

        ReverseDependencyIndex index = ReverseDependencyIndex.from(graph);

        assertThat(index.edgeCount()).isEqualTo(graph.edgeCount());
        assertThat(index.nodeCount()).isEqualTo(graph.nodeCount());
    }

    @Test
    @DisplayName("a price input's dependents include the position value that reads it")
    void a_price_input_is_read_by_its_position_value() {
        DependencyGraph graph = graphFor(fundHolding(TestMarket.EU_STOCK, "10", List.of()), List.of());

        assertThat(ReverseDependencyIndex.from(graph).dependentsOf(new PriceInput(TestMarket.EU_STOCK)))
                .contains(new PositionValue(ACCOUNT, TestMarket.EU_STOCK));
    }
}
