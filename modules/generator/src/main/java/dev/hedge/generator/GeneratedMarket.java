package dev.hedge.generator;

import dev.hedge.domain.Forward;
import dev.hedge.domain.FxRate;
import dev.hedge.domain.Price;
import dev.hedge.domain.Security;
import java.util.List;
import java.util.Objects;

public record GeneratedMarket(
        List<Security> securities, List<Price> prices, List<FxRate> rates, List<Forward> forwards) {

    public GeneratedMarket {
        securities = List.copyOf(Objects.requireNonNull(securities, "securities must not be null"));
        prices = List.copyOf(Objects.requireNonNull(prices, "prices must not be null"));
        rates = List.copyOf(Objects.requireNonNull(rates, "rates must not be null"));
        forwards = List.copyOf(Objects.requireNonNull(forwards, "forwards must not be null"));
    }
}