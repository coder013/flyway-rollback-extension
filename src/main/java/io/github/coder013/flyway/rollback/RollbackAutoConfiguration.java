package io.github.coder013.flyway.rollback;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
    public FlywayMigrationStrategy rollbackMigrationStrategy(
            RollbackProperties properties,
            DataSource dataSource) {
        return new RollbackMigrationStrategy(properties, dataSource);
    }
}
