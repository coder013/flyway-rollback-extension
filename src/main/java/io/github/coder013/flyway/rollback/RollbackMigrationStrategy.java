package io.github.coder013.flyway.rollback;

import io.github.coder013.flyway.rollback.exception.InvalidTargetVersionException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;

public class RollbackMigrationStrategy implements FlywayMigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(RollbackMigrationStrategy.class);

    private final RollbackProperties properties;
    private final DataSource dataSource;
    private final RollbackScriptLocator scriptLocator;
    private final TransactionTemplate transactionTemplate;
    private final RollbackHistoryRepository historyRepository; // nullable

    public RollbackMigrationStrategy(RollbackProperties properties, DataSource dataSource) {
        this(properties, dataSource, new RollbackScriptLocator(properties.getScriptLocation()), null);
    }

    public RollbackMigrationStrategy(RollbackProperties properties, DataSource dataSource,
                                     RollbackHistoryRepository historyRepository) {
        this(properties, dataSource, new RollbackScriptLocator(properties.getScriptLocation()), historyRepository);
    }

    RollbackMigrationStrategy(RollbackProperties properties, DataSource dataSource,
                               RollbackScriptLocator scriptLocator) {
        this(properties, dataSource, scriptLocator, null);
    }

    RollbackMigrationStrategy(RollbackProperties properties, DataSource dataSource,
                               RollbackScriptLocator scriptLocator, RollbackHistoryRepository historyRepository) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.scriptLocator = scriptLocator;
        this.historyRepository = historyRepository;
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void migrate(Flyway flyway) {
        String targetVersion = properties.getTargetVersion();

        if (targetVersion == null) {
            log.debug("flyway-extension.target-version not set. Running standard migration.");
            flyway.migrate();
            return;
        }

        if (!targetVersion.matches("\\d+(\\.\\d+)*")) {
            throw new InvalidTargetVersionException(targetVersion);
        }

        log.info("flyway-extension target-version: {}", targetVersion);

        String tableName = flyway.getConfiguration().getTable();
        SchemaHistoryRepository historyRepository = new SchemaHistoryRepository(dataSource, tableName);

        List<String> versionsToRollback = historyRepository.findVersionsGreaterThan(targetVersion);

        if (versionsToRollback.isEmpty()) {
            log.info("No versions to rollback. Running migration with target {}.", targetVersion);
            migrateWithTarget(flyway, targetVersion);
            return;
        }

        List<String> ordered = versionsToRollback.stream()
                .sorted(Comparator.comparing(MigrationVersion::fromVersion).reversed())
                .toList();

        log.info("Versions to rollback (in order): {}", ordered);

        // 실행 전 모든 rollback 스크립트 존재 여부 검증 (fail-fast)
        for (String version : ordered) {
            scriptLocator.locate(version);
        }

        if (properties.isDryRun()) {
            log.info("[DRY-RUN] Would rollback versions: {}", ordered);
            for (String version : ordered) {
                Resource script = scriptLocator.locate(version);
                log.info("[DRY-RUN] Would execute: {}", script.getFilename());
            }
            log.info("[DRY-RUN] Would then migrate to target version: {}", targetVersion);
            recordHistory(targetVersion, ordered, true);
            return;
        }

        // rollback 실행 (단일 트랜잭션 — 실패 시 전체 롤백)
        transactionTemplate.execute(status -> {
            for (String version : ordered) {
                Resource script = scriptLocator.locate(version);
                log.info("Executing rollback script for version {}: {}", version, script.getFilename());
                executeScript(script);
                historyRepository.deleteVersion(version);
                log.info("Rollback complete for version {}.", version);
            }
            return null;
        });

        migrateWithTarget(flyway, targetVersion);
        recordHistory(targetVersion, ordered, false);
    }

    private void recordHistory(String targetVersion, List<String> rolledBackVersions, boolean dryRun) {
        if (historyRepository != null) {
            historyRepository.record(targetVersion, rolledBackVersions, dryRun);
        }
    }

    private void migrateWithTarget(Flyway flyway, String targetVersion) {
        Flyway constrained = Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target(MigrationVersion.fromVersion(targetVersion))
                .load();
        constrained.migrate();
    }

    private void executeScript(Resource script) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, script);
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute rollback script: " + script.getFilename(), e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
