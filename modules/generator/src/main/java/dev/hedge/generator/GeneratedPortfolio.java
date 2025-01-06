package dev.hedge.generator;

import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.FxRate;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.portfolio.Fund;
import java.util.List;
import java.util.Map;
import java.util.Objects;
public record GeneratedPortfolio(List<Fund> funds, Map<SecurityId, Money> prices, List<FxRate> rates) {

    public GeneratedPortfolio {
        funds = List.copyOf(Objects.requireNonNull(funds, "funds must not be null"));
        prices = Map.copyOf(Objects.requireNonNull(prices, "prices must not be null"));
        rates = List.copyOf(Objects.requireNonNull(rates, "rates must not be null"));
    }

    public int positionCount() {
        return funds.stream().mapToInt(Fund::positionCount).sum();
    }
}