package dev.hedge.ingestion.lineage;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.ingestion.source.IngestionException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Walks back from any canonical value to the bytes that produced it.
 *
 * <p>The walk is a join rather than a stored chain, because the canonical row is keyed by the
 * observation that produced it. Nothing has to be maintained, so nothing can drift out of step
 * with what it claims to describe.
 */
public final class LineageQuery {

    private static final String TRAIL = """
            select cp.observation_id, cp.normalization_version, cp.provider_id,
                   ib.batch_id, ib.artifact_name, ib.artifact_sha256,
                   cp.effective_at, cp.received_at, ro.payload
              from canonical_positions cp
              join raw_observations ro on ro.observation_id = cp.observation_id
              join ingest_batches   ib on ib.batch_id       = ro.batch_id
            """;

    private final DataSource dataSource;

    public LineageQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /** The evidence behind one canonical value. */
    public Optional<LineageTrail> of(ObservationId observation) {
        Objects.requireNonNull(observation, "an observation id is required");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(TRAIL + " where cp.observation_id = ?")) {
            statement.setString(1, observation.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not trace " + observation, failure);
        }
    }

    /**
     * Every statement behind one holding as it stood at a point in time — which is the shape an
     * audit question actually takes: not "what is this number" but "show me everything you were
     * told about this holding, and when".
     */
    public List<LineageTrail> forHolding(
            AccountId account, SecurityId security, Instant effectiveAt, Instant knownAt) {
        List<LineageTrail> trails = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(TRAIL
                        + " where cp.account_id = ? and cp.security_id = ?"
                        + " and cp.effective_at <= ? and cp.received_at <= ?"
                        + " order by cp.received_at desc")) {
            statement.setString(1, account.value());
            statement.setString(2, security.value());
            statement.setTimestamp(3, Timestamp.from(effectiveAt));
            statement.setTimestamp(4, Timestamp.from(knownAt));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    trails.add(read(rows));
                }
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not trace " + security, failure);
        }
        return trails;
    }

    /** The proportion of canonical values that can be traced. The target is all of them. */
    public double lineageCoverage() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select (select count(*) from canonical_positions cp"
                                + " join raw_observations ro on ro.observation_id = cp.observation_id"
                                + " join ingest_batches ib on ib.batch_id = ro.batch_id) as traced,"
                                + " (select count(*) from canonical_positions) as total");
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return 1.0;
            }
            long traced = rows.getLong(1);
            long total = rows.getLong(2);
            return total == 0 ? 1.0 : (double) traced / total;
        } catch (SQLException failure) {
            throw new IngestionException("could not measure lineage coverage", failure);
        }
    }

    private static LineageTrail read(ResultSet rows) throws SQLException {
        return new LineageTrail(
                ObservationId.of(rows.getString(1)),
                rows.getString(2),
                ProviderId.of(rows.getString(3)),
                BatchId.of(rows.getString(4)),
                rows.getString(5),
                rows.getString(6),
                rows.getTimestamp(7).toInstant(),
                rows.getTimestamp(8).toInstant(),
                rows.getString(9));
    }
}
