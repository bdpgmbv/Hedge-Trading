package dev.hedge.domain;

import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PortfolioExposure(
        FundId fundId,
        Currency baseCurrency,
        Money totalValue,
        List<CurrencyExposure> exposures,
        List<HedgeRequirement> hedgeRequirements) {

    public PortfolioExposure {
        Objects.requireNonNull(fundId, "an exposure report must have a fund id");
        Objects.requireNonNull(baseCurrency, "an exposure report must have a base currency");
        Objects.requireNonNull(totalValue, "an exposure report must have a total value");
        exposures = List.copyOf(Objects.requireNonNull(exposures, "exposures must not be null"));
        hedgeRequirements = List.copyOf(
                Objects.requireNonNull(hedgeRequirements, "hedge requirements must not be null"));
        if (!totalValue.currency().equals(baseCurrency)) {
            throw new IllegalArgumentException("the total value must be in the fund base currency "
                    + baseCurrency.getCurrencyCode() + ", was " + totalValue.currency().getCurrencyCode());
        }
    }

    public Optional<CurrencyExposure> exposureIn(Currency currency) {
        return exposures.stream().filter(exposure -> exposure.currency().equals(currency)).findFirst();
    }

    public Optional<HedgeRequirement> hedgeIn(Currency currency) {
        return hedgeRequirements.stream().filter(hedge -> hedge.currency().equals(currency)).findFirst();
    }

    public List<HedgeRequirement> actionableHedges() {
        return hedgeRequirements.stream().filter(HedgeRequirement::actionable).toList();
    }
}