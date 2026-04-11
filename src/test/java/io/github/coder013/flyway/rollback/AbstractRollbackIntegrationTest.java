package io.github.coder013.flyway.rollback;

import io.github.coder013.flyway.rollback.exception.RollbackScriptNotFoundException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
abstract class AbstractRollbackIntegrationTest {

    protected JdbcTemplate jdbcTemplate;

    protected abstract DataSource getDataSource();

    @BeforeEach
    void setUp() {
        DataSource ds = getDataSource();
        jdbcTemplate = new JdbcTemplate(ds);

        // 매 테스트마다 DB를 깨끗한 상태로 초기화 후 V1~V5 적용
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void whenTargetVersionIsLower_thenRollbackAndMigrate() {
        strategy(propertiesWithTarget("3")).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3"));
        assertColumnNotExists("users", "status");
        assertColumnNotExists("users", "address");
    }

    @Test
    void whenRollbackScriptIsMissing_thenThrowWithNoDbChanges() {
        // target=2 → V3,V4,V5 롤백 필요 → R3 없어서 실패
        assertThatThrownBy(() -> strategy(propertiesWithTarget("2")).migrate(buildFlyway()))
                .isInstanceOf(RollbackScriptNotFoundException.class)
                .hasMessageContaining("3");

        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
    }

    @Test
    void whenDryRun_thenNoDbChanges() {
        RollbackProperties properties = propertiesWithTarget("3");
        properties.setDryRun(true);

        strategy(properties).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
        assertColumnExists("users", "status");
        assertColumnExists("users", "address");
    }

    @Test
    void whenHistoryEnabled_thenRecordsRollback() {
        RollbackProperties properties = propertiesWithTarget("3");
        RollbackHistoryRepository historyRepo = new RollbackHistoryRepository(
                getDataSource(), "flyway_rollback_history");

        new RollbackMigrationStrategy(properties, getDataSource(), historyRepo).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3"));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_rollback_history WHERE target_version = '3'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RollbackMigrationStrategy strategy(RollbackProperties properties) {
        return new RollbackMigrationStrategy(properties, getDataSource());
    }

    private Flyway buildFlyway() {
        return Flyway.configure()
                .dataSource(getDataSource())
                .locations("classpath:db/migration")
                .load();
    }

    private RollbackProperties propertiesWithTarget(String version) {
        RollbackProperties properties = new RollbackProperties();
        properties.setTargetVersion(version);
        return properties;
    }

    private void assertAppliedVersions(List<String> expected) {
        SchemaHistoryRepository repo = new SchemaHistoryRepository(getDataSource(), "flyway_schema_history");
        assertThat(repo.findAllVersions()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private void assertColumnExists(String table, String column) {
        try {
            jdbcTemplate.queryForList("SELECT " + column + " FROM " + table + " WHERE 1=0");
        } catch (DataAccessException e) {
            throw new AssertionError("Expected column '" + column + "' to exist in '" + table + "' but it does not", e);
        }
    }

    private void assertColumnNotExists(String table, String column) {
        try {
            jdbcTemplate.queryForList("SELECT " + column + " FROM " + table + " WHERE 1=0");
            throw new AssertionError("Expected column '" + column + "' to NOT exist in '" + table + "' but it does");
        } catch (DataAccessException ignored) {
            // 예상된 실패 — 컬럼 없음 확인
        }
    }
}
