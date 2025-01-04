package dev.hedge.generator;

import dev.hedge.domain.Account;
import dev.hedge.domain.AccountId;
import dev.hedge.domain.Fund;
import dev.hedge.domain.FundId;
import dev.hedge.domain.FxRate;
import dev.hedge.domain.Money;
import dev.hedge.domain.Position;
import dev.hedge.domain.Quantity;
import dev.hedge.domain.SecurityId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ArrayList;
import java.util.Collections;

public final class PortfolioGenerator {

//    private static final Random SHARED = new Random(42);

    private static final long FUND_SEED_STRIDE = 1_000_003L;
    private static final long PRICE_SEED_OFFSET = 7_700_017L;
    private static final long RATE_SEED_OFFSET = 9_900_019L;
    private static final int RATE_SCALE = 6;

    public GeneratedPortfolio generate(WorkloadSpec spec) {
        List<SecurityId> securities = securities(spec);
        List<Fund> funds = new ArrayList<>(spec.funds());
        for (int index = 0; index < spec.funds(); index++) {
            funds.add(fund(spec, index, securities));
        }
        return new GeneratedPortfolio(funds, prices(spec, securities), rates(spec));
    }

    private static List<SecurityId> securities(WorkloadSpec spec) {
        List<SecurityId> securities = new ArrayList<>(spec.securities());
        for (int index = 0; index < spec.securities(); index++) {
            securities.add(SecurityId.of(String.format("SEC-%05d", index)));
        }
        return securities;
    }

    private static Fund fund(WorkloadSpec spec, int fundIndex, List<SecurityId> securities) {
//        Random random = SHARED;
        Random random = new Random(spec.seed() + fundIndex * FUND_SEED_STRIDE);
        Currency baseCurrency = Currency.getInstance(spec.currencies().get(0));

        List<Account> accounts = new ArrayList<>(spec.accountsPerFund());
        for (int accountIndex = 0; accountIndex < spec.accountsPerFund(); accountIndex++) {
            AccountId accountId = AccountId.of(String.format("ACC-%05d-%03d", fundIndex, accountIndex));
            List<SecurityId> available = new ArrayList<>(securities);
            Collections.shuffle(available, random);
            List<Position> positions = new ArrayList<>(spec.positionsPerAccount());
            for (int i = 0; i < spec.positionsPerAccount(); i++) {
                positions.add(new Position(
                        accountId, available.get(i), Quantity.of(String.valueOf(1 + random.nextInt(1000)))));
            }
            Currency cashCurrency = Currency.getInstance(spec.currencies().get(random.nextInt(spec.currencies().size())));
            accounts.add(new Account(accountId, positions, List.of(amount(random, cashCurrency, 1_000, 100_000))));
        }
        return new Fund(FundId.of(String.format("FUND-%05d", fundIndex)), baseCurrency, accounts);
    }

    private static Map<SecurityId, Money> prices(WorkloadSpec spec, List<SecurityId> securities) {
        Random random = new Random(spec.seed() + PRICE_SEED_OFFSET);
        Currency priceCurrency = Currency.getInstance(spec.currencies().get(0));
        Map<SecurityId, Money> prices = new LinkedHashMap<>();
        for (SecurityId security : securities) {
            prices.put(security, amount(random, priceCurrency, 10, 500));
        }
        return prices;
    }

    private static List<FxRate> rates(WorkloadSpec spec) {
        Random random = new Random(spec.seed() + RATE_SEED_OFFSET);
        Currency base = Currency.getInstance(spec.currencies().get(0));
        List<FxRate> rates = new ArrayList<>();
        for (String code : spec.currencies()) {
            Currency currency = Currency.getInstance(code);
            if (currency.equals(base)) {
                continue;
            }
            BigDecimal rate = BigDecimal.valueOf(50_000 + random.nextInt(150_000), RATE_SCALE);
            rates.add(new FxRate(currency, base, rate));
        }
        return rates;
    }

    private static Money amount(Random random, Currency currency, int minimum, int maximum) {
        BigDecimal raw = BigDecimal.valueOf(minimum * 100L + random.nextInt((maximum - minimum) * 100), 2);
        return new Money(raw.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP), currency);
//        return new Money(raw, currency);
    }
}