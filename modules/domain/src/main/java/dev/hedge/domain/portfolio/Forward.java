package dev.hedge.domain.portfolio;

import dev.hedge.domain.id.ForwardId;
import dev.hedge.domain.money.Money;
import java.time.LocalDate;
import java.util.Objects;
public record Forward(ForwardId id, Money sell, Money buy, LocalDate maturity) {

    public Forward {
        Objects.requireNonNull(id, "a forward must have an id");
        Objects.requireNonNull(sell, "a forward must have a sell leg");
        Objects.requireNonNull(buy, "a forward must have a buy leg");
        Objects.requireNonNull(maturity, "a forward must have a maturity date");
        if (sell.currency().equals(buy.currency())) {
            throw new IllegalArgumentException("a forward must exchange two different currencies,"
                    + " but both legs were " + sell.currency().getCurrencyCode());
        }
        if (sell.isNegative() || buy.isNegative()) {
            throw new IllegalArgumentException("both forward legs must be positive amounts");
        }
        if (sell.isZero() || buy.isZero()) {
            throw new IllegalArgumentException("neither forward leg may be zero");
        }
    }

    public boolean isLiveOn(LocalDate date) {
        Objects.requireNonNull(date, "a date is required");
        return !maturity.isBefore(date);
    }

    @Override
    public String toString() {
        return id + " sell " + sell + " buy " + buy + " matures " + maturity;
    }
}