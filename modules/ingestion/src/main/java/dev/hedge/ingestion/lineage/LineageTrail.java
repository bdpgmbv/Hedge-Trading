package dev.hedge.ingestion.lineage;

import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import java.time.Instant;
import java.util.Objects;

/**
 * The full answer to "where did this number come from".
 *
 * <p>Every hop is here: the canonical value, the rules that produced it, the provider record it
 * came from, the batch that delivered it, and the fingerprint of the exact bytes. A number that
 * cannot be traced to bytes is a number nobody can defend when a client disputes it.
 */
public record LineageTrail(
        ObservationId observation,
        String normalizationVersion,
        ProviderId provider,
        BatchId batch,
        String artifactName,
        String artifactSha256,
        Instant effectiveAt,
        Instant receivedAt,
        String rawPayload) {

    public LineageTrail {
        Objects.requireNonNull(observation, "a trail needs an observation");
        Objects.requireNonNull(normalizationVersion, "a trail needs a normalization version");
        Objects.requireNonNull(provider, "a trail needs a provider");
        Objects.requireNonNull(batch, "a trail needs a batch");
        Objects.requireNonNull(artifactName, "a trail needs an artifact name");
        Objects.requireNonNull(artifactSha256, "a trail needs an artifact hash");
        Objects.requireNonNull(rawPayload, "a trail needs the raw payload");
    }

    /** One line a person can read, for a break report or an audit response. */
    public String describe() {
        return "effective " + effectiveAt + ", received " + receivedAt
                + ", from " + provider + " file '" + artifactName + "'"
                + " (sha256 " + artifactSha256.substring(0, 12) + "...)"
                + ", record '" + rawPayload + "'"
                + ", normalized by " + normalizationVersion;
    }
}
