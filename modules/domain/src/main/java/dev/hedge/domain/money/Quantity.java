package dev.hedge.domain.money;

import java.math.BigDecimal;
import java.util.Objects;
public record Quantity(BigDecimal value) {

    public Quantity {
        Objects.requireNonNull(value, "a quantity must not be null");
        value = value.stripTrailingZeros();
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    public boolean isShort() {
        return value.signum() < 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}