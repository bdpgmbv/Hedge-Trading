package dev.hedge.ingestion.identity;

import dev.hedge.ingestion.source.IdentifierKind;
import java.util.Objects;

/**
 * What came of trying to turn a provider's code into something this system knows.
 *
 * <p>There is no third case where a best guess is returned. Identifier equality is not economic
 * identity, and a system that guesses produces numbers nobody can defend.
 *
 * @param <T> the resolved identifier type
 */
public sealed interface Resolution<T> {

    record Resolved<T>(T value) implements Resolution<T> {
        public Resolved {
            Objects.requireNonNull(value, "a resolution needs a value");
        }
    }

    record Unmapped<T>(IdentifierKind kind, String identifier, String reason) implements Resolution<T> {
        public Unmapped {
            Objects.requireNonNull(kind, "an unmapped identifier needs its kind");
            Objects.requireNonNull(identifier, "an unmapped identifier needs its value");
            Objects.requireNonNull(reason, "an unmapped identifier needs a reason");
        }
    }

    default boolean isResolved() {
        return this instanceof Resolved<T>;
    }
}
