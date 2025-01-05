package dev.hedge.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Security(SecurityId id, Currency denomination, List<ExposureWeight> exposures) {

    public Security {
        Objects.requireNonNull(id, "a security must have an id");
        Objects.requireNonNull(denomination, "a security must have a denomination currency");
        exposures = List.copyOf(Objects.requireNonNull(exposures, "exposures must not be null"));
        requireOneWeightPerCurrency(id, exposures);
        requireWeightsSumToOne(id, exposures);
    }

    public static Security denominatedIn(SecurityId id, String currencyCode) {
        return new Security(id, Currency.getInstance(currencyCode), List.of());
    }

    /**
     * The weights to apply when attributing this security's value to currencies. A security with no
     * explicit mapping is exposed entirely to the currency it is denominated in.
     */
    public List<ExposureWeight> effectiveExposures() {
        return exposures.isEmpty()
                ? List.of(new ExposureWeight(denomination, BigDecimal.ONE))
                : exposures;
    }

    private static void requireOneWeightPerCurrency(SecurityId id, List<ExposureWeight> exposures) {
        Set<Currency> seen = new HashSet<>();
        for (ExposureWeight weight : exposures) {
            if (!seen.add(weight.currency())) {
                throw new IllegalArgumentException("security " + id + " maps "
                        + weight.currency().getCurrencyCode() + " exposure twice");
            }
        }
    }

    private static void requireWeightsSumToOne(SecurityId id, List<ExposureWeight> exposures) {
        if (exposures.isEmpty()) {
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ExposureWeight weight : exposures) {
            total = total.add(weight.weight());
        }
        if (total.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("the exposure weights of security " + id
                    + " must sum to exactly 1, but summed to " + total.toPlainString());
        }
    }
}