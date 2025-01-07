package dev.hedge.ingestion.raw;

import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ProviderId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record StoredBatch(
        BatchId id,
        ProviderId provider,
        String artifactName,
        String artifactSha256,
        int artifactBytes,
        Instant receivedAt,
        Optional<Integer> declaredCount,
        int parsedCount,
        BatchStatus status,
        Optional<String> problem) {

    public StoredBatch {
        Objects.requireNonNull(id, "a stored batch needs an id");
        Objects.requireNonNull(provider, "a stored batch needs a provider");
        Objects.requireNonNull(artifactName, "a stored batch needs an artifact name");
        Objects.requireNonNull(artifactSha256, "a stored batch needs an artifact hash");
        Objects.requireNonNull(receivedAt, "a stored batch needs a received time");
        Objects.requireNonNull(declaredCount, "declaredCount must be present or empty, never null");
        Objects.requireNonNull(status, "a stored batch needs a status");
        Objects.requireNonNull(problem, "problem must be present or empty, never null");
    }
}
