package dev.hedge.ingestion.raw;

import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.temporal.ObservationKind;
import dev.hedge.ingestion.source.IngestionException;
import dev.hedge.ingestion.source.RawBatch;
import dev.hedge.ingestion.source.RawRecord;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * The evidence store: what each provider actually said, kept exactly as they said it.
 *
 * <p>Nothing here is ever updated or deleted. When a parser turns out to be wrong — and one
 * eventually will — everything downstream can be rebuilt from these rows without asking a
 * custodian to resend six months of files. A pipeline that parses and discards cannot recover from
 * its own bugs.
 */
public final class RawObservationStore {

    private final DataSource dataSource;

    public RawObservationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /** Records who a provider is and what it is allowed to tell us. Safe to call repeatedly. */
    public void registerProvider(SourceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "a source descriptor is required");
        String provides = descriptor.provides().stream()
                .map(ObservationKind::name)
                .sorted()
                .collect(Collectors.joining(","));
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into providers"
                            + " (provider_id, display_name, identifier_kind, delivery_style, format, provides)"
                            + " values (?, ?, ?, ?, ?, ?)"
                            + " on conflict (provider_id) do update set"
                            + " identifier_kind = excluded.identifier_kind,"
                            + " delivery_style = excluded.delivery_style,"
                            + " format = excluded.format,"
                            + " provides = excluded.provides")) {
                statement.setString(1, descriptor.provider().value());
                statement.setString(2, descriptor.provider().value());
                statement.setString(3, descriptor.identifierKind().name());
                statement.setString(4, descriptor.deliveryStyle().name());
                statement.setString(5, descriptor.format());
                statement.setString(6, provides);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new IngestionException("could not register provider " + descriptor.provider(), failure);
        }
    }

    /**
     * Stores a batch and all of its records in one transaction.
     *
     * @throws DuplicateArtifactException when this provider has already delivered these exact bytes
     */
    public StoredBatch store(RawBatch batch) {
        Objects.requireNonNull(batch, "a batch is required");
        ProviderId provider = batch.source().provider();
        BatchId batchId = RawIds.batchId(provider, batch.artifact().sha256());
        BatchStatus status = statusOf(batch);

        StoredBatch stored = new StoredBatch(
                batchId,
                provider,
                batch.artifact().name(),
                batch.artifact().sha256(),
                batch.artifact().sizeInBytes(),
                batch.receivedAt(),
                batch.completeness().declaredCount(),
                batch.size(),
                status,
                batch.completeness().problem());

        try (Connection connection = dataSource.getConnection()) {
            try {
                writeBatch(connection, stored);
                writeObservations(connection, batchId, batch);
                connection.commit();
                return stored;
            } catch (SQLException failure) {
                connection.rollback();
                if (isUniqueViolation(failure)) {
                    throw new DuplicateArtifactException(provider + " has already delivered "
                            + batch.artifact().name() + " (sha256 " + batch.artifact().sha256()
                            + "); the same bytes are never ingested twice");
                }
                throw new IngestionException("could not store batch " + batchId, failure);
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not obtain a connection", failure);
        }
    }

    private static void writeBatch(Connection connection, StoredBatch batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into ingest_batches (batch_id, provider_id, artifact_name, artifact_sha256,"
                        + " artifact_bytes, received_at, declared_count, parsed_count, status, problem)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, batch.id().value());
            statement.setString(2, batch.provider().value());
            statement.setString(3, batch.artifactName());
            statement.setString(4, batch.artifactSha256());
            statement.setInt(5, batch.artifactBytes());
            statement.setTimestamp(6, Timestamp.from(batch.receivedAt()));
            if (batch.declaredCount().isPresent()) {
                statement.setInt(7, batch.declaredCount().orElseThrow());
            } else {
                statement.setNull(7, java.sql.Types.INTEGER);
            }
            statement.setInt(8, batch.parsedCount());
            statement.setString(9, batch.status().name());
            statement.setString(10, batch.problem().orElse(null));
            statement.executeUpdate();
        }
    }

    private static void writeObservations(Connection connection, BatchId batchId, RawBatch batch)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into raw_observations (observation_id, batch_id, sequence_in_batch,"
                        + " provider_record_id, provider_version, effective_at, received_at, payload)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (RawRecord record : batch.records()) {
                statement.setString(1, RawIds.observationId(batchId, record.sequence()).value());
                statement.setString(2, batchId.value());
                statement.setInt(3, record.sequence());
                statement.setString(4, record.providerRecordId().orElse(null));
                statement.setString(5, record.providerVersion().orElse(null));
                statement.setTimestamp(6, Timestamp.from(record.effectiveAt()));
                statement.setTimestamp(7, Timestamp.from(batch.receivedAt()));
                statement.setString(8, record.payload());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public Optional<StoredBatch> findBatch(BatchId id) {
        Objects.requireNonNull(id, "a batch id is required");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select provider_id, artifact_name, artifact_sha256, artifact_bytes,"
                                + " received_at, declared_count, parsed_count, status, problem"
                                + " from ingest_batches where batch_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                int declared = rows.getInt(6);
                return Optional.of(new StoredBatch(
                        id,
                        ProviderId.of(rows.getString(1)),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getInt(4),
                        rows.getTimestamp(5).toInstant(),
                        rows.wasNull() ? Optional.empty() : Optional.of(declared),
                        rows.getInt(7),
                        BatchStatus.valueOf(rows.getString(8)),
                        Optional.ofNullable(rows.getString(9))));
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not read batch " + id, failure);
        }
    }

    /** Every record of a batch, in the order the provider wrote them. */
    public List<StoredObservation> observationsIn(BatchId batchId) {
        Objects.requireNonNull(batchId, "a batch id is required");
        List<StoredObservation> observations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select observation_id, sequence_in_batch, provider_record_id, provider_version,"
                                + " effective_at, received_at, payload from raw_observations"
                                + " where batch_id = ? order by sequence_in_batch")) {
            statement.setString(1, batchId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    observations.add(new StoredObservation(
                            dev.hedge.domain.id.ObservationId.of(rows.getString(1)),
                            batchId,
                            rows.getInt(2),
                            Optional.ofNullable(rows.getString(3)),
                            Optional.ofNullable(rows.getString(4)),
                            rows.getTimestamp(5).toInstant(),
                            rows.getTimestamp(6).toInstant(),
                            rows.getString(7)));
                }
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not read observations for batch " + batchId, failure);
        }
        return observations;
    }

    /** Batches oldest first — the order a replay must follow to rebuild history faithfully. */
    public List<BatchId> batchesInReceiptOrder() {
        List<BatchId> batches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select batch_id from ingest_batches order by received_at, batch_id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                batches.add(BatchId.of(rows.getString(1)));
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not list batches", failure);
        }
        return batches;
    }

    private static BatchStatus statusOf(RawBatch batch) {
        if (batch.completeness().quarantined()) {
            return BatchStatus.QUARANTINED;
        }
        return batch.completeness().isComplete() ? BatchStatus.ACCEPTED : BatchStatus.INCOMPLETE;
    }

    private static boolean isUniqueViolation(SQLException failure) {
        return "23505".equals(failure.getSQLState());
    }
}
