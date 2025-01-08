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
 * Custodian C: one day's positions split across several files, securities named by the provider's
 * own internal code.
 *
 * <pre>
 * #CHUNK 1/3 CUSTODIAN_C 2026-10-13T21:00:00Z
 * P|CPROV-7781|ACCT9900|412.5
 * P|CPROV-7782|ACCT9900|88
 * #ENDCHUNK 2
 * </pre>
 *
 * <p>Each chunk is its own artifact and its own batch, which keeps this adapter honest: bytes in,
 * records out, no state carried between files. The chunk position is recorded as the batch
 * sequence so that a later layer can notice chunk 2 of 3 never arriving — a missing chunk looks
 * exactly like an account that sold everything, and only the declared total reveals it.
 *
 * <p>The identifiers are the awkward case on purpose. {@code CPROV-7781} means nothing to anyone
 * but this custodian, so it cannot be matched to another source without a mapping that someone
 * maintains. Identifier equality is not economic identity.
 */
public final class CustodianCChunkedSource implements ObservationSource {

    private static final SourceDescriptor DESCRIPTOR = new SourceDescriptor(
            ProviderId.of("CUSTODIAN_C"),
            Set.of(ObservationKind.POSITION),
            IdentifierKind.PROVIDER_SPECIFIC,
            DeliveryStyle.SNAPSHOT,
            "chunked");

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
        String chunk = null;
        Integer declared = null;

        int lineNumber = 0;
        for (String line : artifact.text().split("\n", -1)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#CHUNK")) {
                String[] header = trimmed.split("\\s+");
                if (header.length < 4) {
                    return malformed(artifact, receivedAt, records, chunk,
                            "the chunk header is incomplete on line " + lineNumber);
                }
                chunk = header[1];
                try {
                    effectiveAt = Instant.parse(header[3]);
                } catch (java.time.format.DateTimeParseException failure) {
                    return malformed(artifact, receivedAt, records, chunk,
                            "the chunk header carries '" + header[3] + "', which is not a valid instant");
                }
                continue;
            }
            if (trimmed.startsWith("#ENDCHUNK")) {
                String[] end = trimmed.split("\\s+");
                if (end.length < 2) {
                    return malformed(artifact, receivedAt, records, chunk,
                            "the chunk terminator carries no record count");
                }
                declared = Integer.parseInt(end[1]);
                continue;
            }
            String[] fields = trimmed.split("\\|");
            if (!"P".equals(fields[0])) {
                return malformed(artifact, receivedAt, records, chunk,
                        "unknown row type '" + fields[0] + "' on line " + lineNumber);
            }
            if (effectiveAt == null) {
                return malformed(artifact, receivedAt, records, chunk,
                        "a position row appeared before the chunk header that dates it");
            }
            records.add(new RawRecord(
                    records.size(),
                    fields[2] + "," + fields[1] + "," + fields[3],
                    Optional.of(fields[1]),
                    Optional.empty(),
                    effectiveAt));
        }

        if (chunk == null) {
            return malformed(artifact, receivedAt, records, null,
                    "the file has no #CHUNK header, so its place in the day is unknown");
        }
        if (declared == null) {
            return malformed(artifact, receivedAt, records, chunk,
                    "the chunk has no #ENDCHUNK terminator, so it may have been cut short");
        }
        return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                BatchCompleteness.verified(declared, records.size()), Optional.of(chunk));
    }

    private static RawBatch malformed(
            Artifact artifact, Instant receivedAt, List<RawRecord> records, String chunk, String problem) {
        return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                BatchCompleteness.malformed(records.size(), problem), Optional.ofNullable(chunk));
    }
}
