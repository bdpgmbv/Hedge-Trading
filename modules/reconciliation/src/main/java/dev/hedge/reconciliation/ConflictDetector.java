package dev.hedge.reconciliation;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.SecurityId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the case where nothing in the data decides who is right.
 *
 * <p>A conflict is not a difference. Two sources at different standing disagreeing is settled by
 * the ranking. Two sources of <em>equal</em> standing disagreeing is a question for a person, and
 * the correct behaviour is to say so rather than pick the first, the largest, or the newest.
 * Quietly choosing produces a number nobody can defend when a client asks.
 */
public final class ConflictDetector {

    public Optional<Break> detect(
            AccountId account,
            SecurityId security,
            Map<String, BigDecimal> valuesByEquallyRankedSource,
            Tolerance tolerance) {
        Objects.requireNonNull(account, "an account is required");
        Objects.requireNonNull(security, "a security is required");
        Objects.requireNonNull(valuesByEquallyRankedSource, "values must not be null");
        Objects.requireNonNull(tolerance, "a tolerance is required");

        if (valuesByEquallyRankedSource.size() < 2) {
            return Optional.empty();
        }
        List<Map.Entry<String, BigDecimal>> entries =
                new ArrayList<>(valuesByEquallyRankedSource.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        BigDecimal lowest = entries.get(0).getValue();
        BigDecimal highest = lowest;
        for (Map.Entry<String, BigDecimal> entry : entries) {
            lowest = lowest.min(entry.getValue());
            highest = highest.max(entry.getValue());
        }
        if (tolerance.accepts(lowest, highest)) {
            return Optional.empty();
        }

        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : entries) {
            if (detail.length() > 0) {
                detail.append(", ");
            }
            detail.append(entry.getKey()).append('=').append(entry.getValue().toPlainString());
        }
        return Optional.of(new Break(
                BreakType.CONFLICT,
                account,
                security,
                Optional.of(lowest),
                Optional.of(highest),
                Optional.of(highest.subtract(lowest)),
                Optional.empty(),
                Optional.empty(),
                "sources of equal standing disagree and nothing decides between them: " + detail));
    }
}
