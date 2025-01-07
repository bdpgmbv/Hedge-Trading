package dev.hedge.ingestion;

import dev.hedge.persistence.DataSources;
import dev.hedge.persistence.SchemaMigrator;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

public final class IngestionTestDatabase {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/hedge_trading_test";

    private IngestionTestDatabase() {}

    public static DataSource start() {
        DataSource dataSource = DataSources.pooled(url(), user(), password(), 3);
        SchemaMigrator.migrate(dataSource);
        return dataSource;
    }

    public static void clear(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table raw_observations, ingest_batches, providers cascade");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear the ingestion tables", failure);
        }
    }

    private static String url() {
        return System.getenv().getOrDefault("HEDGE_DB_URL", DEFAULT_URL);
    }

    private static String user() {
        return System.getenv().getOrDefault("HEDGE_DB_USER", System.getProperty("user.name"));
    }

    private static String password() {
        return System.getenv().getOrDefault("HEDGE_DB_PASSWORD", "");
    }
}
