# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git Conventions

- **Commit messages must be written in English.**
- **Never push directly to `main`.** Always create a feature branch and open a PR.

## Build & Test Commands

```bash
# Build (compile + test)
./gradlew build

# Run all unit tests (H2, fast)
./gradlew test

# Run a single test class
./gradlew test --tests RollbackMigrationStrategyTest

# Run a single test method
./gradlew test --tests "RollbackMigrationStrategyTest#methodName"

# Run all integration tests (requires Docker — PostgreSQL, MySQL, MariaDB via Testcontainers)
./gradlew integrationTest

# Run integration tests for a specific DB
./gradlew integrationTestPostgres
./gradlew integrationTestMysql
./gradlew integrationTestMariadb

# Assemble JAR without tests
./gradlew assemble

# Install to local Maven repo (for testing as a dependency locally)
./gradlew publishToMavenLocal

# Clean
./gradlew clean
```

## Project Purpose

This is a **Spring Boot starter library** (not an application) that adds rollback functionality to Flyway Community Edition, replicating the paid `flyway undo` feature. It is published via `publishToMavenLocal`; there is no `main()` to run.

**Coordinates**: `io.github.coder013:flyway-rollback-extension:0.0.3`  
**Requires**: Java 17+, Spring Boot 3.x

## Architecture

Spring Boot's autoconfiguration mechanism registers `RollbackMigrationStrategy` as the `FlywayMigrationStrategy` bean, which intercepts Flyway's initialization.

**Execution flow when `flyway-extension.rollback.target-version` is set**:
1. `RollbackAutoConfiguration` → creates `RollbackMigrationStrategy`
2. `RollbackMigrationStrategy.migrate()` is called by Spring Boot instead of default Flyway behavior
3. Validates target version format (dot-separated numeric; e.g. `"3"`, `"1.2"`)
4. Pre-validates all needed rollback scripts exist (fail-fast — no DB changes until all scripts confirmed)
5. Executes rollback SQL scripts in **reverse version order** (highest first) inside a **single transaction**
6. Deletes each rolled-back version from `flyway_schema_history`
7. Runs `flyway.migrate()` constrained to the target version
8. Records the rollback event in `flyway_rollback_history` (if history is enabled)

**Key classes**:
- `RollbackMigrationStrategy` — orchestrates the rollback flow; entry point
- `RollbackScriptLocator` — resolves `R{version}__*.sql` files from the configured `script-location`
- `SchemaHistoryRepository` — queries and deletes rows from `flyway_schema_history`
- `RollbackHistoryRepository` — creates and inserts into `flyway_rollback_history` (rollback audit log)
- `RollbackProperties` — `@ConfigurationProperties(prefix = "flyway-extension.rollback")`, exposes `target-version`, `dry-run`, `script-location`, `history.*`
- `RollbackEndpoint` — Spring Boot Actuator endpoint (`/actuator/flyway-rollback`) showing applied versions and available rollback scripts
- `RollbackAutoConfiguration` — Spring Boot SPI entry, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## File Naming Convention

- Migration scripts (Flyway standard): `V{version}__{description}.sql`
- Rollback scripts (this library): `R{version}__{description}.sql`
- Rollback scripts live in the path set by `script-location` (default: `classpath:db/rollback/`); one script per version; multiple for same version throws `IllegalStateException`

## Configuration

```yaml
flyway-extension:
  rollback:
    target-version: "3"           # Versions above this will be rolled back. Omit for standard Flyway behavior.
    dry-run: false                 # If true, logs the execution plan without touching the DB (default: false)
    script-location: classpath:db/rollback/  # Location of rollback scripts (default)
    history:
      enabled: true                # Record rollback events in a separate table (default: true)
      table-name: flyway_rollback_history  # History table name (default)
```

## Testing

Tests use **JUnit 5 + H2 in-memory DB + AssertJ**.

**Unit tests** (`RollbackMigrationStrategyTest`) — no Docker required:
- No target version → standard migration
- Target equals current max version → no-op (idempotent)
- Target lower than current → rollback then migrate
- Invalid version format → `InvalidTargetVersionException`
- Mid-rollback failure → transaction rolls back all changes
- Missing rollback script → `RollbackScriptNotFoundException` before any DB changes
- Dry-run mode → no DB changes, history still recorded
- History recording → `flyway_rollback_history` row written correctly
- Actuator endpoint → returns applied versions and available rollback script versions
- Custom script location → scripts resolved from user-specified path

**Integration tests** (`AbstractRollbackIntegrationTest` + DB-specific subclasses) — requires Docker:
- Same scenarios as unit tests, executed against real PostgreSQL, MySQL, and MariaDB

Test SQL lives in `src/test/resources/db/migration/` (V1–V5), `src/test/resources/db/rollback/` (only R4, R5 — intentionally missing R1–R3 to test the pre-validation failure path), and `src/test/resources/db/custom-rollback/` (R4, R5 — used to test custom `script-location`).

## Dependencies

All runtime dependencies are `compileOnly` (consumers provide them):
- `org.flywaydb:flyway-core`
- `org.springframework.boot:spring-boot-autoconfigure`
- `org.springframework:spring-jdbc`
- `org.slf4j:slf4j-api`
- `org.springframework.boot:spring-boot-actuator` (optional — only needed for the actuator endpoint)
