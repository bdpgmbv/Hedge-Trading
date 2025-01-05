package dev.hedge.calculation;

import dev.hedge.domain.FxRate;
import dev.hedge.domain.Money;
import dev.hedge.domain.Price;
import dev.hedge.domain.Security;
import dev.hedge.domain.SecurityId;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable view of everything the calculator needs from the market: what each security is, what
 * it costs, and how to move an amount between currencies. Built once and read many times, so every
 * lookup is a hash lookup rather than a search.
 */
public final class MarketSnapshot {

    private final Map<SecurityId, Security> securities;
    private final Map<SecurityId, Money> prices;
    private final Map<String, FxRate> rates;

    private MarketSnapshot(
            Map<SecurityId, Security> securities,
            Map<SecurityId, Money> prices,
            Map<String, FxRate> rates) {
        this.securities = Map.copyOf(securities);
        this.prices = Map.copyOf(prices);
        this.rates = Map.copyOf(rates);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Security security(SecurityId id) {
        Security security = securities.get(Objects.requireNonNull(id, "a security id is required"));
        if (security == null) {
            throw new CalculationException("no security definition for " + id);
        }
        return security;
    }

    public Money price(SecurityId id) {
        Money price = prices.get(Objects.requireNonNull(id, "a security id is required"));
        if (price == null) {
            throw new CalculationException("no price for " + id);
        }
        return price;
    }

    public Money convert(Money money, Currency target) {
        Objects.requireNonNull(money, "an amount is required");
        Objects.requireNonNull(target, "a target currency is required");
        if (money.currency().equals(target)) {
            return money;
        }
        FxRate direct = rates.get(key(money.currency(), target));
        if (direct != null) {
            return direct.convert(money, RoundingMode.HALF_UP);
        }
        FxRate reverse = rates.get(key(target, money.currency()));
        if (reverse != null) {
            return reverse.inverse().convert(money, RoundingMode.HALF_UP);
        }
        throw new CalculationException("no fx rate from " + money.currency().getCurrencyCode()
                + " to " + target.getCurrencyCode());
    }

    public int securityCount() {
        return securities.size();
    }

    public int rateCount() {
        return rates.size();
    }

    private static String key(Currency base, Currency quote) {
        return base.getCurrencyCode() + quote.getCurrencyCode();
    }

    public static final class Builder {

        private final Map<SecurityId, Security> securities = new HashMap<>();
        private final Map<SecurityId, Money> prices = new HashMap<>();
        private final Map<String, FxRate> rates = new HashMap<>();

        private Builder() {}

        public Builder security(Security security) {
            Objects.requireNonNull(security, "a security is required");
            securities.put(security.id(), security);
            return this;
        }

        public Builder securities(List<Security> values) {
            Objects.requireNonNull(values, "securities must not be null").forEach(this::security);
            return this;
        }

        public Builder price(Price price) {
            Objects.requireNonNull(price, "a price is required");
            prices.put(price.securityId(), price.value());
            return this;
        }

        public Builder prices(List<Price> values) {
            Objects.requireNonNull(values, "prices must not be null").forEach(this::price);
            return this;
        }

        public Builder rate(FxRate rate) {
            Objects.requireNonNull(rate, "an fx rate is required");
            rates.put(key(rate.base(), rate.quote()), rate);
            return this;
        }

        public Builder rates(List<FxRate> values) {
            Objects.requireNonNull(values, "rates must not be null").forEach(this::rate);
            return this;
        }

        public MarketSnapshot build() {
            return new MarketSnapshot(securities, prices, rates);
        }
    }
}