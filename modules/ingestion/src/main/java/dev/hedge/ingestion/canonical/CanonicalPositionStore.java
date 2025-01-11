package dev.hedge.ingestion.canonical;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.domain.temporal.Provenance;
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
 * Positions as each provider stated them, with both times kept, and nothing ever overwritten.
 *
 * <p>The whole point is the pair of questions in {@link #asOf}. "What was true on Monday" and
 * "what did we believe on Monday" are different questions with different answers the moment a
 * correction arrives, and a store that cannot tell them apart can never explain why a published
 * number changed.
 */
public final class CanonicalPositionStore {

    private final DataSource dataSource;

    public CanonicalPositionStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public void append(ProviderId provider, Observation<Position> observation) {
        Objects.requireNonNull(provider, "a provider is required");
        Objects.requireNonNull(observation, "an observation is required");
        Position position = observation.value();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into canonical_positions (observation_id, provider_id, account_id,"
                                + " security_id, quantity, effective_at, received_at, normalization_version)"
                                + " values (?, ?, ?, ?, ?, ?, ?, ?)"
                                + " on conflict (observation_id) do nothing")) {
            statement.setString(1, observation.from().observation().value());
            statement.setString(2, provider.value());
            statement.setString(3, position.accountId().value());
            statement.setString(4, position.securityId().value());
            statement.setBigDecimal(5, position.quantity().value());
            statement.setTimestamp(6, Timestamp.from(observation.effectiveAt()));
            statement.setTimestamp(7, Timestamp.from(observation.receivedAt()));
            statement.setString(8, observation.from().normalizationVersion());
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException failure) {
            throw new IngestionException("could not append a canonical position", failure);
        }
    }

    /**
     * What this provider was saying about the business moment {@code effectiveAt}, as far as this
     * system knew at {@code knownAt}.
     *
     * <p>Both filters matter and neither is redundant. Dropping {@code effectiveAt} answers "what
     * do we hold now"; dropping {@code knownAt} answers "what do we hold now, with hindsight".
     * Only together do they reproduce a report that was published in the past.
     *
     * <p>{@code distinct on} is doing the supersession: rows are ordered newest-effective then
     * newest-received, and PostgreSQL keeps the first of each account and security. A correction
     * wins because it was received later, not because anything marked the old row obsolete.
     */
    public List<Observation<Position>> asOf(ProviderId provider, Instant effectiveAt, Instant knownAt) {
        Objects.requireNonNull(provider, "a provider is required");
        Objects.requireNonNull(effectiveAt, "a business time is required");
        Objects.requireNonNull(knownAt, "a knowledge time is required");

        List<Observation<Position>> positions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select distinct on (account_id, security_id)"
                                + " observation_id, account_id, security_id, quantity,"
                                + " effective_at, received_at, normalization_version"
                                + " from canonical_positions"
                                + " where provider_id = ? and effective_at <= ? and received_at <= ?"
                                + " order by account_id, security_id, effective_at desc, received_at desc")) {
            statement.setString(1, provider.value());
            statement.setTimestamp(2, Timestamp.from(effectiveAt));
            statement.setTimestamp(3, Timestamp.from(knownAt));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    AccountId account = AccountId.of(rows.getString(2));
                    positions.add(new Observation<>(
                            new Position(
                                    account,
                                    SecurityId.of(rows.getString(3)),
                                    new Quantity(rows.getBigDecimal(4))),
                            rows.getTimestamp(5).toInstant(),
                            rows.getTimestamp(6).toInstant(),
                            new Provenance(
                                    provider,
                                    ObservationId.of(rows.getString(1)),
                                    rows.getString(7))));
                }
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not read positions as of " + effectiveAt, failure);
        }
        return positions;
    }

    /** Every statement ever made about one holding, newest first. The audit answer. */
    public List<Observation<Position>> historyOf(
            ProviderId provider, AccountId account, SecurityId security) {
        List<Observation<Position>> history = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select observation_id, quantity, effective_at, received_at,"
                                + " normalization_version from canonical_positions"
                                + " where provider_id = ? and account_id = ? and security_id = ?"
                                + " order by received_at desc, effective_at desc")) {
            statement.setString(1, provider.value());
            statement.setString(2, account.value());
            statement.setString(3, security.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    history.add(new Observation<>(
                            new Position(account, security, new Quantity(rows.getBigDecimal(2))),
                            rows.getTimestamp(3).toInstant(),
                            rows.getTimestamp(4).toInstant(),
                            new Provenance(
                                    provider, ObservationId.of(rows.getString(1)), rows.getString(5))));
                }
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not read the history of " + security, failure);
        }
        return history;
    }

    public int count() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select count(*) from canonical_positions");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException failure) {
            throw new IngestionException("could not count canonical positions", failure);
        }
    }
}
