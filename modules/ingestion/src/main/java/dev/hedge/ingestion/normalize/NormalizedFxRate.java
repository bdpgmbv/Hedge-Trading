package dev.hedge.ingestion.normalize;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record NormalizedFxRate(Currency base, Currency quote, BigDecimal rate) {

    public NormalizedFxRate {
        Objects.requireNonNull(base, "a normalized rate needs a base currency");
        Objects.requireNonNull(quote, "a normalized rate needs a quote currency");
        Objects.requireNonNull(rate, "a normalized rate needs a rate");
    }
}
