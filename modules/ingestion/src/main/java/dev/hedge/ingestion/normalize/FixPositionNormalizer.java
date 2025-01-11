package dev.hedge.ingestion.normalize;

import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.math.BigDecimal;

/**
 * Reads the FIX shape: account, identifier, identifier scheme, signed quantity.
 *
 * <p>Unlike the other custodians, this one carries its identifier scheme per record. FIX tag 22
 * says what tag 48 contains, so the same provider can name a security by CUSIP on one fill and
 * ISIN on the next. That is why identity resolution cannot be decided per provider.
 */
public final class FixPositionNormalizer implements Normalizer<NormalizedPosition> {

    private static final String VERSION = "position-fix-v1";
    private static final int FIELDS = 4;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public NormalizationResult<NormalizedPosition> normalize(
            StoredObservation observation, SourceDescriptor source) {
        String[] fields = observation.payload().split(",", -1);
        if (fields.length != FIELDS) {
            return new NormalizationResult.Rejected<>("expected " + FIELDS + " FIX fields but found "
                    + fields.length + " in '" + observation.payload() + "'");
        }
        IdentifierKind kind = switch (fields[2].trim()) {
            case "1" -> IdentifierKind.CUSIP;
            case "2" -> IdentifierKind.SEDOL;
            case "4" -> IdentifierKind.ISIN;
            case "8" -> IdentifierKind.TICKER;
            default -> null;
        };
        if (kind == null) {
            return new NormalizationResult.Rejected<>("FIX tag 22 carries '" + fields[2].trim()
                    + "', which is not an identifier scheme this system recognises");
        }
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(fields[3].trim());
        } catch (NumberFormatException failure) {
            return new NormalizationResult.Rejected<>(
                    "'" + fields[3].trim() + "' is not a quantity this system can hold exactly");
        }
        return new NormalizationResult.Accepted<>(
                new NormalizedPosition(fields[0].trim(), kind, fields[1].trim(), quantity), VERSION);
    }
}
