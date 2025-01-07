package dev.hedge.ingestion.source;

/**
 * Whether a batch replaces what came before it or adds to it.
 *
 * <p>Confusing the two is the most damaging mistake in position ingestion. Applying a SNAPSHOT as
 * if it were INCREMENTAL doubles every holding; applying an INCREMENTAL as if it were a SNAPSHOT
 * deletes every holding the batch did not mention.
 */
public enum DeliveryStyle {
    /** The batch states the complete position of the account as at its effective time. */
    SNAPSHOT,
    /** The batch states only what changed since the previous batch from this provider. */
    INCREMENTAL
}
