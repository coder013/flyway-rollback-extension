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

    private final String scriptPattern;
    private final String allScriptsPattern;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public RollbackScriptLocator() {
        this("classpath:db/rollback/");
    }

    public RollbackScriptLocator(String location) {
        String base = location.endsWith("/") ? location : location + "/";
        this.scriptPattern = base + "R%s__*.sql";
        this.allScriptsPattern = base + "R*__*.sql";
    }

    public List<String> listAllVersions() {
        try {
            Resource[] resources = resolver.getResources(allScriptsPattern);
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
        String pattern = scriptPattern.formatted(version);
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
