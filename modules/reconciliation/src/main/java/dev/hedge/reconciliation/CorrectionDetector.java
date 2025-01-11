package dev.hedge.reconciliation;

import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tells apart the three things that look identical in a list of statements from one source.
 *
 * <p>Given everything a provider ever said about one holding:
 *
 * <ul>
 *   <li>the same value restated is a {@link BreakType#DUPLICATE} and must change nothing
 *   <li>a different value for the same business moment is a {@link BreakType#CORRECTION}
 *   <li>a different value for a later business moment is simply the next day's position
 * </ul>
 *
 * <p>Getting the first two confused is how a retransmission silently doubles a holding, or a
 * genuine correction is discarded as a repeat.
 */
public final class CorrectionDetector {

    public List<Break> classify(List<Observation<Position>> statementsAboutOneHolding) {
        Objects.requireNonNull(statementsAboutOneHolding, "statements must not be null");
        List<Observation<Position>> ordered = new ArrayList<>(statementsAboutOneHolding);
        ordered.sort(Comparator.comparing(Observation<Position>::receivedAt)
                .thenComparing(observation -> observation.from().observation().value()));

        List<Break> findings = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            Observation<Position> earlier = ordered.get(index - 1);
            Observation<Position> later = ordered.get(index);
            if (!later.effectiveAt().equals(earlier.effectiveAt())) {
                continue; // a different business moment is not a restatement of this one
            }
            BigDecimal before = earlier.value().quantity().value();
            BigDecimal after = later.value().quantity().value();
            boolean same = before.compareTo(after) == 0;
            findings.add(new Break(
                    same ? BreakType.DUPLICATE : BreakType.CORRECTION,
                    later.value().accountId(),
                    later.value().securityId(),
                    Optional.of(before),
                    Optional.of(after),
                    Optional.of(after.subtract(before)),
                    Optional.of(earlier.from().observation()),
                    Optional.of(later.from().observation()),
                    same
                            ? "the same value for the same business moment was stated again,"
                                    + " so it must have no second effect"
                            : "the value for " + later.effectiveAt() + " was restated from "
                                    + before.toPlainString() + " to " + after.toPlainString()));
        }
        return findings;
    }
}
