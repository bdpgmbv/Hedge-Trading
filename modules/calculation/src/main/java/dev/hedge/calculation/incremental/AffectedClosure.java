package dev.hedge.calculation.incremental;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The derived nodes reachable from a set of changed inputs, in an order where every node comes
 * after the affected nodes it reads.
 *
 * <p>This is where the saving of the whole phase is decided. The engine's cost is the size of this
 * list, not the size of the graph, so {@link #size()} is the number to measure and to watch in
 * production: if the closure approaches the node count, incremental evaluation has degenerated into
 * full recalculation and is only adding overhead.
 */
public final class AffectedClosure {

    private final List<CalculationNode> ordered;

    private AffectedClosure(List<CalculationNode> ordered) {
        this.ordered = List.copyOf(ordered);
    }

    public static AffectedClosure of(
            Set<? extends CalculationNode> changed,
            DependencyGraph graph,
            ReverseDependencyIndex index) {
        Objects.requireNonNull(changed, "the changed nodes must not be null");
        Objects.requireNonNull(graph, "a dependency graph is required");
        Objects.requireNonNull(index, "a reverse index is required");

        // Breadth-first over "who reads me", collecting only the nodes that get recomputed.
        Set<CalculationNode> reached = new LinkedHashSet<>();
        Deque<CalculationNode> queue = new ArrayDeque<>();
        List<CalculationNode> seeds = new ArrayList<>(changed);
        seeds.sort(Comparator.comparing(CalculationNode::key));
        queue.addAll(seeds);

        while (!queue.isEmpty()) {
            CalculationNode node = queue.poll();
            List<CalculationNode> next = new ArrayList<>(index.dependentsOf(node));
            next.sort(Comparator.comparing(CalculationNode::key));
            for (CalculationNode dependent : next) {
                if (reached.add(dependent)) {
                    queue.add(dependent);
                }
            }
        }

        return new AffectedClosure(order(reached, graph));
    }

    /**
     * Orders the affected nodes by their precomputed global topological rank.
     *
     * <p>The obvious implementation re-derives in-degrees inside the affected subgraph, but that
     * reads every dependency of every affected node. An aggregate such as the portfolio total has
     * one dependency per position, so ordering a four-node closure cost ten thousand lookups and
     * ate the entire saving. Sorting by a rank computed once when the graph was built is O(k log k)
     * in the closure size and touches nothing outside it.
     */
    private static List<CalculationNode> order(Set<CalculationNode> affected, DependencyGraph graph) {
        List<CalculationNode> ordered = new ArrayList<>(affected);
        ordered.sort(Comparator.comparingInt(graph::rankOf));
        return ordered;
    }

    public List<CalculationNode> nodes() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }
}
