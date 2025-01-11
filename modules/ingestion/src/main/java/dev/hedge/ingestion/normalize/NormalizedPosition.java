package dev.hedge.ingestion.normalize;

import dev.hedge.ingestion.source.IdentifierKind;
import java.math.BigDecimal;
import java.util.Objects;

/** A holding in canonical shape, still named the way the provider named it. */
public record NormalizedPosition(
        String accountCode, IdentifierKind identifierKind, String identifier, BigDecimal quantity) {

    public NormalizedPosition {
        Objects.requireNonNull(accountCode, "a normalized position needs an account code");
        Objects.requireNonNull(identifierKind, "a normalized position needs an identifier kind");
        Objects.requireNonNull(identifier, "a normalized position needs an identifier");
        Objects.requireNonNull(quantity, "a normalized position needs a quantity");
    }
}
