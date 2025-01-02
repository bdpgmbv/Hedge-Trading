package dev.hedge.domain;

import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class Probe {

    @Test
    void run() {
//        System.out.println("PROBE " + Money.of("100", "USD"));
//        System.out.println("PROBE " + Money.of("100", "JPY"));

//        System.out.println("PROBE " + Money.of("100.00", "USD").plus(Money.of("50.00", "EUR")));

//        System.out.println("PROBE " + Money.of("1.005", "USD"));

//        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");
//        System.out.println("PROBE wrong way: " + eurUsd.convert(Money.of("100.00", "USD"), RoundingMode.HALF_UP));
//        System.out.println("PROBE right way: " + eurUsd.inverse().convert(Money.of("100.00", "USD"), RoundingMode.HALF_UP));

//        Money price = Money.of("10.00", "USD");
//        System.out.println("PROBE HALF_UP: " + price.multipliedBy(new java.math.BigDecimal("1.0049"), RoundingMode.HALF_UP));
//        System.out.println("PROBE DOWN   : " + price.multipliedBy(new java.math.BigDecimal("1.0049"), RoundingMode.DOWN));

//        FxRate r = FxRate.of("EUR", "USD", "1.10");
//        System.out.println("PROBE original : " + r.rate().toPlainString());
//        System.out.println("PROBE inverted twice: " + r.inverse().inverse().rate().toPlainString());
//        System.out.println("PROBE same?    : " + r.equals(r.inverse().inverse()));

        AccountId account = AccountId.of("ACC-1");
        Position apple = new Position(account, SecurityId.of("AAPL"), Quantity.of("100"));
        Money pricePerShare = Money.of("150.00", "USD");

        Money valueUsd = pricePerShare.multipliedBy(apple.quantity().value(), RoundingMode.HALF_UP);
        System.out.println("PROBE 1 value in USD : " + valueUsd);

        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");
        Money valueEur = eurUsd.inverse().convert(valueUsd, RoundingMode.HALF_UP);
        System.out.println("PROBE 2 value in EUR : " + valueEur);

        Money cash = Money.of("2500.00", "USD");
        System.out.println("PROBE 3 total USD    : " + valueUsd.plus(cash));

        try {
            valueUsd.plus(valueEur);
        } catch (IllegalArgumentException e) {
            System.out.println("PROBE 4 refused      : " + e.getMessage());
        }
    }
}