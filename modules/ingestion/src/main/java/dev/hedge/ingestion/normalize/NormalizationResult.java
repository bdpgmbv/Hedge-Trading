package dev.hedge.ingestion.normalize;

import java.util.Objects;

/**
 * What a normalizer made of one raw record.
 *
 * <p>There is no silent third option. A record that cannot be understood is {@link Rejected} with
 * a reason, never dropped: a position that vanishes understates exposure, and nothing downstream
 * can notice an absence.
 */
public sealed interface NormalizationResult<T> {

    record Accepted<T>(T value, String normalizationVersion) implements NormalizationResult<T> {
        public Accepted {
            Objects.requireNonNull(value, "an accepted result needs a value");
            Objects.requireNonNull(normalizationVersion, "an accepted result needs its rules version");
        }
    }

    record Rejected<T>(String reason) implements NormalizationResult<T> {
        public Rejected {
            Objects.requireNonNull(reason, "a rejection needs a reason");
        }
    }

    default boolean isAccepted() {
        return this instanceof Accepted<T>;
    }
}
