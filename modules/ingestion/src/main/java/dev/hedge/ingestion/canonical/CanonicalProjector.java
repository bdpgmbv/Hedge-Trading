package dev.hedge.ingestion.canonical;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.domain.temporal.Provenance;
import dev.hedge.ingestion.identity.AccountMappingRegistry;
import dev.hedge.ingestion.identity.Resolution;
import dev.hedge.ingestion.identity.SecurityIdentifierRegistry;
import dev.hedge.ingestion.identity.UnresolvedIdentifierLog;
import dev.hedge.ingestion.normalize.NormalizationResult;
import dev.hedge.ingestion.normalize.NormalizedPosition;
import dev.hedge.ingestion.normalize.Normalizer;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns stored evidence into canonical positions: normalize, resolve, append.
 *
 * <p>Written as a pure walk over raw observations so the same code can both populate the live
 * tables and rebuild history from scratch. If projection depended on anything other than the
 * stored evidence, the normalization rules and the identity mappings, then replaying the past
 * could not reproduce it — which is the invariant Layer 44 exists to check.
 */
public final class CanonicalProjector {

    private final SecurityIdentifierRegistry securities;
    private final AccountMappingRegistry accounts;
    private final UnresolvedIdentifierLog unresolved;

    public CanonicalProjector(
            SecurityIdentifierRegistry securities,
            AccountMappingRegistry accounts,
            UnresolvedIdentifierLog unresolved) {
        this.securities = Objects.requireNonNull(securities, "a security registry is required");
        this.accounts = Objects.requireNonNull(accounts, "an account registry is required");
        this.unresolved = Objects.requireNonNull(unresolved, "an unresolved log is required");
    }

    /** What a projection produced, including everything it could not use and why. */
    public record Projection(
            List<Observation<Position>> positions, List<String> rejected, List<String> unmapped) {

        public Projection {
            positions = List.copyOf(positions);
            rejected = List.copyOf(rejected);
            unmapped = List.copyOf(unmapped);
        }

        public int usable() {
            return positions.size();
        }
    }

    /**
     * Projects one batch. Nothing is written here; the caller decides whether this is a live
     * ingest or a replay, and the two must produce identical results from identical evidence.
     *
     * @param recordUnmapped true for a live ingest, false for a replay — a replay must not add
     *     new entries to a human's work queue for identifiers that were already triaged
     */
    public Projection project(
            RawObservationStore store,
            BatchId batch,
            SourceDescriptor source,
            Normalizer<NormalizedPosition> normalizer,
            boolean recordUnmapped) {
        Objects.requireNonNull(store, "a raw store is required");
        Objects.requireNonNull(batch, "a batch is required");
        Objects.requireNonNull(source, "a source descriptor is required");
        Objects.requireNonNull(normalizer, "a normalizer is required");

        List<Observation<Position>> positions = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> unmappedIdentifiers = new ArrayList<>();

        for (StoredObservation observation : store.observationsIn(batch)) {
            NormalizationResult<NormalizedPosition> result = normalizer.normalize(observation, source);
            if (result instanceof NormalizationResult.Rejected<NormalizedPosition> refusal) {
                rejected.add(observation.id() + ": " + refusal.reason());
                continue;
            }
            NormalizedPosition normalized =
                    ((NormalizationResult.Accepted<NormalizedPosition>) result).value();

            Resolution<SecurityId> security =
                    securities.resolve(normalized.identifierKind(), normalized.identifier());
            Resolution<AccountId> account =
                    accounts.resolve(source.provider(), normalized.accountCode());

            if (security instanceof Resolution.Unmapped<SecurityId> gap) {
                unmappedIdentifiers.add(gap.kind() + ":" + gap.identifier());
                if (recordUnmapped) {
                    unresolved.record(gap.kind(), gap.identifier(), source.provider(),
                            observation.id(), observation.receivedAt(), gap.reason());
                }
                continue;
            }
            if (account instanceof Resolution.Unmapped<AccountId> gap) {
                unmappedIdentifiers.add("ACCOUNT:" + gap.identifier());
                if (recordUnmapped) {
                    unresolved.record(gap.kind(), gap.identifier(), source.provider(),
                            observation.id(), observation.receivedAt(), gap.reason());
                }
                continue;
            }

            positions.add(new Observation<>(
                    new Position(
                            ((Resolution.Resolved<AccountId>) account).value(),
                            ((Resolution.Resolved<SecurityId>) security).value(),
                            new Quantity(normalized.quantity())),
                    observation.effectiveAt(),
                    observation.receivedAt(),
                    new Provenance(source.provider(), observation.id(),
                            ((NormalizationResult.Accepted<NormalizedPosition>) result)
                                    .normalizationVersion())));
        }
        return new Projection(positions, rejected, unmappedIdentifiers);
    }

    /** Projects and persists. The live path. */
    public Projection projectAndStore(
            RawObservationStore store,
            CanonicalPositionStore canonical,
            BatchId batch,
            SourceDescriptor source,
            Normalizer<NormalizedPosition> normalizer) {
        Projection projection = project(store, batch, source, normalizer, true);
        ProviderId provider = source.provider();
        for (Observation<Position> position : projection.positions()) {
            canonical.append(provider, position);
        }
        return projection;
    }

    public Optional<String> firstRejection(Projection projection) {
        return projection.rejected().stream().findFirst();
    }
}
