package dev.hedge.ingestion.raw;

import dev.hedge.ingestion.source.IngestionException;

/**
 * Thrown when a provider delivers bytes it has already delivered.
 *
 * <p>Custodians retransmit after a failed transfer, and schedulers occasionally fire twice. Without
 * this the same positions are ingested again, which on an incremental feed doubles a holding and
 * is almost impossible to spot afterwards.
 */
public class DuplicateArtifactException extends IngestionException {

    private static final long serialVersionUID = 1L;

    public DuplicateArtifactException(String message) {
        super(message);
    }
}
