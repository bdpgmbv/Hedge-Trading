package dev.hedge.calculation;

import dev.hedge.domain.Account;
import dev.hedge.domain.CurrencyExposure;
import dev.hedge.domain.ExposureWeight;
import dev.hedge.domain.Forward;
import dev.hedge.domain.Fund;
import dev.hedge.domain.HedgePolicy;
import dev.hedge.domain.HedgeRequirement;
import dev.hedge.domain.Money;
import dev.hedge.domain.PortfolioExposure;
import dev.hedge.domain.Position;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The full, deliberately simple calculation. It walks every position once, attributes its value to
 * currencies, adds cash, applies live forwards, and derives the hedge requirement per currency.
 *
 * <p>This is the oracle. Every later optimisation is judged by whether it produces the same answer
 * as this class, so it is written for obviousness rather than speed.
 *
 * <p>Cost is O(P × W + F + C log C) for P positions, W exposure weights per security, F forwards and
 * C distinct currencies.
 */
public final class ExposureCalculator {

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public PortfolioExposure calculate(
            Fund fund,
            List<Forward> forwards,
            MarketSnapshot market,
            HedgePolicy policy,
            LocalDate asOf) {
        Objects.requireNonNull(fund, "a fund is required");
        Objects.requireNonNull(forwards, "forwards must not be null");
        Objects.requireNonNull(market, "a market snapshot is required");
        Objects.requireNonNull(policy, "a hedge policy is required");
        Objects.requireNonNull(asOf, "a valuation date is required");

        Currency base = fund.baseCurrency();
        Map<String, Money> fromSecurities = new HashMap<>();
        Map<String, Money> fromCash = new HashMap<>();
        Map<String, Money> fromForwards = new HashMap<>();
        Money totalValue = Money.zero(base.getCurrencyCode());

        for (Account account : fund.accounts()) {
            for (Position position : account.positions()) {
                Money value = valueOf(position, market);
                totalValue = totalValue.plus(market.convert(value, base));
                for (ExposureWeight weight : market.security(position.securityId()).effectiveExposures()) {
                    add(fromSecurities, market.convert(value, weight.currency())
                            .multipliedBy(weight.weight(), ROUNDING));
                }
            }
            for (Money cash : account.cashBalances()) {
                add(fromCash, cash);
                totalValue = totalValue.plus(market.convert(cash, base));
            }
        }

        for (Forward forward : forwards) {
            if (!forward.isLiveOn(asOf)) {
                continue;
            }
            add(fromForwards, negated(forward.sell()));
            add(fromForwards, forward.buy());
        }

        Set<String> currencies = new TreeSet<>();
        currencies.addAll(fromSecurities.keySet());
        currencies.addAll(fromCash.keySet());
        currencies.addAll(fromForwards.keySet());

        List<CurrencyExposure> exposures = new ArrayList<>(currencies.size());
        List<HedgeRequirement> hedges = new ArrayList<>(currencies.size());
        for (String code : currencies) {
            CurrencyExposure exposure = CurrencyExposure.of(
                    amountOrZero(fromSecurities, code),
                    amountOrZero(fromCash, code),
                    amountOrZero(fromForwards, code));
            exposures.add(exposure);
            if (!exposure.currency().equals(base)) {
                hedges.add(HedgeRequirement.from(exposure, policy));
            }
        }
        return new PortfolioExposure(fund.id(), base, totalValue, exposures, hedges);
    }

    private static Money valueOf(Position position, MarketSnapshot market) {
        return market.price(position.securityId()).multipliedBy(position.quantity().value(), ROUNDING);
    }

    private static void add(Map<String, Money> totals, Money amount) {
        totals.merge(amount.currency().getCurrencyCode(), amount, Money::plus);
    }

    private static Money negated(Money amount) {
        return Money.zero(amount.currency().getCurrencyCode()).minus(amount);
    }

    private static Money amountOrZero(Map<String, Money> totals, String currencyCode) {
        Money amount = totals.get(currencyCode);
        return amount != null ? amount : Money.zero(currencyCode);
    }
}