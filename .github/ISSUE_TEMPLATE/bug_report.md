---
name: Bug report
about: Report a bug or unexpected behavior
labels: bug
---

## Describe the bug

A clear and concise description of what the bug is.

## Minimal reproducible example

Provide the smallest possible `application.yaml`, migration/rollback SQL, and Spring Boot version that reproduces the issue.

```yaml
# application.yaml
flyway-extension:
  rollback:
    target-version: ""
```

```sql
-- V1__example.sql

-- R1__example.sql
```

## Expected behavior

What you expected to happen.

## Actual behavior

What actually happened. Include the full stack trace if an exception was thrown.

## Environment

| Field | Value |
|---|---|
| flyway-rollback-extension version | |
| Spring Boot version | |
| Flyway version | |
| Database & version | |
| Java version | |
