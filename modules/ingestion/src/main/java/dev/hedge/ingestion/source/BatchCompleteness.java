package dev.hedge.ingestion.source;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether the batch we parsed is the batch the provider sent.
 *
 * <p>A file transfer can truncate, and a truncated position file looks exactly like a portfolio
 * that sold everything after line 4,000. Comparing a declared count from the trailer against what
 * was parsed is the cheapest control that catches it.
 */
public record BatchCompleteness(Optional<Integer> declaredCount, int parsedCount, Optional<String> problem) {

    public BatchCompleteness {
        Objects.requireNonNull(declaredCount, "declaredCount must be present or empty, never null");
        Objects.requireNonNull(problem, "problem must be present or empty, never null");
        if (parsedCount < 0) {
            throw new IllegalArgumentException("parsedCount must not be negative, was " + parsedCount);
        }
    }

    public static BatchCompleteness verified(int declared, int parsed) {
        if (declared != parsed) {
            return new BatchCompleteness(
                    Optional.of(declared),
                    parsed,
                    Optional.of("the trailer declared " + declared + " records but " + parsed
                            + " were parsed, so the batch is incomplete or malformed"));
        }
        return new BatchCompleteness(Optional.of(declared), parsed, Optional.empty());
    }

    /** For providers whose format carries no trailer; completeness simply cannot be checked. */
    public static BatchCompleteness unverifiable(int parsed) {
        return new BatchCompleteness(Optional.empty(), parsed, Optional.empty());
    }

    public static BatchCompleteness malformed(int parsed, String problem) {
        return new BatchCompleteness(Optional.empty(), parsed, Optional.of(problem));
    }

    public boolean isComplete() {
        return problem.isEmpty();
    }
}
