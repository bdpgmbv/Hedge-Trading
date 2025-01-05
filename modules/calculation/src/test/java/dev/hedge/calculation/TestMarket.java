package dev.hedge.calculation;

import dev.hedge.domain.ExposureWeight;
import dev.hedge.domain.FxRate;
import dev.hedge.domain.Price;
import dev.hedge.domain.Security;
import dev.hedge.domain.SecurityId;
import java.util.Currency;
import java.util.List;

/** A small fixed market, shared by the example tests and the properties. */
final class TestMarket {

    static final Currency USD = Currency.getInstance("USD");
    static final Currency EUR = Currency.getInstance("EUR");
    static final Currency JPY = Currency.getInstance("JPY");

    static final SecurityId US_STOCK = SecurityId.of("SEC-US");
    static final SecurityId EU_STOCK = SecurityId.of("SEC-EU");
    static final SecurityId JP_STOCK = SecurityId.of("SEC-JP");
    static final SecurityId GLOBAL_ETF = SecurityId.of("SEC-ETF");

    static final List<SecurityId> UNIVERSE = List.of(US_STOCK, EU_STOCK, JP_STOCK, GLOBAL_ETF);

    private TestMarket() {}

    static MarketSnapshot snapshot() {
        return MarketSnapshot.builder()
                .security(Security.denominatedIn(US_STOCK, "USD"))
                .security(Security.denominatedIn(EU_STOCK, "EUR"))
                .security(Security.denominatedIn(JP_STOCK, "JPY"))
                .security(new Security(
                        GLOBAL_ETF,
                        USD,
                        List.of(
                                ExposureWeight.of("USD", "0.60"),
                                ExposureWeight.of("EUR", "0.30"),
                                ExposureWeight.of("JPY", "0.10"))))
                .price(Price.of("SEC-US", "100.00", "USD"))
                .price(Price.of("SEC-EU", "50.00", "EUR"))
                .price(Price.of("SEC-JP", "2000", "JPY"))
                .price(Price.of("SEC-ETF", "25.00", "USD"))
                .rate(FxRate.of("EUR", "USD", "1.10"))
                .rate(FxRate.of("JPY", "USD", "0.0065"))
                .build();
    }
}