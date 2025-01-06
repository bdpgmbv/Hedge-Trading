package dev.hedge.domain.exposure;

import java.math.BigDecimal;
import java.util.Objects;
public record HedgePolicy(BigDecimal targetRatio, BigDecimal tolerance) {

    public HedgePolicy {
        Objects.requireNonNull(targetRatio, "a hedge policy must have a target ratio");
        Objects.requireNonNull(tolerance, "a hedge policy must have a tolerance");
        requireRatio(targetRatio, "targetRatio");
        requireRatio(tolerance, "tolerance");
    }

    public static HedgePolicy of(String targetRatio, String tolerance) {
        return new HedgePolicy(new BigDecimal(targetRatio), new BigDecimal(tolerance));
    }

    public static HedgePolicy fullyHedged() {
        return new HedgePolicy(BigDecimal.ONE, new BigDecimal("0.01"));
    }

    public static HedgePolicy unhedged() {
        return new HedgePolicy(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static void requireRatio(BigDecimal value, String name) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and 1, was " + value.toPlainString());
        }
    }
}