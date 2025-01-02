package dev.hedge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record FxRate(Currency base, Currency quote, BigDecimal rate) {

    private static final int INVERSE_SCALE = 10;

    public FxRate {
        Objects.requireNonNull(base, "a base currency must not be null");
        Objects.requireNonNull(quote, "a quote currency must not be null");
        Objects.requireNonNull(rate, "a rate must not be null");
        if (base.equals(quote)) {
            throw new IllegalArgumentException("base and quote currency must differ");
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("an fx rate must be positive, was " + rate.toPlainString());
        }
    }

    public static FxRate of(String base, String quote, String rate) {
        return new FxRate(Currency.getInstance(base), Currency.getInstance(quote), new BigDecimal(rate));
    }

    public Money convert(Money money, RoundingMode rounding) {
        if (!money.currency().equals(base)) {
            throw new IllegalArgumentException("this rate converts " + base.getCurrencyCode()
                    + " to " + quote.getCurrencyCode()
                    + ", but was given " + money.currency().getCurrencyCode());
        }
        return new Money(
                money.amount().multiply(rate).setScale(quote.getDefaultFractionDigits(), rounding), quote);
    }

    public FxRate inverse() {
        return new FxRate(quote, base, BigDecimal.ONE.divide(rate, INVERSE_SCALE, RoundingMode.HALF_UP));
    }

    @Override
    public String toString() {
        return base.getCurrencyCode() + "/" + quote.getCurrencyCode() + " " + rate.toPlainString();
    }
}