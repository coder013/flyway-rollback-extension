package io.github.coder013.flyway.rollback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

public class RollbackHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RollbackHistoryRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final String insertSql;

    public RollbackHistoryRepository(DataSource dataSource, String tableName) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        String q = detectQuoteChar(dataSource);
        String quoted = q + tableName + q;
        createTableIfNotExists(q, quoted);
        this.insertSql = "INSERT INTO " + quoted + " (" +
                q + "target_version" + q + ", " +
                q + "rolled_back_versions" + q + ", " +
                q + "dry_run" + q + ", " +
                q + "executed_at" + q + ") VALUES (?, ?, ?, ?)";
    }

    private void createTableIfNotExists(String q, String quotedTable) {
        String sql = "CREATE TABLE IF NOT EXISTS " + quotedTable + " (" +
                q + "target_version" + q + " VARCHAR(100) NOT NULL, " +
                q + "rolled_back_versions" + q + " VARCHAR(1000) NOT NULL, " +
                q + "dry_run" + q + " BOOLEAN NOT NULL DEFAULT FALSE, " +
                q + "executed_at" + q + " TIMESTAMP NOT NULL" +
                ")";
        jdbcTemplate.execute(sql);
        log.debug("Rollback history table ensured.");
    }

    private static String detectQuoteChar(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String q = conn.getMetaData().getIdentifierQuoteString();
            return " ".equals(q) ? "" : q;
        } catch (Exception e) {
            return "\"";
        }
    }

    public void record(String targetVersion, List<String> rolledBackVersions, boolean dryRun) {
        jdbcTemplate.update(insertSql,
                targetVersion,
                String.join(",", rolledBackVersions),
                dryRun,
                LocalDateTime.now());
        log.info("Rollback history recorded: targetVersion={}, rolledBackVersions={}, dryRun={}",
                targetVersion, rolledBackVersions, dryRun);
    }
}
