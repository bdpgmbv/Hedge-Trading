package dev.hedge.ingestion.replay;

import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.ingestion.canonical.CanonicalProjector;
import dev.hedge.ingestion.normalize.NormalizedPosition;
import dev.hedge.ingestion.normalize.Normalizer;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds canonical state from the stored evidence alone.
 *
 * <p>The invariant it exists to make checkable is {@code LiveState(T) = ReplayState(T)} given the
 * same source history, normalization versions and identity mappings. If the two ever differ, then
 * something that is not evidence influenced the live answer — a clock, a random id, an ordering
 * that depended on how fast a thread ran — and the system can no longer explain its own past.
 *
 * <p>It writes nothing. Comparing what it produces against what is stored is the job of the
 * reconciliation engine, which already compares two lists of positions and does not care that one
 * of them is a reconstruction.
 */
public final class ReplayEngine {

    private final RawObservationStore store;
    private final CanonicalProjector projector;

    public ReplayEngine(RawObservationStore store, CanonicalProjector projector) {
        this.store = Objects.requireNonNull(store, "a raw store is required");
        this.projector = Objects.requireNonNull(projector, "a projector is required");
    }

    public record Replayed(
            List<Observation<Position>> positions,
            int batchesReplayed,
            int observationsRead,
            Duration took) {}

    /**
     * Replays every batch in receipt order and resolves the result as of a point in time.
     *
     * <p>Receipt order matters: an incremental feed applied in a different order produces a
     * different position, so a replay that sorted batches any other way would diverge for a
     * reason that has nothing to do with correctness.
     */
    public Replayed replayAsOf(
            Map<String, SourceDescriptor> sourcesByProvider,
            Map<String, Normalizer<NormalizedPosition>> normalizersByProvider,
            Instant effectiveAt,
            Instant knownAt) {
        Objects.requireNonNull(sourcesByProvider, "sources are required");
        Objects.requireNonNull(normalizersByProvider, "normalizers are required");

        long startedAt = System.nanoTime();
        List<Observation<Position>> everything = new ArrayList<>();
        int batches = 0;
        int observations = 0;

        for (BatchId batch : store.batchesInReceiptOrder()) {
            var stored = store.findBatch(batch).orElseThrow();
            SourceDescriptor source = sourcesByProvider.get(stored.provider().value());
            Normalizer<NormalizedPosition> normalizer =
                    normalizersByProvider.get(stored.provider().value());
            if (source == null || normalizer == null) {
                continue; // nothing is known about how to read this provider any more
            }
            batches++;
            observations += stored.parsedCount();
            everything.addAll(
                    projector.project(store, batch, source, normalizer, false).positions());
        }

        return new Replayed(
                resolveAsOf(everything, effectiveAt, knownAt),
                batches,
                observations,
                Duration.ofNanos(System.nanoTime() - startedAt));
    }

    /**
     * The same supersession rule the database applies, applied in memory: latest effective time
     * wins, and among equals the latest received time wins.
     */
    private static List<Observation<Position>> resolveAsOf(
            List<Observation<Position>> all, Instant effectiveAt, Instant knownAt) {
        record Holding(String account, String security) {}
        Map<Holding, Observation<Position>> winner = new HashMap<>();
        for (Observation<Position> observation : all) {
            if (observation.effectiveAt().isAfter(effectiveAt)
                    || observation.receivedAt().isAfter(knownAt)) {
                continue;
            }
            Holding holding = new Holding(
                    observation.value().accountId().value(), observation.value().securityId().value());
            Observation<Position> current = winner.get(holding);
            if (current == null || beats(observation, current)) {
                winner.put(holding, observation);
            }
        }
        List<Observation<Position>> resolved = new ArrayList<>(winner.values());
        resolved.sort(Comparator
                .comparing((Observation<Position> observation) -> observation.value().accountId().value())
                .thenComparing(observation -> observation.value().securityId().value()));
        return resolved;
    }

    private static boolean beats(Observation<Position> candidate, Observation<Position> current) {
        int byEffective = candidate.effectiveAt().compareTo(current.effectiveAt());
        return byEffective != 0
                ? byEffective > 0
                : candidate.receivedAt().isAfter(current.receivedAt());
    }
}
