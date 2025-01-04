package dev.hedge.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;

public final class DataSources {

    private DataSources() {}

    public static DataSource pooled(String jdbcUrl, String user, String password, int maximumPoolSize) {
        return pooled(jdbcUrl, user, password, maximumPoolSize, null);
    }

    public static DataSource pooled(
            String jdbcUrl, String user, String password, int maximumPoolSize, MeterRegistry meterRegistry) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setPoolName("hedge-pool");
        config.setConnectionTimeout(5_000);
        config.setAutoCommit(false);
        if (meterRegistry != null) {
            config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        }
        return new HikariDataSource(config);
    }

    public static void close(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new IllegalStateException("could not close the connection pool", failure);
            }
        }
    }
}