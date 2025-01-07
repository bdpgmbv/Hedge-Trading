package dev.hedge.ingestion.provider;

import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.temporal.ObservationKind;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.BatchCompleteness;
import dev.hedge.ingestion.source.DeliveryStyle;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.ObservationSource;
import dev.hedge.ingestion.source.RawBatch;
import dev.hedge.ingestion.source.RawRecord;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A market data vendor: prices and fx rates, never positions.
 *
 * <pre>
 * # VENDOR_A 2026-10-13T21:00:00Z
 * PRICE|037833100|USD|184.52
 * FX|EUR|USD|1.1023
 * COUNT|2
 * </pre>
 *
 * <p>The mirror image of the custodian rule: a vendor reporting a position is quarantined just as
 * a custodian reporting a price is. Each source is trusted for exactly one thing.
 */
public final class VendorMarketDataSource implements ObservationSource {

    private final SourceDescriptor descriptor;

    public VendorMarketDataSource(String providerId) {
        this.descriptor = new SourceDescriptor(
                ProviderId.of(providerId),
                Set.of(ObservationKind.PRICE, ObservationKind.FX_RATE),
                IdentifierKind.CUSIP,
                DeliveryStyle.SNAPSHOT,
                "pipe");
    }

    @Override
    public SourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public RawBatch read(Artifact artifact, Instant receivedAt) {
        Objects.requireNonNull(artifact, "an artifact is required");
        Objects.requireNonNull(receivedAt, "a received time is required");

        List<RawRecord> records = new ArrayList<>();
        Instant effectiveAt = null;
        Integer declared = null;

        int lineNumber = 0;
        for (String line : artifact.text().split("\n", -1)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                String[] header = trimmed.substring(1).trim().split("\\s+");
                if (header.length < 2) {
                    return malformed(artifact, receivedAt, records, "the header line is incomplete");
                }
                try {
                    effectiveAt = Instant.parse(header[1]);
                } catch (java.time.format.DateTimeParseException failure) {
                    return malformed(artifact, receivedAt, records,
                            "the header carries '" + header[1] + "', which is not a valid instant");
                }
                continue;
            }
            String[] fields = trimmed.split("\\|");
            switch (fields[0]) {
                case "COUNT" -> declared = Integer.parseInt(fields[1]);
                case "PRICE", "FX" -> {
                    if (effectiveAt == null) {
                        return malformed(artifact, receivedAt, records,
                                "a " + fields[0] + " row appeared before the header that dates it");
                    }
                    records.add(new RawRecord(
                            records.size(),
                            trimmed,
                            Optional.of(fields[0] + ":" + fields[1]),
                            Optional.empty(),
                            effectiveAt));
                }
                case "POSITION" -> {
                    return new RawBatch(descriptor, artifact, receivedAt, records,
                            BatchCompleteness.quarantine(records.size(), descriptor.provider()
                                    + " supplied a POSITION row, but a market data vendor may only"
                                    + " report prices and fx rates; what you hold comes from custodians"));
                }
                default -> {
                    return malformed(artifact, receivedAt, records,
                            "unknown row type '" + fields[0] + "' on line " + lineNumber);
                }
            }
        }

        if (declared == null) {
            return malformed(artifact, receivedAt, records,
                    "the file has no COUNT line, so completeness cannot be verified");
        }
        return new RawBatch(descriptor, artifact, receivedAt, records,
                BatchCompleteness.verified(declared, records.size()));
    }

    private RawBatch malformed(
            Artifact artifact, Instant receivedAt, List<RawRecord> records, String problem) {
        return new RawBatch(descriptor, artifact, receivedAt, records,
                BatchCompleteness.malformed(records.size(), problem));
    }
}
