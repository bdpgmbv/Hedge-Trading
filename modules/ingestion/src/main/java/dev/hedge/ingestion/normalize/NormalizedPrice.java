package dev.hedge.ingestion.normalize;

import dev.hedge.ingestion.source.IdentifierKind;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record NormalizedPrice(
        IdentifierKind identifierKind, String identifier, BigDecimal amount, Currency currency) {

    public NormalizedPrice {
        Objects.requireNonNull(identifierKind, "a normalized price needs an identifier kind");
        Objects.requireNonNull(identifier, "a normalized price needs an identifier");
        Objects.requireNonNull(amount, "a normalized price needs an amount");
        Objects.requireNonNull(currency, "a normalized price needs a currency");
    }
}
