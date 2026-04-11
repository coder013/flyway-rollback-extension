package io.github.coder013.flyway.rollback;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

public class SchemaHistoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final String selectVersionsSql;
    private final String deleteVersionSql;

    public SchemaHistoryRepository(DataSource dataSource, String tableName) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        String q = detectQuoteChar(dataSource);
        String quoted = q + tableName + q;
        this.selectVersionsSql = "SELECT " + q + "version" + q + " FROM " + quoted +
                " WHERE " + q + "success" + q + " = true AND " + q + "version" + q + " IS NOT NULL" +
                " ORDER BY " + q + "installed_rank" + q;
        this.deleteVersionSql = "DELETE FROM " + quoted + " WHERE " + q + "version" + q + " = ?";
    }

    private static String detectQuoteChar(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String q = conn.getMetaData().getIdentifierQuoteString();
            return " ".equals(q) ? "" : q;
        } catch (Exception e) {
            return "\"";
        }
    }

    public List<String> findVersionsGreaterThan(String targetVersion) {
        MigrationVersion target = MigrationVersion.fromVersion(targetVersion);
        return jdbcTemplate.queryForList(selectVersionsSql, String.class)
                .stream()
                .filter(v -> MigrationVersion.fromVersion(v).compareTo(target) > 0)
                .toList();
    }

    public List<String> findAllVersions() {
        return jdbcTemplate.queryForList(selectVersionsSql, String.class);
    }

    public void deleteVersion(String version) {
        jdbcTemplate.update(deleteVersionSql, version);
    }
}
