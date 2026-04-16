# Contributing to flyway-rollback-extension

Thank you for your interest in contributing!

## Getting Started

**Requirements:** Java 17+, Gradle (wrapper included), Docker (for integration tests)

```bash
git clone https://github.com/coder013/flyway-rollback-extension.git
cd flyway-rollback-extension
./gradlew build
```

## Running Tests

```bash
# Unit tests (H2, no Docker required)
./gradlew test

# Integration tests (Testcontainers — Docker required)
./gradlew integrationTestPostgres
./gradlew integrationTestMysql
./gradlew integrationTestMariadb
./gradlew integrationTest   # all DBs
```

## Submitting Changes

1. Fork the repository and create a branch from `main`
2. Make your changes
3. Ensure all unit tests pass: `./gradlew test`
4. Open a pull request against `main`

Please keep pull requests focused — one feature or fix per PR.

## Reporting Bugs

Open a [GitHub Issue](https://github.com/coder013/flyway-rollback-extension/issues) with:
- A minimal reproducible example
- Expected vs. actual behavior
- Java, Spring Boot, and Flyway versions

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
