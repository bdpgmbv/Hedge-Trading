package dev.hedge.reconciliation;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The outcome of comparing two sides, with the counts a morning report is built from. */
public record ReconciliationRun(
        String leftSource,
        String rightSource,
        List<Break> breaks,
        Map<BreakType, Integer> counts,
        int comparisons,
        Duration took) {

    public ReconciliationRun {
        Objects.requireNonNull(leftSource, "a run needs a left source");
        Objects.requireNonNull(rightSource, "a run needs a right source");
        breaks = List.copyOf(Objects.requireNonNull(breaks, "breaks must not be null"));
        counts = Map.copyOf(Objects.requireNonNull(counts, "counts must not be null"));
        Objects.requireNonNull(took, "a run needs a duration");
    }

    public int count(BreakType type) {
        return counts.getOrDefault(type, 0);
    }

    public List<Break> needingAttention() {
        return breaks.stream().filter(Break::needsAttention).toList();
    }

    static Map<BreakType, Integer> tally(List<Break> breaks) {
        Map<BreakType, Integer> counts = new EnumMap<>(BreakType.class);
        for (Break found : breaks) {
            counts.merge(found.type(), 1, Integer::sum);
        }
        return counts;
    }
}
