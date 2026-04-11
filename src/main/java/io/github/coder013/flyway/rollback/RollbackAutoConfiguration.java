package io.github.coder013.flyway.rollback;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration(before = FlywayAutoConfiguration.class)
@ConditionalOnClass(Flyway.class)
@EnableConfigurationProperties(RollbackProperties.class)
public class RollbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "flyway-extension.rollback.history", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RollbackHistoryRepository rollbackHistoryRepository(RollbackProperties properties, DataSource dataSource) {
        return new RollbackHistoryRepository(dataSource, properties.getHistory().getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    public FlywayMigrationStrategy rollbackMigrationStrategy(
            RollbackProperties properties,
            DataSource dataSource,
            ObjectProvider<RollbackHistoryRepository> historyRepositoryProvider) {
        return new RollbackMigrationStrategy(properties, dataSource, historyRepositoryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnMissingBean
    public RollbackEndpoint rollbackEndpoint(RollbackProperties properties, DataSource dataSource, Flyway flyway) {
        return new RollbackEndpoint(properties, dataSource, flyway);
    }
}
