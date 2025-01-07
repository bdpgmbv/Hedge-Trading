package dev.hedge.ingestion.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * Custodian B: only what changed since the last file, as JSON, securities named by ISIN.
 *
 * <pre>
 * {
 *   "provider": "CUSTODIAN_B",
 *   "effectiveAt": "2026-10-13T21:00:00Z",
 *   "delivery": "INCREMENTAL",
 *   "batchSequence": 42,
 *   "changes": [ { "recordId": "B-1001", "version": 1,
 *                  "account": "CUST-B-99", "isin": "US0378331005", "quantity": "100" } ]
 * }
 * </pre>
 *
 * <p>Two things differ from Custodian A beyond the format. It is INCREMENTAL, so applying it as a
 * snapshot would delete every holding it does not mention. And it needs no trailer: a truncated
 * JSON document fails to parse, so the structure is its own completeness check — which is a real
 * advantage of the format, not a gap in the control.
 *
 * <p>{@code batchSequence} is how a missing file is noticed. Gap detection across batches belongs
 * to a later layer; the number is captured here so that layer has something to work with.
 */
public final class CustodianBJsonSource implements ObservationSource {

    private static final SourceDescriptor DESCRIPTOR = new SourceDescriptor(
            ProviderId.of("CUSTODIAN_B"),
            Set.of(ObservationKind.POSITION),
            IdentifierKind.ISIN,
            DeliveryStyle.INCREMENTAL,
            "json");

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public SourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RawBatch read(Artifact artifact, Instant receivedAt) {
        Objects.requireNonNull(artifact, "an artifact is required");
        Objects.requireNonNull(receivedAt, "a received time is required");

        List<RawRecord> records = new ArrayList<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(artifact.text());
        } catch (com.fasterxml.jackson.core.JacksonException failure) {
            return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                    BatchCompleteness.malformed(0,
                            "the document is not valid JSON, which for this provider also means it"
                                    + " was truncated in transfer: " + failure.getOriginalMessage()));
        }

        JsonNode effective = root.get("effectiveAt");
        if (effective == null || !effective.isTextual()) {
            return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                    BatchCompleteness.malformed(0, "the document has no effectiveAt, so nothing in it can be dated"));
        }
        Instant effectiveAt;
        try {
            effectiveAt = Instant.parse(effective.asText());
        } catch (java.time.format.DateTimeParseException failure) {
            return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                    BatchCompleteness.malformed(0, "effectiveAt '" + effective.asText() + "' is not a valid instant"));
        }

        if (root.has("prices") || root.has("fxRates")) {
            return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                    BatchCompleteness.quarantine(0, DESCRIPTOR.provider()
                            + " supplied market data, but a custodian may only report positions"));
        }

        JsonNode changes = root.get("changes");
        if (changes == null || !changes.isArray()) {
            return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                    BatchCompleteness.malformed(0, "the document has no 'changes' array"));
        }

        for (JsonNode change : changes) {
            records.add(new RawRecord(
                    records.size(),
                    text(change, "account") + "," + text(change, "isin") + "," + text(change, "quantity"),
                    Optional.ofNullable(change.get("recordId")).map(JsonNode::asText),
                    Optional.ofNullable(change.get("version")).map(JsonNode::asText),
                    effectiveAt));
        }
        return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                BatchCompleteness.unverifiable(records.size()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asText();
    }
}
