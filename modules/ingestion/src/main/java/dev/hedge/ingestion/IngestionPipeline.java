package dev.hedge.ingestion;

import dev.hedge.ingestion.raw.DuplicateArtifactException;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.ingestion.raw.StoredBatch;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.ObservationSource;
import dev.hedge.ingestion.source.RawBatch;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Takes a delivered artifact from any transport and turns it into stored evidence.
 *
 * <p>Deliberately the only route in. A custodian file over SFTP, a hand upload, a typed entry and
 * later a Kafka message all arrive here, because a second pipeline for a second transport means a
 * second set of bugs and two different answers to the same question.
 */
public final class IngestionPipeline {

    private final RawObservationStore store;
    private final Clock clock;

    public IngestionPipeline(RawObservationStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "a raw observation store is required");
        this.clock = Objects.requireNonNull(clock, "a clock is required");
    }

    /** What became of one delivery. A duplicate is an outcome, not a failure. */
    public sealed interface Outcome {
        record Ingested(StoredBatch batch) implements Outcome {}

        record Rejected(StoredBatch batch, String problem) implements Outcome {}

        record AlreadySeen(String artifactName, String sha256) implements Outcome {}
    }

    public Outcome accept(ObservationSource source, Artifact artifact) {
        Objects.requireNonNull(source, "a source is required");
        Objects.requireNonNull(artifact, "an artifact is required");

        Instant receivedAt = clock.instant();
        store.registerProvider(source.descriptor());

        RawBatch batch = source.read(artifact, receivedAt);
        try {
            StoredBatch stored = store.store(batch);
            Optional<String> problem = batch.completeness().problem();
            return problem.isPresent()
                    ? new Outcome.Rejected(stored, problem.orElseThrow())
                    : new Outcome.Ingested(stored);
        } catch (DuplicateArtifactException duplicate) {
            // The evidence of the first delivery is already stored, so there is nothing to keep
            // and nothing to undo. Retransmission is routine, not an error worth alarming on.
            return new Outcome.AlreadySeen(artifact.name(), artifact.sha256());
        }
    }
}
