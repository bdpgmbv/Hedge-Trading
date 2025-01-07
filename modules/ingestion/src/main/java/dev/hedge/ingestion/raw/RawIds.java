package dev.hedge.ingestion.raw;

import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;

/**
 * Identifiers derived from content rather than generated.
 *
 * <p>Replaying the same artifact must produce the same ids, or the reconstructed history would
 * differ from the live one in every identifier and Layer 44's equality check would be impossible.
 * A random UUID here would quietly make deterministic replay unachievable.
 */
public final class RawIds {

    private static final int HASH_PREFIX = 16;

    private RawIds() {}

    public static BatchId batchId(ProviderId provider, String artifactSha256) {
        return BatchId.of(provider.value() + "-" + artifactSha256.substring(0, HASH_PREFIX));
    }

    public static ObservationId observationId(BatchId batch, int sequence) {
        return ObservationId.of(batch.value() + "#" + sequence);
    }
}
