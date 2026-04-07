package io.github.coder013.flyway.rollback;

import io.github.coder013.flyway.rollback.exception.RollbackScriptNotFoundException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

public class RollbackScriptLocator {

    private static final String SCRIPT_PATTERN = "classpath:db/rollback/R%s__*.sql";

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

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
