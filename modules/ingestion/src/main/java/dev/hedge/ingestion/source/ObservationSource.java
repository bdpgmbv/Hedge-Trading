package dev.hedge.ingestion.source;

import java.time.Instant;

/**
 * One way data reaches this system.
 *
 * <p>Four transports implement this: custodian files arriving over SFTP, files uploaded by hand,
 * positions typed into the user interface, and later a Kafka stream of internal trades. They differ
 * only in how bytes arrive. Everything after this point — storing the evidence, normalizing it,
 * resolving identifiers, reconciling — is identical for all of them, because a second pipeline for
 * a second transport means a second set of bugs and two different answers to the same question.
 */
public interface ObservationSource {

    SourceDescriptor descriptor();

    /**
     * Reads an artifact into raw records without interpreting them. Implementations split and
     * record; they do not resolve identifiers, convert currencies, or decide what anything means.
     */
    RawBatch read(Artifact artifact, Instant receivedAt);
}
