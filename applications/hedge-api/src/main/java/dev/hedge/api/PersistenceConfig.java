package dev.hedge.api;

import dev.hedge.persistence.DataSources;
import dev.hedge.persistence.FundRepository;
import dev.hedge.persistence.SchemaMigrator;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PersistenceConfig {

    @Bean(destroyMethod = "close")
    DataSource dataSource(
            @Value("${hedge.db.url}") String url,
            @Value("${hedge.db.user}") String user,
            @Value("${hedge.db.password}") String password,
            @Value("${hedge.db.pool-size}") int poolSize,
            MeterRegistry meterRegistry) {
        DataSource dataSource = DataSources.pooled(url, user, password, poolSize, meterRegistry);
        SchemaMigrator.migrate(dataSource);
        return dataSource;
    }

    @Bean
    FundRepository fundRepository(DataSource dataSource) {
        return new FundRepository(dataSource);
    }
}