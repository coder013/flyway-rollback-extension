# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build (compile + test)
./gradlew build

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests RollbackMigrationStrategyTest

# Run a single test method
./gradlew test --tests "RollbackMigrationStrategyTest#methodName"

# Assemble JAR without tests
./gradlew assemble

# Install to local Maven repo (for testing as a dependency locally)
./gradlew publishToMavenLocal

# Clean
./gradlew clean
```

## Project Purpose

This is a **Spring Boot starter library** (not an application) that adds rollback functionality to Flyway Community Edition, replicating the paid `flyway undo` feature. It is published via `publishToMavenLocal`; there is no `main()` to run.

**Coordinates**: `io.github.coder013:flyway-rollback-extension:0.0.1-SNAPSHOT`  
**Requires**: Java 17+, Spring Boot 3.x

## Architecture

Spring Boot's autoconfiguration mechanism registers `RollbackMigrationStrategy` as the `FlywayMigrationStrategy` bean, which intercepts Flyway's initialization.

**Execution flow when `flyway-extension.target-version` is set**:
1. `RollbackAutoConfiguration` → creates `RollbackMigrationStrategy`
2. `RollbackMigrationStrategy.migrate()` is called by Spring Boot instead of default Flyway behavior
3. Pre-validates all needed rollback scripts exist (fail-fast — no DB changes until all scripts confirmed)
4. Executes rollback SQL scripts in **reverse version order** (highest first)
5. Deletes each rolled-back version from `flyway_schema_history`
6. Runs `flyway.migrate()` constrained to the target version

**Key classes**:
- `RollbackMigrationStrategy` — orchestrates the rollback flow; entry point
- `RollbackScriptLocator` — resolves `R{version}__*.sql` files from `classpath:db/rollback/`
- `SchemaHistoryRepository` — queries and deletes rows from `flyway_schema_history`
- `RollbackProperties` — `@ConfigurationProperties(prefix = "flyway-extension")`, exposes `target-version`
- `RollbackAutoConfiguration` — Spring Boot SPI entry, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## File Naming Convention

- Migration scripts (Flyway standard): `V{version}__{description}.sql`
- Rollback scripts (this library): `R{version}__{description}.sql`
- Rollback scripts live in `classpath:db/rollback/`; one script per version; multiple for same version throws `IllegalStateException`

## Configuration

```yaml
flyway-extension:
  target-version: "3"  # omit or leave null for standard Flyway migration
```

## Testing

Tests use **JUnit 5 + H2 in-memory DB + AssertJ**. The single test class `RollbackMigrationStrategyTest` covers:
- No target version → standard migration
- Target equals current max version → no-op
- Target lower than current → rollback then migrate
- Missing rollback script → exception before any DB changes

Test SQL lives in `src/test/resources/db/migration/` (V1–V5) and `src/test/resources/db/rollback/` (only R4, R5 — intentionally missing R1–R3 to test the pre-validation failure path).

## Dependencies

All runtime dependencies are `compileOnly` (consumers provide them):
- `org.flywaydb:flyway-core`
- `org.springframework.boot:spring-boot-autoconfigure`
- `org.springframework:spring-jdbc`
