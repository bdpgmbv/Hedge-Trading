package dev.hedge.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
final class TestDatabase {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/hedge_trading_test";

    private TestDatabase() {}

    static DataSource start(int poolSize) {
        DataSource dataSource = DataSources.pooled(url(), user(), password(), poolSize);
        SchemaMigrator.migrate(dataSource);
        return dataSource;
    }

    static void clear(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("truncate table positions, cash_balances, accounts, securities, funds cascade");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear the test database", failure);
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