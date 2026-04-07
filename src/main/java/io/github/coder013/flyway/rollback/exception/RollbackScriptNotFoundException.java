package io.github.coder013.flyway.rollback.exception;

public class RollbackScriptNotFoundException extends RuntimeException {

    public RollbackScriptNotFoundException(String version) {
        super("Rollback script not found for version: " + version + ". Expected file at classpath:db/rollback/R" + version + "__*.sql");
    }
}
