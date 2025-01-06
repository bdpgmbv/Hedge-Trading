package dev.hedge.calculation.incremental;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The graph inverted: for each node, the nodes that read it.
 *
 * <p>The forward graph answers "what does this node need", which is what evaluation uses. Change
 * propagation needs the opposite question, "who needs this node", and answering that by scanning
 * the forward graph would be O(E) per lookup. Paying O(V + E) once to invert it makes every lookup
 * O(1), and the index is built from an immutable graph so it can be shared and reused.
 */
public final class ReverseDependencyIndex {

    private final Map<CalculationNode, Set<CalculationNode>> dependents;

    private ReverseDependencyIndex(Map<CalculationNode, Set<CalculationNode>> dependents) {
        this.dependents = dependents;
    }

    public static ReverseDependencyIndex from(DependencyGraph graph) {
        Objects.requireNonNull(graph, "a dependency graph is required");
        Map<CalculationNode, Set<CalculationNode>> inverted = new LinkedHashMap<>();
        for (CalculationNode node : graph.nodes()) {
            inverted.computeIfAbsent(node, key -> new LinkedHashSet<>());
        }
        for (CalculationNode node : graph.nodes()) {
            for (CalculationNode dependency : graph.dependenciesOf(node)) {
                inverted.computeIfAbsent(dependency, key -> new LinkedHashSet<>()).add(node);
            }
        }
        return new ReverseDependencyIndex(inverted);
    }

    public Set<CalculationNode> dependentsOf(CalculationNode node) {
        return Collections.unmodifiableSet(
                dependents.getOrDefault(Objects.requireNonNull(node, "a node is required"), Set.of()));
    }

    public int nodeCount() {
        return dependents.size();
    }

    public int edgeCount() {
        return dependents.values().stream().mapToInt(Set::size).sum();
    }
}
