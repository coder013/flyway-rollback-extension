package io.github.coder013.flyway.rollback;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import javax.sql.DataSource;

@Tag("postgres")
class PostgresRollbackIntegrationTest extends AbstractRollbackIntegrationTest {

    @Container
    static PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16");

    private static DataSource dataSource;

    @BeforeAll
    static void setUpDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(container.getJdbcUrl());
        ds.setUsername(container.getUsername());
        ds.setPassword(container.getPassword());
        dataSource = ds;
    }

    @Override
    protected DataSource getDataSource() {
        return dataSource;
    }
}
