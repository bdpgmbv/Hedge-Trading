package dev.hedge.domain.exposure;

import dev.hedge.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
public record HedgeRequirement(
        Currency currency, Money gross, Money hedged, Money target, Money required, boolean actionable) {

    public HedgeRequirement {
        Objects.requireNonNull(currency, "a hedge requirement must have a currency");
        Objects.requireNonNull(gross, "gross must not be null");
        Objects.requireNonNull(hedged, "hedged must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(required, "required must not be null");
    }

    /**
     * The forward position the policy wants is the negative of the gross exposure scaled by the
     * target ratio. What must still be traded is that target minus what the live forwards already
     * provide.
     */
    public static HedgeRequirement from(CurrencyExposure exposure, HedgePolicy policy) {
        Objects.requireNonNull(exposure, "an exposure is required");
        Objects.requireNonNull(policy, "a hedge policy is required");

        Money gross = exposure.gross();
        Money hedged = exposure.forwards();
        Money target = gross.multipliedBy(policy.targetRatio().negate(), RoundingMode.HALF_UP);
        Money required = target.minus(hedged);

        BigDecimal threshold = gross.amount().abs().multiply(policy.tolerance());
        boolean actionable = required.amount().abs().compareTo(threshold) > 0;

        return new HedgeRequirement(exposure.currency(), gross, hedged, target, required, actionable);
    }
}