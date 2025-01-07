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
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Custodian A: a complete snapshot as a CSV file, securities named by CUSIP, with a trailer.
 *
 * <pre>
 * HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
 * POSITION,ACC-00001,037833100,1500.00000000
 * POSITION,ACC-00001,594918104,2300.00000000
 * TRAILER,2
 * </pre>
 *
 * <p>The trailer is what makes truncation detectable. A position file cut short by a failed
 * transfer is indistinguishable from a portfolio that sold everything after row 4,000, and only a
 * declared count catches it.
 */
public final class CustodianACsvSource implements ObservationSource {

    private static final SourceDescriptor DESCRIPTOR = new SourceDescriptor(
            ProviderId.of("CUSTODIAN_A"),
            Set.of(ObservationKind.POSITION),
            IdentifierKind.CUSIP,
            DeliveryStyle.SNAPSHOT,
            "csv");

    private static final CSVFormat FORMAT =
            CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).setTrim(true).build();

    @Override
    public SourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RawBatch read(Artifact artifact, Instant receivedAt) {
        Objects.requireNonNull(artifact, "an artifact is required");
        Objects.requireNonNull(receivedAt, "a received time is required");

        List<RawRecord> records = new ArrayList<>();
        Instant effectiveAt = null;
        Integer declared = null;
        String forbidden = null;

        try (CSVParser parser = CSVParser.parse(new StringReader(artifact.text()), FORMAT)) {
            for (CSVRecord row : parser) {
                String kind = row.get(0);
                switch (kind) {
                    case "HEADER" -> effectiveAt = endOfDay(row.get(2));
                    case "TRAILER" -> declared = Integer.parseInt(row.get(1));
                    case "POSITION" -> {
                        if (effectiveAt == null) {
                            return malformed(artifact, receivedAt, records,
                                    "a POSITION row appeared before the HEADER that dates it");
                        }
                        records.add(new RawRecord(
                                records.size(),
                                String.join(",", row.get(1), row.get(2), row.get(3)),
                                Optional.of(row.get(1) + ":" + row.get(2)),
                                Optional.empty(),
                                effectiveAt));
                    }
                    // A custodian is not a pricing source. Seeing one here is not a parse problem,
                    // it is a provider exceeding what it is permitted to tell us.
                    case "PRICE", "FX" -> forbidden = kind;
                    default -> {
                        return malformed(artifact, receivedAt, records,
                                "unknown row type '" + kind + "' at line " + row.getRecordNumber());
                    }
                }
            }
        } catch (IOException | IllegalArgumentException | ArrayIndexOutOfBoundsException failure) {
            return malformed(artifact, receivedAt, records,
                    "could not parse the file: " + failure.getMessage());
        }

        if (forbidden != null) {
            return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                    BatchCompleteness.quarantine(records.size(), DESCRIPTOR.provider()
                            + " supplied a " + forbidden + " row, but a custodian may only report"
                            + " positions; prices come from market data vendors so that the same"
                            + " security is valued identically whoever holds it"));
        }
        if (declared == null) {
            return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                    BatchCompleteness.malformed(records.size(),
                            "the file has no TRAILER, so completeness cannot be verified"));
        }
        return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                BatchCompleteness.verified(declared, records.size()));
    }

    private static RawBatch malformed(
            Artifact artifact, Instant receivedAt, List<RawRecord> records, String problem) {
        return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                BatchCompleteness.malformed(records.size(), problem));
    }

    /** A custodian snapshot describes the close of the business day it names. */
    private static Instant endOfDay(String isoDate) {
        return LocalDate.parse(isoDate).atTime(21, 0).toInstant(ZoneOffset.UTC);
    }
}
