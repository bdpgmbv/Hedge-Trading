package dev.hedge.ingestion.raw;

import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ObservationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One provider record as it was received, retrievable forever. */
public record StoredObservation(
        ObservationId id,
        BatchId batch,
        int sequence,
        Optional<String> providerRecordId,
        Optional<String> providerVersion,
        Instant effectiveAt,
        Instant receivedAt,
        String payload) {

    public StoredObservation {
        Objects.requireNonNull(id, "a stored observation needs an id");
        Objects.requireNonNull(batch, "a stored observation needs a batch");
        Objects.requireNonNull(providerRecordId, "providerRecordId must be present or empty");
        Objects.requireNonNull(providerVersion, "providerVersion must be present or empty");
        Objects.requireNonNull(effectiveAt, "a stored observation needs an effective time");
        Objects.requireNonNull(receivedAt, "a stored observation needs a received time");
        Objects.requireNonNull(payload, "a stored observation needs its payload");
    }
}
