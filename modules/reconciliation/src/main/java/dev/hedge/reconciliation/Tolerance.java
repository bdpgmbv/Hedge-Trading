package dev.hedge.reconciliation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * How close two numbers have to be before the difference stops being worth a person's attention.
 *
 * <p>Without this the engine reports every rounding artefact, the break list fills with noise, and
 * within a week nobody reads it. A reconciliation nobody reads is worse than none, because it
 * looks like control.
 *
 * @param absolute a difference at or below this is ignored outright
 * @param relative a difference at or below this fraction of the larger side is ignored
 */
public record Tolerance(BigDecimal absolute, BigDecimal relative) {

    public Tolerance {
        Objects.requireNonNull(absolute, "a tolerance needs an absolute part");
        Objects.requireNonNull(relative, "a tolerance needs a relative part");
        if (absolute.signum() < 0 || relative.signum() < 0) {
            throw new IllegalArgumentException("a tolerance cannot be negative");
        }
    }

    /** Anything other than an exact match is a break. */
    public static Tolerance exact() {
        return new Tolerance(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static Tolerance of(String absolute, String relative) {
        return new Tolerance(new BigDecimal(absolute), new BigDecimal(relative));
    }

    public boolean accepts(BigDecimal left, BigDecimal right) {
        BigDecimal difference = left.subtract(right).abs();
        if (difference.compareTo(absolute) <= 0) {
            return true;
        }
        BigDecimal larger = left.abs().max(right.abs());
        if (larger.signum() == 0) {
            return difference.signum() == 0;
        }
        return difference.divide(larger, MathContext.DECIMAL64).compareTo(relative) <= 0;
    }
}
