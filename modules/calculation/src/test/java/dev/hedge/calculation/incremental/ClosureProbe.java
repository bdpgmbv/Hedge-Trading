package dev.hedge.calculation.incremental;

import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.generator.GeneratedMarket;
import dev.hedge.generator.MarketGenerator;
import dev.hedge.generator.PortfolioGenerator;
import dev.hedge.generator.WorkloadSpec;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Closure size broken down by the kind of input that changed. A single average over all inputs is
 * misleading, because the fan-out of a price is nothing like the fan-out of a quantity.
 */
class ClosureProbe {

    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 15);

    @Test
    void run() {
        measure("1k  ", new WorkloadSpec(42, 1, 20, 50, 200, List.of("USD", "EUR", "GBP", "JPY")));
        measure("10k ", new WorkloadSpec(42, 1, 100, 100, 200, List.of("USD", "EUR", "GBP", "JPY")));
        measure("50k ", new WorkloadSpec(42, 1, 500, 100, 200, List.of("USD", "EUR", "GBP", "JPY")));
        measure("100k", new WorkloadSpec(42, 1, 1000, 100, 200, List.of("USD", "EUR", "GBP", "JPY")));
    }

    private static void measure(String label, WorkloadSpec spec) {
        Fund fund = new PortfolioGenerator().generate(spec).funds().get(0);
        GeneratedMarket generated = new MarketGenerator().generate(spec);
        MarketSnapshot market = MarketSnapshot.builder()
                .securities(generated.securities())
                .prices(generated.prices())
                .rates(generated.rates())
                .build();

        DependencyGraph graph = DependencyGraph.of(fund, generated.forwards(), market, AS_OF);
        ReverseDependencyIndex index = ReverseDependencyIndex.from(graph);

        System.out.printf(
                "PROBE %s positions=%-7d nodes=%-7d  price%s  quantity%s  cash%s%n",
                label,
                fund.positionCount(),
                graph.nodeCount(),
                stats(graph, index, CalculationNode.PriceInput.class),
                stats(graph, index, CalculationNode.QuantityInput.class),
                stats(graph, index, CalculationNode.CashInput.class));
    }

    private static String stats(
            DependencyGraph graph, ReverseDependencyIndex index, Class<? extends CalculationNode> type) {
        List<CalculationNode> inputs =
                graph.nodes().stream().filter(type::isInstance).toList();
        if (inputs.isEmpty()) {
            return "[none]";
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        long sum = 0;
        for (CalculationNode input : inputs) {
            int size = AffectedClosure.of(Set.of(input), graph, index).size();
            min = Math.min(min, size);
            max = Math.max(max, size);
            sum += size;
        }
        return String.format("[n=%d avg=%.0f min=%d max=%d]", inputs.size(), (double) sum / inputs.size(), min, max);
    }
}
