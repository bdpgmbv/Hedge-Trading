package dev.hedge.ingestion.source;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One record exactly as the provider wrote it, before anything has been interpreted.
 *
 * @param sequence position within the batch, so a rejection can name the offending line
 * @param payload the provider's own text for this record, kept verbatim
 * @param providerRecordId the provider's identifier for this record, where they supply one
 * @param providerVersion the provider's own version or revision, used to spot corrections
 * @param effectiveAt the business time the provider says this record applies to
 */
public record RawRecord(
        int sequence,
        String payload,
        Optional<String> providerRecordId,
        Optional<String> providerVersion,
        Instant effectiveAt) {

    public RawRecord {
        if (sequence < 0) {
            throw new IllegalArgumentException("a record sequence must not be negative, was " + sequence);
        }
        Objects.requireNonNull(payload, "a raw record needs its payload");
        Objects.requireNonNull(providerRecordId, "providerRecordId must be present or empty, never null");
        Objects.requireNonNull(providerVersion, "providerVersion must be present or empty, never null");
        Objects.requireNonNull(effectiveAt, "a raw record needs an effective time");
    }
}
