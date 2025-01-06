package dev.hedge.domain.portfolio;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
public record ExposureWeight(Currency currency, BigDecimal weight) {

    public ExposureWeight {
        Objects.requireNonNull(currency, "an exposure weight must have a currency");
        Objects.requireNonNull(weight, "an exposure weight must have a weight");
        if (weight.signum() <= 0) {
            throw new IllegalArgumentException(
                    "an exposure weight must be positive, was " + weight.toPlainString());
        }
        if (weight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "an exposure weight must not exceed 1, was " + weight.toPlainString());
        }
    }

    public static ExposureWeight of(String currencyCode, String weight) {
        return new ExposureWeight(Currency.getInstance(currencyCode), new BigDecimal(weight));
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + weight.toPlainString();
    }
}