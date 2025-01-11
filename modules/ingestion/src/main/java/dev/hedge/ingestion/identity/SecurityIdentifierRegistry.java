package dev.hedge.ingestion.identity;

import dev.hedge.domain.id.SecurityId;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.IngestionException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Turns whatever a provider calls a security into the one identity this system uses.
 *
 * <p>Custodian A says {@code 037833100}, Custodian B says {@code US0378331005}, Custodian C says
 * {@code CPROV-7781}. They may all be the same instrument, and nothing in the strings says so —
 * only a mapping someone maintains does.
 */
public final class SecurityIdentifierRegistry {

    private final DataSource dataSource;

    public SecurityIdentifierRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public void map(IdentifierKind kind, String identifier, SecurityId security, String assertedBy) {
        Objects.requireNonNull(kind, "a kind is required");
        Objects.requireNonNull(identifier, "an identifier is required");
        Objects.requireNonNull(security, "a security id is required");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into security_identifiers"
                                + " (identifier_kind, identifier_value, security_id, asserted_by)"
                                + " values (?, ?, ?, ?)"
                                + " on conflict (identifier_kind, identifier_value) do update set"
                                + " security_id = excluded.security_id,"
                                + " asserted_by = excluded.asserted_by")) {
            statement.setString(1, kind.name());
            statement.setString(2, identifier);
            statement.setString(3, security.value());
            statement.setString(4, assertedBy);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException failure) {
            throw new IngestionException("could not map " + kind + " " + identifier, failure);
        }
    }

    public Resolution<SecurityId> resolve(IdentifierKind kind, String identifier) {
        Objects.requireNonNull(kind, "a kind is required");
        if (identifier == null || identifier.isBlank()) {
            return new Resolution.Unmapped<>(kind, String.valueOf(identifier), "the identifier is blank");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select security_id from security_identifiers"
                                + " where identifier_kind = ? and identifier_value = ?")) {
            statement.setString(1, kind.name());
            statement.setString(2, identifier.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new Resolution.Resolved<>(SecurityId.of(rows.getString(1)))
                        : new Resolution.Unmapped<>(kind, identifier.trim(),
                                "no security is mapped to " + kind + " " + identifier.trim());
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not resolve " + kind + " " + identifier, failure);
        }
    }

    /** Every name a security is known by, which is how a reconciliation break gets explained. */
    public List<String> namesOf(SecurityId security) {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select identifier_kind, identifier_value from security_identifiers"
                                + " where security_id = ? order by identifier_kind")) {
            statement.setString(1, security.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1) + ":" + rows.getString(2));
                }
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not list names of " + security, failure);
        }
        return names;
    }
}
