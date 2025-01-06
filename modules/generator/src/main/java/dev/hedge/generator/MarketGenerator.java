package dev.hedge.generator;

import dev.hedge.domain.id.ForwardId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.FxRate;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Price;
import dev.hedge.domain.portfolio.Forward;
import dev.hedge.domain.portfolio.Security;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
/**
 * Produces the market data that matches a {@link WorkloadSpec}: one definition and one price for
 * every security {@link PortfolioGenerator} can reference, a rate from every currency to the base,
 * and one forward per non-base currency. Every value derives from the spec seed, so two runs of the
 * same spec produce identical market data.
 */
public final class MarketGenerator {

    private static final long SECURITY_SEED_OFFSET = 3_300_013L;
    private static final long RATE_SEED_OFFSET = 9_900_019L;
    private static final long FORWARD_SEED_OFFSET = 5_500_011L;
    private static final int RATE_SCALE = 6;
    private static final LocalDate EPOCH = LocalDate.of(2026, 1, 1);

    public GeneratedMarket generate(WorkloadSpec spec) {
        List<Currency> currencies = currencies(spec);
        Currency base = currencies.get(0);
        Random random = new Random(spec.seed() + SECURITY_SEED_OFFSET);

        List<Security> securities = new ArrayList<>(spec.securities());
        List<Price> prices = new ArrayList<>(spec.securities());
        for (int index = 0; index < spec.securities(); index++) {
            SecurityId id = SecurityId.of(String.format("SEC-%05d", index));
            Currency denomination = currencies.get(index % currencies.size());
            securities.add(Security.denominatedIn(id, denomination.getCurrencyCode()));
            prices.add(new Price(id, amount(random, denomination, 10, 500)));
        }
        return new GeneratedMarket(
                securities, prices, rates(spec, currencies, base), forwards(spec, currencies, base));
    }

    private static List<Currency> currencies(WorkloadSpec spec) {
        List<Currency> currencies = new ArrayList<>(spec.currencies().size());
        for (String code : spec.currencies()) {
            currencies.add(Currency.getInstance(code));
        }
        return currencies;
    }

    private static List<FxRate> rates(WorkloadSpec spec, List<Currency> currencies, Currency base) {
        Random random = new Random(spec.seed() + RATE_SEED_OFFSET);
        List<FxRate> rates = new ArrayList<>();
        for (Currency currency : currencies) {
            if (currency.equals(base)) {
                continue;
            }
            rates.add(new FxRate(
                    currency, base, BigDecimal.valueOf(50_000 + random.nextInt(150_000), RATE_SCALE)));
        }
        return rates;
    }

    private static List<Forward> forwards(WorkloadSpec spec, List<Currency> currencies, Currency base) {
        Random random = new Random(spec.seed() + FORWARD_SEED_OFFSET);
        List<Forward> forwards = new ArrayList<>();
        int index = 0;
        for (Currency currency : currencies) {
            if (currency.equals(base)) {
                continue;
            }
            index++;
            forwards.add(new Forward(
                    ForwardId.of(String.format("FWD-%03d", index)),
                    amount(random, currency, 10_000, 100_000),
                    amount(random, base, 10_000, 100_000),
                    EPOCH.plusDays(30L * index)));
        }
        return forwards;
    }

    private static Money amount(Random random, Currency currency, int minimum, int maximum) {
        BigDecimal raw = BigDecimal.valueOf(minimum * 100L + random.nextInt((maximum - minimum) * 100), 2);
        return new Money(raw.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP), currency);
    }
}