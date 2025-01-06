package dev.hedge.calculation.incremental;

import dev.hedge.calculation.CalculationException;
import dev.hedge.calculation.MarketSnapshot;
import dev.hedge.calculation.incremental.CalculationNode.CashInput;
import dev.hedge.calculation.incremental.CalculationNode.CurrencyTotal;
import dev.hedge.calculation.incremental.CalculationNode.ForwardInput;
import dev.hedge.calculation.incremental.CalculationNode.HedgeTarget;
import dev.hedge.calculation.incremental.CalculationNode.PortfolioTotal;
import dev.hedge.calculation.incremental.CalculationNode.PositionValue;
import dev.hedge.calculation.incremental.CalculationNode.PriceInput;
import dev.hedge.calculation.incremental.CalculationNode.QuantityInput;
import dev.hedge.calculation.incremental.CalculationNode.RateInput;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.ExposureWeight;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The forward dependency graph: for each node, the nodes it reads. Built once from a fund and its
 * market, then queried many times. Construction is deterministic, so the same fund always produces
 * the same graph in the same order.
 */
public final class DependencyGraph {

    private final Map<CalculationNode, Set<CalculationNode>> dependencies;
    private final List<CalculationNode> ordered;
    private final Map<CalculationNode, Integer> ranks;

    private DependencyGraph(Map<CalculationNode, Set<CalculationNode>> dependencies) {
        this.dependencies = dependencies;
        this.ordered = List.copyOf(sortTopologically(dependencies));
        Map<CalculationNode, Integer> ranks = new HashMap<>(this.ordered.size());
        for (int rank = 0; rank < this.ordered.size(); rank++) {
            ranks.put(this.ordered.get(rank), rank);
        }
        this.ranks = ranks;
    }

    /**
     * This node's position in a global topological order. Sorting any subset of nodes by rank
     * yields a valid evaluation order for that subset, which is what lets a closure be ordered in
     * O(k log k) instead of re-deriving in-degrees over nodes that may each have thousands of
     * dependencies.
     */
    public int rankOf(CalculationNode node) {
        Integer rank = ranks.get(Objects.requireNonNull(node, "a node is required"));
        if (rank == null) {
            throw new CalculationException("this graph has no node " + node.key());
        }
        return rank;
    }

    public static DependencyGraph of(
            Fund fund, List<Forward> forwards, MarketSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(fund, "a fund is required");
        Objects.requireNonNull(forwards, "forwards must not be null");
        Objects.requireNonNull(market, "a market snapshot is required");
        Objects.requireNonNull(asOf, "a valuation date is required");

        Currency base = fund.baseCurrency();
        Map<CalculationNode, Set<CalculationNode>> edges = new LinkedHashMap<>();
        CalculationNode portfolioTotal = new PortfolioTotal();

        for (Account account : fund.accounts()) {
            for (Position position : account.positions()) {
                Money price = market.price(position.securityId());
                Currency priced = price.currency();
                CalculationNode value = new PositionValue(account.id(), position.securityId());

                link(edges, value, new QuantityInput(account.id(), position.securityId()));
                link(edges, value, new PriceInput(position.securityId()));

                for (ExposureWeight weight :
                        market.security(position.securityId()).effectiveExposures()) {
                    CalculationNode total = new CurrencyTotal(weight.currency());
                    link(edges, total, value);
                    if (!priced.equals(weight.currency())) {
                        link(edges, total, new RateInput(priced, weight.currency()));
                    }
                }

                link(edges, portfolioTotal, value);
                if (!priced.equals(base)) {
                    link(edges, portfolioTotal, new RateInput(priced, base));
                }
            }

            for (Money cash : account.cashBalances()) {
                CalculationNode input = new CashInput(account.id(), cash.currency());
                link(edges, new CurrencyTotal(cash.currency()), input);
                link(edges, portfolioTotal, input);
                if (!cash.currency().equals(base)) {
                    link(edges, portfolioTotal, new RateInput(cash.currency(), base));
                }
            }
        }

        for (Forward forward : forwards) {
            if (!forward.isLiveOn(asOf)) {
                continue;
            }
            link(
                    edges,
                    new CurrencyTotal(forward.sell().currency()),
                    new ForwardInput(forward.id(), forward.sell().currency()));
            link(
                    edges,
                    new CurrencyTotal(forward.buy().currency()),
                    new ForwardInput(forward.id(), forward.buy().currency()));
        }

        // A hedge target exists for every currency the fund is exposed to except its own base.
        for (CalculationNode node : List.copyOf(edges.keySet())) {
            if (node instanceof CurrencyTotal total && !total.currency().equals(base)) {
                link(edges, new HedgeTarget(total.currency()), total);
            }
        }

        return new DependencyGraph(edges);
    }

    private static void link(
            Map<CalculationNode, Set<CalculationNode>> edges,
            CalculationNode dependent,
            CalculationNode dependency) {
        edges.computeIfAbsent(dependent, key -> new LinkedHashSet<>()).add(dependency);
        edges.computeIfAbsent(dependency, key -> new LinkedHashSet<>());
    }

    public Set<CalculationNode> nodes() {
        return Collections.unmodifiableSet(dependencies.keySet());
    }

    public Set<CalculationNode> dependenciesOf(CalculationNode node) {
        return Collections.unmodifiableSet(
                dependencies.getOrDefault(Objects.requireNonNull(node, "a node is required"), Set.of()));
    }

    public int nodeCount() {
        return dependencies.size();
    }

    public int edgeCount() {
        return dependencies.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Every node in an order where dependencies come before the nodes that read them. Also the
     * cycle check: a calculation graph with a cycle has no valid evaluation order, and a cycle here
     * would mean an exposure that depends on itself.
     */
    public List<CalculationNode> topologicalOrder() {
        return ordered;
    }

    private static List<CalculationNode> sortTopologically(
            Map<CalculationNode, Set<CalculationNode>> dependencies) {
        Map<CalculationNode, Integer> remaining = new HashMap<>();
        Map<CalculationNode, List<CalculationNode>> dependents = new HashMap<>();
        for (Map.Entry<CalculationNode, Set<CalculationNode>> entry : dependencies.entrySet()) {
            remaining.put(entry.getKey(), entry.getValue().size());
            for (CalculationNode dependency : entry.getValue()) {
                dependents.computeIfAbsent(dependency, key -> new ArrayList<>()).add(entry.getKey());
            }
        }

        List<CalculationNode> ready = new ArrayList<>();
        remaining.forEach((node, count) -> {
            if (count == 0) {
                ready.add(node);
            }
        });
        ready.sort(Comparator.comparing(CalculationNode::key));

        Deque<CalculationNode> queue = new ArrayDeque<>(ready);
        List<CalculationNode> ordered = new ArrayList<>(dependencies.size());
        while (!queue.isEmpty()) {
            CalculationNode node = queue.poll();
            ordered.add(node);
            List<CalculationNode> next = new ArrayList<>(dependents.getOrDefault(node, List.of()));
            next.sort(Comparator.comparing(CalculationNode::key));
            for (CalculationNode dependent : next) {
                if (remaining.merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (ordered.size() != dependencies.size()) {
            throw new CalculationException("the dependency graph contains a cycle: ordered "
                    + ordered.size() + " of " + dependencies.size() + " nodes");
        }
        return ordered;
    }
}
