package dev.hedge.ingestion.normalize;

import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.math.BigDecimal;

/**
 * Reads the shape custodians A, B and C all reduce to: account, identifier, quantity.
 *
 * <p>Which identifier scheme those middle characters belong to is not in the record — it comes
 * from the provider's descriptor, because a CUSIP and an ISIN are both just text.
 */
public final class DelimitedPositionNormalizer implements Normalizer<NormalizedPosition> {

    private static final String VERSION = "position-delimited-v1";
    private static final int FIELDS = 3;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public NormalizationResult<NormalizedPosition> normalize(
            StoredObservation observation, SourceDescriptor source) {
        String[] fields = observation.payload().split(",", -1);
        if (fields.length != FIELDS) {
            return new NormalizationResult.Rejected<>("expected " + FIELDS + " fields but found "
                    + fields.length + " in '" + observation.payload() + "'");
        }
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(fields[2].trim());
        } catch (NumberFormatException failure) {
            return new NormalizationResult.Rejected<>(
                    "'" + fields[2].trim() + "' is not a quantity this system can hold exactly");
        }
        if (fields[0].isBlank() || fields[1].isBlank()) {
            return new NormalizationResult.Rejected<>(
                    "the account or the security identifier is blank in '" + observation.payload() + "'");
        }
        return new NormalizationResult.Accepted<>(
                new NormalizedPosition(
                        fields[0].trim(), source.identifierKind(), fields[1].trim(), quantity),
                VERSION);
    }
}
