package io.github.coder013.flyway.rollback.exception;

public class InvalidTargetVersionException extends RuntimeException {

    public InvalidTargetVersionException(String version) {
        super("Invalid target-version: \"" + version + "\". Must be a dot-separated numeric version (e.g. \"1\", \"1.2\", \"2.1.3\").");
    }
}
