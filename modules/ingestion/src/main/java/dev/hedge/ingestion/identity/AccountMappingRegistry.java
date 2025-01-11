package dev.hedge.ingestion.identity;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.IngestionException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Turns a provider's account code into this system's account.
 *
 * <p>Unlike a security identifier, an account code is only unique inside the provider that issued
 * it. Two custodians can both use "ACC-00001" for different clients, so the provider is part of
 * the key — treating account codes as globally unique merges two portfolios into one.
 */
public final class AccountMappingRegistry {

    private final DataSource dataSource;

    public AccountMappingRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public void map(ProviderId provider, String providerAccountCode, AccountId account) {
        Objects.requireNonNull(provider, "a provider is required");
        Objects.requireNonNull(providerAccountCode, "a provider account code is required");
        Objects.requireNonNull(account, "an account id is required");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into account_mappings (provider_id, provider_account_code, account_id)"
                                + " values (?, ?, ?)"
                                + " on conflict (provider_id, provider_account_code) do update set"
                                + " account_id = excluded.account_id")) {
            statement.setString(1, provider.value());
            statement.setString(2, providerAccountCode);
            statement.setString(3, account.value());
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException failure) {
            throw new IngestionException(
                    "could not map account " + providerAccountCode + " of " + provider, failure);
        }
    }

    public Resolution<AccountId> resolve(ProviderId provider, String providerAccountCode) {
        Objects.requireNonNull(provider, "a provider is required");
        if (providerAccountCode == null || providerAccountCode.isBlank()) {
            return new Resolution.Unmapped<>(
                    IdentifierKind.PROVIDER_SPECIFIC, String.valueOf(providerAccountCode),
                    "the account code is blank");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select account_id from account_mappings"
                                + " where provider_id = ? and provider_account_code = ?")) {
            statement.setString(1, provider.value());
            statement.setString(2, providerAccountCode.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new Resolution.Resolved<>(AccountId.of(rows.getString(1)))
                        : new Resolution.Unmapped<>(
                                IdentifierKind.PROVIDER_SPECIFIC, providerAccountCode.trim(),
                                provider + " account " + providerAccountCode.trim() + " is not mapped");
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not resolve account " + providerAccountCode, failure);
        }
    }
}
