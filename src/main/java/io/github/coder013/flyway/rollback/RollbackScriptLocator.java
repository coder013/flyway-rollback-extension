package io.github.coder013.flyway.rollback;

import io.github.coder013.flyway.rollback.exception.RollbackScriptNotFoundException;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class RollbackScriptLocator {

    private static final String SCRIPT_PATTERN = "classpath:db/rollback/R%s__*.sql";
    private static final String ALL_SCRIPTS_PATTERN = "classpath:db/rollback/R*__*.sql";

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public List<String> listAllVersions() {
        try {
            Resource[] resources = resolver.getResources(ALL_SCRIPTS_PATTERN);
            return Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .filter(Objects::nonNull)
                    .map(filename -> filename.replaceAll("^R(.+?)__.*\\.sql$", "$1"))
                    .sorted(Comparator.comparing(MigrationVersion::fromVersion))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public Resource locate(String version) {
        String pattern = SCRIPT_PATTERN.formatted(version);
        try {
            Resource[] resources = resolver.getResources(pattern);
            if (resources.length == 0) {
                throw new RollbackScriptNotFoundException(version);
            }
            if (resources.length > 1) {
                throw new IllegalStateException(
                        "Multiple rollback scripts found for version: " + version + ". Only one is allowed.");
            }
            return resources[0];
        } catch (IOException e) {
            throw new RollbackScriptNotFoundException(version);
        }
    }
}
