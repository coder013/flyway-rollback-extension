package io.github.coder013.flyway.rollback;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

public class SchemaHistoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final String selectVersionsSql;
    private final String deleteVersionSql;

    public SchemaHistoryRepository(DataSource dataSource, String tableName) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        // 테이블명을 double-quote로 감싸 DB의 identifier case 규칙에서 자유롭게 함
        String quoted = "\"" + tableName + "\"";
        this.selectVersionsSql = "SELECT \"version\" FROM " + quoted + " WHERE \"success\" = true AND \"version\" IS NOT NULL";
        this.deleteVersionSql = "DELETE FROM " + quoted + " WHERE \"version\" = ?";
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
