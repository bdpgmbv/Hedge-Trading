package dev.hedge.domain.temporal;

import java.time.Instant;
import java.util.Objects;

/**
 * A value, when it was true, and when we found out.
 *
 * <p>The two timestamps are the heart of the platform and are never the same question:
 *
 * <ul>
 *   <li>{@code effectiveAt} — the business moment the value describes
 *   <li>{@code receivedAt} — the moment this system learned it
 * </ul>
 *
 * <p>A custodian sending a correction on Wednesday for Monday produces an observation effective
 * Monday and received Wednesday. Monday's exposure changes, yet a report published on Tuesday was
 * right given what was known then. Both must stay reproducible, which is only possible if both
 * times are recorded.
 *
 * @param <T> the canonical value, such as a price or a position
 */
public record Observation<T>(T value, Instant effectiveAt, Instant receivedAt, Provenance from) {

    public Observation {
        Objects.requireNonNull(value, "an observation needs a value");
        Objects.requireNonNull(effectiveAt, "an observation needs an effective time");
        Objects.requireNonNull(receivedAt, "an observation needs a received time");
        Objects.requireNonNull(from, "an observation needs provenance");
        if (receivedAt.isBefore(effectiveAt.minusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            throw new IllegalArgumentException("an observation cannot be received meaningfully before"
                    + " it was effective: effectiveAt=" + effectiveAt + " receivedAt=" + receivedAt);
        }
    }

    /** Providers and this system do not share a clock; a little skew is tolerated, a lot is a bug. */
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;

    /** True when this observation was already known at the given moment. */
    public boolean wasKnownAt(Instant knowledgeTime) {
        Objects.requireNonNull(knowledgeTime, "a knowledge time is required");
        return !receivedAt.isAfter(knowledgeTime);
    }

    /** True when this observation describes a business moment at or before the one asked for. */
    public boolean appliesAt(Instant businessTime) {
        Objects.requireNonNull(businessTime, "a business time is required");
        return !effectiveAt.isAfter(businessTime);
    }

    public <R> Observation<R> withValue(R replacement) {
        return new Observation<>(replacement, effectiveAt, receivedAt, from);
    }
}
