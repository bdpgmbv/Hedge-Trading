package dev.hedge.calculation;

import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.FxRate;
import dev.hedge.domain.money.Price;
import dev.hedge.domain.portfolio.ExposureWeight;
import dev.hedge.domain.portfolio.Security;
import java.util.Currency;
import java.util.List;
/** A small fixed market, shared by the example tests and the properties. */
public final class TestMarket {

    public static final Currency USD = Currency.getInstance("USD");
    public static final Currency EUR = Currency.getInstance("EUR");
    public static final Currency JPY = Currency.getInstance("JPY");

    public static final SecurityId US_STOCK = SecurityId.of("SEC-US");
    public static final SecurityId EU_STOCK = SecurityId.of("SEC-EU");
    public static final SecurityId JP_STOCK = SecurityId.of("SEC-JP");
    public static final SecurityId GLOBAL_ETF = SecurityId.of("SEC-ETF");

    public static final List<SecurityId> UNIVERSE = List.of(US_STOCK, EU_STOCK, JP_STOCK, GLOBAL_ETF);

    private TestMarket() {}

    public static MarketSnapshot snapshot() {
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