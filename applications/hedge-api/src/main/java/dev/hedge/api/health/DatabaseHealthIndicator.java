package dev.hedge.api.health;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
@Component("db")
class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    DatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select 1")) {
            return rows.next()
                    ? Health.up().withDetail("database", "PostgreSQL").build()
                    : Health.down().withDetail("reason", "the check query returned nothing").build();
        } catch (SQLException failure) {
            return Health.down(failure).build();
        }
    }
}