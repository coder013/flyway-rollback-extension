package io.github.coder013.flyway.rollback;

import io.github.coder013.flyway.rollback.exception.RollbackScriptNotFoundException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RollbackMigrationStrategyTest {

    private EmbeddedDatabase dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName(UUID.randomUUID().toString())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);

        // V1~V5 전부 적용된 상태로 시작
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterEach
    void tearDown() {
        dataSource.shutdown();
    }

    @Test
    void whenTargetVersionIsNull_thenMigrateNormally() {
        RollbackProperties properties = new RollbackProperties(); // targetVersion = null

        strategy(properties).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
        assertColumnExists("USERS", "STATUS");
        assertColumnExists("USERS", "ADDRESS");
    }

    @Test
    void whenTargetVersionEqualsMaxApplied_thenNoRollback() {
        RollbackProperties properties = propertiesWithTarget("5");

        strategy(properties).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
        assertColumnExists("USERS", "STATUS");
        assertColumnExists("USERS", "ADDRESS");
    }

    @Test
    void whenTargetVersionIsLower_thenRollbackVersionsAboveAndMigrateToTarget() {
        RollbackProperties properties = propertiesWithTarget("3");

        strategy(properties).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3"));
        assertColumnNotExists("USERS", "STATUS");  // V4 롤백됨
        assertColumnNotExists("USERS", "ADDRESS"); // V5 롤백됨
    }

    @Test
    void whenRollbackScriptIsMissing_thenThrowExceptionWithNoDbChanges() {
        // target=2이면 V3,V4,V5 롤백 필요 → R3__*.sql 없어서 예외 발생
        RollbackProperties properties = propertiesWithTarget("2");

        assertThatThrownBy(() -> strategy(properties).migrate(buildFlyway()))
                .isInstanceOf(RollbackScriptNotFoundException.class)
                .hasMessageContaining("3");

        // 예외 발생 전 검증 단계에서 실패했으므로 DB 변경 없음
        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
        assertColumnExists("USERS", "STATUS");
        assertColumnExists("USERS", "ADDRESS");
    }

    private RollbackMigrationStrategy strategy(RollbackProperties properties) {
        return new RollbackMigrationStrategy(properties, dataSource);
    }

    private Flyway buildFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }

    private RollbackProperties propertiesWithTarget(String version) {
        RollbackProperties properties = new RollbackProperties();
        properties.setTargetVersion(version);
        return properties;
    }

    private void assertAppliedVersions(List<String> expected) {
        List<String> actual = jdbcTemplate.queryForList(
                "SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" = true AND \"version\" IS NOT NULL ORDER BY \"installed_rank\"",
                String.class);
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private void assertColumnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        assertThat(count).isGreaterThan(0);
    }

    private void assertColumnNotExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        assertThat(count).isZero();
    }
}
