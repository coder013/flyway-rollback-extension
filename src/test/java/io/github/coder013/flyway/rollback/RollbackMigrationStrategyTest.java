package io.github.coder013.flyway.rollback;

import io.github.coder013.flyway.rollback.exception.InvalidTargetVersionException;
import io.github.coder013.flyway.rollback.exception.RollbackScriptNotFoundException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;
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
    void whenTargetVersionIsBlank_thenMigrateNormally() {
        RollbackProperties properties = new RollbackProperties();
        properties.setTargetVersion(""); // empty string passed via command line (e.g. --flyway-extension.rollback.target-version=)

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
    void whenAlreadyAtTargetVersion_thenRerunIsNoOp() {
        RollbackProperties properties = propertiesWithTarget("3");

        strategy(properties).migrate(buildFlyway());
        assertAppliedVersions(List.of("1", "2", "3"));

        // 동일 target으로 재실행 — no-op
        strategy(properties).migrate(buildFlyway());
        assertAppliedVersions(List.of("1", "2", "3"));
        assertColumnNotExists("USERS", "STATUS");
        assertColumnNotExists("USERS", "ADDRESS");
    }

    @Test
    void whenTargetVersionIsInvalidFormat_thenThrowInvalidTargetVersionException() {
        assertThatThrownBy(() -> strategy(propertiesWithTarget("abc")).migrate(buildFlyway()))
                .isInstanceOf(InvalidTargetVersionException.class)
                .hasMessageContaining("abc");

        assertThatThrownBy(() -> strategy(propertiesWithTarget("1.2.abc")).migrate(buildFlyway()))
                .isInstanceOf(InvalidTargetVersionException.class)
                .hasMessageContaining("1.2.abc");

        assertThatThrownBy(() -> strategy(propertiesWithTarget("v1")).migrate(buildFlyway()))
                .isInstanceOf(InvalidTargetVersionException.class)
                .hasMessageContaining("v1");
    }

    @Test
    void whenRollbackScriptFailsMidway_thenTransactionRollsBackAllChanges() {
        // R5는 no-op DML, R4는 실행 시 실패하는 DML로 교체
        // (H2는 DDL에서 묵시적 커밋이 발생하므로 트랜잭션 검증은 DML로 진행)
        RollbackScriptLocator failingLocator = new RollbackScriptLocator() {
            @Override
            public Resource locate(String version) {
                if ("5".equals(version)) {
                    return new ByteArrayResource("UPDATE users SET name = name WHERE 1 = 0;".getBytes(), "R5__noop.sql");
                }
                if ("4".equals(version)) {
                    return new ByteArrayResource("DELETE FROM nonexistent_table_xyz;".getBytes(), "R4__bad.sql");
                }
                return super.locate(version);
            }
        };

        RollbackProperties properties = propertiesWithTarget("3");
        RollbackMigrationStrategy strategy = new RollbackMigrationStrategy(properties, dataSource, failingLocator);

        assertThatThrownBy(() -> strategy.migrate(buildFlyway()))
                .isInstanceOf(RuntimeException.class);

        // 트랜잭션 롤백 — V5 롤백도 취소되어 원래 상태 유지
        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
        assertColumnExists("USERS", "STATUS");
        assertColumnExists("USERS", "ADDRESS");
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

    @Test
    void whenDryRun_thenNoDbChangesAndLogsActions() {
        RollbackProperties properties = propertiesWithTarget("3");
        properties.setDryRun(true);

        strategy(properties).migrate(buildFlyway());

        // dry-run: DB 변경 없음
        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
        assertColumnExists("USERS", "STATUS");
        assertColumnExists("USERS", "ADDRESS");
    }

    @Test
    void whenDryRunWithMissingScript_thenThrowsBeforeAnyChange() {
        RollbackProperties properties = propertiesWithTarget("2"); // R3 없음
        properties.setDryRun(true);

        // dry-run이어도 fail-fast 검증은 동일하게 동작
        assertThatThrownBy(() -> strategy(properties).migrate(buildFlyway()))
                .isInstanceOf(RollbackScriptNotFoundException.class)
                .hasMessageContaining("3");

        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));
    }

    @Test
    void whenHistoryEnabled_thenRecordsRollbackAfterSuccess() {
        RollbackProperties properties = propertiesWithTarget("3");
        RollbackHistoryRepository historyRepo = new RollbackHistoryRepository(dataSource, "flyway_rollback_history");

        new RollbackMigrationStrategy(properties, dataSource, historyRepo).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM \"flyway_rollback_history\"");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("target_version")).isEqualTo("3");
        assertThat(rows.get(0).get("rolled_back_versions")).isEqualTo("5,4");
        assertThat(rows.get(0).get("dry_run")).isEqualTo(false);
    }

    @Test
    void whenDryRunWithHistory_thenRecordsDryRunEntry() {
        RollbackProperties properties = propertiesWithTarget("3");
        properties.setDryRun(true);
        RollbackHistoryRepository historyRepo = new RollbackHistoryRepository(dataSource, "flyway_rollback_history");

        new RollbackMigrationStrategy(properties, dataSource, historyRepo).migrate(buildFlyway());

        // DB 변경 없음
        assertAppliedVersions(List.of("1", "2", "3", "4", "5"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM \"flyway_rollback_history\"");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("dry_run")).isEqualTo(true);
    }

    @Test
    void rollbackEndpoint_showsAppliedVersionsAndAvailableScripts() {
        RollbackEndpoint endpoint = new RollbackEndpoint(new RollbackProperties(), dataSource, buildFlyway());
        RollbackEndpoint.RollbackInfo info = endpoint.info();

        assertThat(info.appliedVersions()).containsExactlyInAnyOrder("1", "2", "3", "4", "5");
        assertThat(info.rollbackScriptVersions()).containsExactlyInAnyOrder("4", "5");
        assertThat(info.targetVersion()).isNull();
        assertThat(info.dryRun()).isFalse();
    }

    @Test
    void whenCustomScriptLocation_thenUsesScriptsFromThatLocation() {
        RollbackProperties properties = propertiesWithTarget("3");
        properties.setScriptLocation("classpath:db/custom-rollback/");

        new RollbackMigrationStrategy(properties, dataSource).migrate(buildFlyway());

        assertAppliedVersions(List.of("1", "2", "3"));
        assertColumnNotExists("USERS", "STATUS");
        assertColumnNotExists("USERS", "ADDRESS");
    }

    @Test
    void rollbackEndpoint_reflectsConfiguredTargetAndDryRun() {
        RollbackProperties properties = propertiesWithTarget("3");
        properties.setDryRun(true);

        RollbackEndpoint endpoint = new RollbackEndpoint(properties, dataSource, buildFlyway());
        RollbackEndpoint.RollbackInfo info = endpoint.info();

        assertThat(info.targetVersion()).isEqualTo("3");
        assertThat(info.dryRun()).isTrue();
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
