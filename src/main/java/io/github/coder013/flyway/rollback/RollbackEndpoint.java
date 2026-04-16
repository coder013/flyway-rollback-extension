package io.github.coder013.flyway.rollback;

import org.flywaydb.core.Flyway;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import javax.sql.DataSource;
import java.util.List;

@Endpoint(id = "flyway-rollback")
public class RollbackEndpoint {

    private final RollbackProperties properties;
    private final DataSource dataSource;
    private final Flyway flyway;
    private final RollbackScriptLocator scriptLocator;

    public RollbackEndpoint(RollbackProperties properties, DataSource dataSource, Flyway flyway) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.scriptLocator = new RollbackScriptLocator(properties.getScriptLocation());
    }

    @ReadOperation
    public RollbackInfo info() {
        String tableName = flyway.getConfiguration().getTable();
        SchemaHistoryRepository historyRepository = new SchemaHistoryRepository(dataSource, tableName);
        List<String> appliedVersions = historyRepository.findAllVersions();
        List<String> scriptVersions = scriptLocator.listAllVersions();
        return new RollbackInfo(appliedVersions, scriptVersions, properties.getTargetVersion(), properties.isDryRun());
    }

    public record RollbackInfo(
            List<String> appliedVersions,
            List<String> rollbackScriptVersions,
            String targetVersion,
            boolean dryRun
    ) {}
}
