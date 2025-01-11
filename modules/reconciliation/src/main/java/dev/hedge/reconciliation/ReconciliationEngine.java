package dev.hedge.reconciliation;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares what two sources say about the same portfolio.
 *
 * <p><strong>Indexed, not nested.</strong> The obvious implementation searches the right side for
 * each row of the left, which is O(N × M): at fifty thousand positions a side that is two and a
 * half billion comparisons. Building a hash index of one side first and walking the other once is
 * O(N + M), and the difference is the difference between a reconciliation that finishes before
 * the desk opens and one that does not.
 *
 * <p>Takes positions as arguments and reads nothing. That is why this module cannot see ingestion:
 * a reconciliation engine coupled to file formats could never compare a stream to a file.
 */
public final class ReconciliationEngine {

    /** Two positions are the same holding when they are the same security in the same account. */
    private record Holding(AccountId account, SecurityId security) {
        static Holding of(Observation<Position> observation) {
            return new Holding(observation.value().accountId(), observation.value().securityId());
        }
    }

    public ReconciliationRun reconcile(
            String leftSource,
            List<Observation<Position>> left,
            String rightSource,
            List<Observation<Position>> right,
            Tolerance tolerance) {
        Objects.requireNonNull(leftSource, "the left source needs a name");
        Objects.requireNonNull(rightSource, "the right source needs a name");
        Objects.requireNonNull(left, "the left side must not be null");
        Objects.requireNonNull(right, "the right side must not be null");
        Objects.requireNonNull(tolerance, "a tolerance is required");

        long startedAt = System.nanoTime();

        // One pass to index, one pass to compare. Nothing searches.
        Map<Holding, Observation<Position>> leftIndex = new HashMap<>(left.size() * 2);
        for (Observation<Position> observation : left) {
            leftIndex.put(Holding.of(observation), observation);
        }

        List<Break> breaks = new ArrayList<>();
        int comparisons = 0;

        for (Observation<Position> rightSide : right) {
            comparisons++;
            Holding holding = Holding.of(rightSide);
            Observation<Position> leftSide = leftIndex.remove(holding);
            if (leftSide == null) {
                breaks.add(added(holding, rightSide, rightSource, leftSource));
                continue;
            }
            BigDecimal leftQuantity = leftSide.value().quantity().value();
            BigDecimal rightQuantity = rightSide.value().quantity().value();
            breaks.add(tolerance.accepts(leftQuantity, rightQuantity)
                    ? matched(holding, leftSide, rightSide)
                    : changed(holding, leftSide, rightSide, leftSource, rightSource));
        }

        // Whatever the walk did not remove, the right side never mentioned.
        for (Map.Entry<Holding, Observation<Position>> remaining : leftIndex.entrySet()) {
            comparisons++;
            breaks.add(missing(remaining.getKey(), remaining.getValue(), leftSource, rightSource));
        }

        breaks.sort(Comparator.comparing((Break found) -> found.type().ordinal())
                .thenComparing(found -> found.account().value())
                .thenComparing(found -> found.security().value()));

        return new ReconciliationRun(
                leftSource,
                rightSource,
                breaks,
                ReconciliationRun.tally(breaks),
                comparisons,
                Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static Break matched(
            Holding holding, Observation<Position> left, Observation<Position> right) {
        return new Break(
                BreakType.MATCH,
                holding.account(),
                holding.security(),
                Optional.of(left.value().quantity().value()),
                Optional.of(right.value().quantity().value()),
                Optional.of(BigDecimal.ZERO),
                Optional.of(left.from().observation()),
                Optional.of(right.from().observation()),
                "both sources agree within tolerance");
    }

    private static Break changed(
            Holding holding,
            Observation<Position> left,
            Observation<Position> right,
            String leftSource,
            String rightSource) {
        BigDecimal leftQuantity = left.value().quantity().value();
        BigDecimal rightQuantity = right.value().quantity().value();
        return new Break(
                BreakType.CHANGED,
                holding.account(),
                holding.security(),
                Optional.of(leftQuantity),
                Optional.of(rightQuantity),
                Optional.of(rightQuantity.subtract(leftQuantity)),
                Optional.of(left.from().observation()),
                Optional.of(right.from().observation()),
                leftSource + " says " + leftQuantity.toPlainString() + " and " + rightSource
                        + " says " + rightQuantity.toPlainString());
    }

    private static Break added(
            Holding holding, Observation<Position> right, String rightSource, String leftSource) {
        return new Break(
                BreakType.ADDED,
                holding.account(),
                holding.security(),
                Optional.empty(),
                Optional.of(right.value().quantity().value()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(right.from().observation()),
                rightSource + " reports this holding and " + leftSource + " has never mentioned it");
    }

    private static Break missing(
            Holding holding, Observation<Position> left, String leftSource, String rightSource) {
        return new Break(
                BreakType.MISSING,
                holding.account(),
                holding.security(),
                Optional.of(left.value().quantity().value()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(left.from().observation()),
                Optional.empty(),
                leftSource + " reports this holding and " + rightSource + " does not");
    }
}
