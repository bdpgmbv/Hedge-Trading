package dev.hedge.domain.money;

import dev.hedge.domain.id.SecurityId;
import java.util.Objects;
public record Price(SecurityId securityId, Money value) {

    public Price {
        Objects.requireNonNull(securityId, "a price must refer to a security");
        Objects.requireNonNull(value, "a price must have a value");
        if (value.isNegative()) {
            throw new IllegalArgumentException("a price must not be negative, was " + value);
        }
    }

    public static Price of(String securityId, String amount, String currencyCode) {
        return new Price(SecurityId.of(securityId), Money.of(amount, currencyCode));
    }

    @Override
    public String toString() {
        return securityId + " @ " + value;
    }
}