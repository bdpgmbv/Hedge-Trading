package dev.hedge.ingestion.normalize;

import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads a vendor's market data rows, which carry prices and fx rates in one stream.
 *
 * <pre>
 * PRICE|037833100|USD|184.52
 * FX|EUR|USD|1.1023
 * </pre>
 *
 * <p>Returns one or the other, never both, so the caller can route each to its own store without
 * the normalizer knowing where either goes.
 */
public final class MarketDataNormalizer implements Normalizer<MarketDataNormalizer.MarketDatum> {

    private static final String VERSION = "market-data-pipe-v1";

    /** Exactly one of the two is present. */
    public record MarketDatum(Optional<NormalizedPrice> price, Optional<NormalizedFxRate> rate) {
        public MarketDatum {
            Objects.requireNonNull(price, "price must be present or empty");
            Objects.requireNonNull(rate, "rate must be present or empty");
            if (price.isPresent() == rate.isPresent()) {
                throw new IllegalArgumentException("a market datum is either a price or a rate");
            }
        }

        static MarketDatum of(NormalizedPrice price) {
            return new MarketDatum(Optional.of(price), Optional.empty());
        }

        static MarketDatum of(NormalizedFxRate rate) {
            return new MarketDatum(Optional.empty(), Optional.of(rate));
        }
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public NormalizationResult<MarketDatum> normalize(
            StoredObservation observation, SourceDescriptor source) {
        String[] fields = observation.payload().split("\\|", -1);
        if (fields.length != 4) {
            return new NormalizationResult.Rejected<>(
                    "expected 4 fields but found " + fields.length + " in '" + observation.payload() + "'");
        }
        return switch (fields[0].trim()) {
            case "PRICE" -> price(fields, source);
            case "FX" -> rate(fields);
            default -> new NormalizationResult.Rejected<>(
                    "'" + fields[0].trim() + "' is neither a PRICE nor an FX row");
        };
    }

    private NormalizationResult<MarketDatum> price(String[] fields, SourceDescriptor source) {
        Currency currency;
        try {
            currency = Currency.getInstance(fields[2].trim());
        } catch (IllegalArgumentException failure) {
            return new NormalizationResult.Rejected<>(
                    "'" + fields[2].trim() + "' is not a currency code");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(fields[3].trim());
        } catch (NumberFormatException failure) {
            return new NormalizationResult.Rejected<>("'" + fields[3].trim() + "' is not a price");
        }
        if (amount.signum() < 0) {
            return new NormalizationResult.Rejected<>("a price of " + amount + " is negative");
        }
        return new NormalizationResult.Accepted<>(
                MarketDatum.of(new NormalizedPrice(
                        source.identifierKind(), fields[1].trim(), amount, currency)),
                VERSION);
    }

    private NormalizationResult<MarketDatum> rate(String[] fields) {
        Currency base;
        Currency quote;
        try {
            base = Currency.getInstance(fields[1].trim());
            quote = Currency.getInstance(fields[2].trim());
        } catch (IllegalArgumentException failure) {
            return new NormalizationResult.Rejected<>(
                    "'" + fields[1].trim() + "/" + fields[2].trim() + "' is not a currency pair");
        }
        if (base.equals(quote)) {
            return new NormalizationResult.Rejected<>(
                    "a rate from " + base.getCurrencyCode() + " to itself carries no information");
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(fields[3].trim());
        } catch (NumberFormatException failure) {
            return new NormalizationResult.Rejected<>("'" + fields[3].trim() + "' is not a rate");
        }
        if (rate.signum() <= 0) {
            return new NormalizationResult.Rejected<>("an fx rate of " + rate + " is not positive");
        }
        return new NormalizationResult.Accepted<>(
                MarketDatum.of(new NormalizedFxRate(base, quote, rate)), VERSION);
    }
}
