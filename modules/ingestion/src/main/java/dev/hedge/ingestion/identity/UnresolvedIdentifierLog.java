package dev.hedge.ingestion.identity;

import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.ingestion.source.IdentifierKind;
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
import javax.sql.DataSource;

/**
 * The queue of identifiers nobody can map yet.
 *
 * <p>Deliberately a queue rather than a log line. An unmapped identifier is a position this system
 * cannot see, which means exposure is understated, which means something is unhedged and nobody
 * knows. It needs an owner and a count, not a line in a file that scrolls past.
 */
public final class UnresolvedIdentifierLog {

    private final DataSource dataSource;

    public UnresolvedIdentifierLog(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public record Unresolved(
            IdentifierKind kind,
            String identifier,
            ProviderId provider,
            ObservationId firstObservation,
            Instant firstSeenAt,
            Instant lastSeenAt,
            int occurrences,
            String reason) {}

    /** Records a sighting, counting repeats rather than piling up duplicate rows. */
    public void record(
            IdentifierKind kind,
            String identifier,
            ProviderId provider,
            ObservationId observation,
            Instant seenAt,
            String reason) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into unresolved_identifiers (identifier_kind, identifier_value,"
                                + " provider_id, first_observation_id, first_seen_at, last_seen_at,"
                                + " occurrences, reason) values (?, ?, ?, ?, ?, ?, 1, ?)"
                                + " on conflict (identifier_kind, identifier_value, provider_id)"
                                + " do update set last_seen_at = excluded.last_seen_at,"
                                + " occurrences = unresolved_identifiers.occurrences + 1")) {
            statement.setString(1, kind.name());
            statement.setString(2, identifier);
            statement.setString(3, provider.value());
            statement.setString(4, observation.value());
            statement.setTimestamp(5, Timestamp.from(seenAt));
            statement.setTimestamp(6, Timestamp.from(seenAt));
            statement.setString(7, reason);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException failure) {
            throw new IngestionException("could not record unresolved " + kind + " " + identifier, failure);
        }
    }

    /** Most frequently seen first: the gaps costing the most visibility get fixed first. */
    public List<Unresolved> outstanding() {
        List<Unresolved> outstanding = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select identifier_kind, identifier_value, provider_id, first_observation_id,"
                                + " first_seen_at, last_seen_at, occurrences, reason"
                                + " from unresolved_identifiers"
                                + " order by occurrences desc, identifier_value");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                outstanding.add(new Unresolved(
                        IdentifierKind.valueOf(rows.getString(1)),
                        rows.getString(2),
                        ProviderId.of(rows.getString(3)),
                        ObservationId.of(rows.getString(4)),
                        rows.getTimestamp(5).toInstant(),
                        rows.getTimestamp(6).toInstant(),
                        rows.getInt(7),
                        rows.getString(8)));
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not list unresolved identifiers", failure);
        }
        return outstanding;
    }

    /** Clears an entry once a mapping has been supplied. */
    public void resolved(IdentifierKind kind, String identifier) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "delete from unresolved_identifiers"
                                + " where identifier_kind = ? and identifier_value = ?")) {
            statement.setString(1, kind.name());
            statement.setString(2, identifier);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException failure) {
            throw new IngestionException("could not clear unresolved " + kind + " " + identifier, failure);
        }
    }
}
