# flyway-rollback-extension

A Spring Boot starter library that adds rollback functionality to Flyway Community Edition, replicating the paid `flyway undo` feature.

Executes user-written rollback SQL files in reverse order — no Flyway Teams license required.

[한국어 문서](#한국어)

---

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration Reference](#configuration-reference)
- [How It Works](#how-it-works)
- [Features](#features)
- [Known Limitations](#known-limitations)
- [Running Tests](#running-tests)
- [한국어](#한국어)

---

## Requirements

- Java 17+
- Spring Boot 3.x
- Flyway (provided by the consumer — `compileOnly`)

---

## Installation

**Gradle:**

```gradle
implementation 'io.github.coder013:flyway-rollback-extension:0.0.3'
```

**Maven:**

```xml
<dependency>
    <groupId>io.github.coder013</groupId>
    <artifactId>flyway-rollback-extension</artifactId>
    <version>0.0.3</version>
</dependency>
```

---

## Quick Start

### 1. Write rollback scripts

Place rollback SQL files under `src/main/resources/db/rollback/`.

**Naming rule:** `R{version}__{description}.sql`

```
src/main/resources/
└── db/
    ├── migration/
    │   ├── V4__add_status_column.sql
    │   └── V5__add_address_column.sql
    └── rollback/
        ├── R4__add_status_column.sql
        └── R5__add_address_column.sql
```

```sql
-- V4__add_status_column.sql
ALTER TABLE users ADD COLUMN status VARCHAR(50);

-- R4__add_status_column.sql  (rollback)
ALTER TABLE users DROP COLUMN status;
```

### 2. Set the target version

```yaml
# application.yaml
flyway-extension:
  rollback:
    target-version: "3"
```

The library will roll back all versions above `3` (in reverse order), then run `flyway.migrate()` up to version `3`.

---

## Configuration Reference

```yaml
flyway-extension:
  rollback:
    target-version: "3"           # Versions above this will be rolled back. Omit for standard Flyway behavior.
    dry-run: false                 # If true, logs the execution plan without touching the DB (default: false)
    script-location: classpath:db/rollback/  # Location of rollback scripts (default)
    history:
      enabled: true                # Record rollback history in a separate table (default: true)
      table-name: flyway_rollback_history  # History table name (default)
```

**Behavior matrix:**

| Configuration | DB state | Behavior |
|---|---|---|
| No `target-version` | any | Standard `flyway.migrate()` |
| `target-version: "3"` | V5 applied | Roll back V5 → V4, then migrate to V3 |
| `target-version: "5"` | V5 applied | No rollback, migrate to V5 |
| `target-version: "3"` | V3 applied (re-run) | No-op (idempotent) |
| `target-version: "2"` | V5 applied, R3 missing | `RollbackScriptNotFoundException` (no DB changes) |
| `target-version: "abc"` | any | `InvalidTargetVersionException` |

---

## How It Works

```
target-version set in application.yaml
        │
        ├─ not set → flyway.migrate() (standard behavior)
        │
        └─ set
             │
             ├─ [Validation] Invalid version format → InvalidTargetVersionException
             │
             ├─ Query flyway_schema_history for versions above target
             │
             ├─ No rollback needed (already at or below target)
             │    └─ migrate() up to target
             │
             └─ Rollback needed
                  │
                  ├─ [Pre-check] Verify all R{version}__*.sql files exist
                  │    └─ Any missing → RollbackScriptNotFoundException (no DB changes)
                  │
                  ├─ dry-run=true
                  │    ├─ Log execution plan (no DB changes)
                  │    └─ Record in flyway_rollback_history with dry_run=true
                  │
                  └─ dry-run=false
                       ├─ [Single transaction]
                       │    ├─ Execute rollback SQL in reverse version order
                       │    ├─ Delete each version from flyway_schema_history
                       │    └─ On failure: roll back entire transaction (all-or-nothing)
                       ├─ migrate() up to target
                       └─ Record in flyway_rollback_history (if history.enabled=true)
```

### Class overview

```
io.github.coder013.flyway.rollback
├── RollbackAutoConfiguration        # Spring Boot autoconfiguration entry point
├── RollbackMigrationStrategy        # Orchestrates the rollback flow
├── RollbackScriptLocator            # Resolves R{version}__*.sql from classpath:db/rollback/
├── RollbackProperties               # Binds flyway-extension.rollback.* config
├── SchemaHistoryRepository          # Reads/deletes rows in flyway_schema_history
├── RollbackHistoryRepository        # Records entries in flyway_rollback_history
├── RollbackEndpoint                 # Spring Boot Actuator @Endpoint
└── exception/
    ├── RollbackScriptNotFoundException
    └── InvalidTargetVersionException
```

---

## Features

| Feature | Status | Description |
|---|---|---|
| Rollback execution | ✅ | Executes rollback SQL in reverse order when `target-version` is set |
| Pre-validation (fail-fast) | ✅ | Confirms all rollback scripts exist before any DB changes |
| Script discovery | ✅ | Auto-resolves `classpath:db/rollback/R{version}__*.sql` |
| History cleanup | ✅ | Removes rolled-back versions from `flyway_schema_history` |
| Forward migration | ✅ | Runs `flyway.migrate()` up to target after rollback |
| Standard mode | ✅ | Falls back to normal Flyway behavior when `target-version` is not set |
| Custom history table | ✅ | Respects `spring.flyway.table` setting |
| Spring Boot autoconfiguration | ✅ | Zero-config SPI registration |
| Transaction safety | ✅ | Entire rollback runs in a single transaction (all-or-nothing on DDL-capable DBs) |
| Version format validation | ✅ | Throws `InvalidTargetVersionException` on invalid input |
| Idempotency | ✅ | Re-running with the same target is a no-op |
| Dry-run mode | ✅ | Logs execution plan without touching the DB |
| Rollback history | ✅ | Records rollback events in `flyway_rollback_history` |
| Actuator endpoint | ✅ | Exposes current state at `/actuator/flyway-rollback` |

---

## Known Limitations

| Limitation | Details |
|---|---|
| Partial rollback on DDL failure | On DBs that do not support DDL transactions (MySQL, MariaDB, H2), a mid-rollback DDL failure may leave the schema in a partial state. PostgreSQL provides full all-or-nothing guarantees. |
| `flyway repair` not supported | Behavior is untested when `flyway_schema_history` contains `success=false` rows. |
| One rollback script per version | Having two or more `R{version}__*.sql` files for the same version throws `IllegalStateException`. |
| MySQL/MariaDB extra dependency | Flyway 9+ requires the `flyway-mysql` module for MySQL/MariaDB support. |

### Design decisions

**Why `FlywayMigrationStrategy`?**
Spring Boot invokes this bean instead of the default `flyway.migrate()` call when it is registered. It is the official extension point — no Flyway internals are patched.

**Why pre-validation (fail-fast)?**
Failing mid-rollback leaves the DB in an intermediate state. Verifying all scripts exist before touching the DB guarantees atomic failure.

**Why not auto-generate rollback scripts?**
Reversing DDL is inherently incomplete. `ALTER TABLE ADD COLUMN` reverses to `DROP COLUMN`, but dropping a column with data is a business decision. Users must write rollback scripts explicitly.

---

## Running Tests

### Unit tests (H2, no Docker)

```bash
./gradlew test
```

### Integration tests (Testcontainers — requires Docker)

```bash
./gradlew integrationTestPostgres   # PostgreSQL 16
./gradlew integrationTestMysql      # MySQL 8.0
./gradlew integrationTestMariadb    # MariaDB 11
./gradlew integrationTest           # All DBs
```

> OrbStack users: the socket path is auto-detected — no extra env vars needed.

### CI (GitHub Actions)

Runs automatically on push to `main` and on pull requests.

```
Unit Tests (H2)
Integration Tests (postgres)  ─┐
Integration Tests (mysql)      ├─ parallel
Integration Tests (mariadb)   ─┘
```

---

## Actuator endpoint

Add `spring-boot-starter-actuator` to your project and expose the endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "flyway-rollback,flyway,health"
```

```json
GET /actuator/flyway-rollback

{
  "appliedVersions": ["1", "2", "3"],
  "rollbackScriptVersions": ["2", "3"],
  "targetVersion": "2",
  "dryRun": false
}
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

---

# 한국어

Flyway Community(무료) 버전에서 롤백 기능을 사용할 수 있도록 해주는 Spring Boot 스타터 라이브러리입니다.

Flyway Teams(유료)의 `flyway undo`와 동일한 방식으로 동작하며, 사용자가 직접 작성한 rollback SQL 파일을 역순으로 실행합니다.

---

## 목차

- [요구사항 및 설치](#요구사항-및-설치)
- [빠른 시작](#빠른-시작)
- [설정 레퍼런스](#설정-레퍼런스)
- [아키텍처](#아키텍처)
- [구현 기능](#구현-기능)
- [알려진 제약](#알려진-제약)
- [테스트 실행](#테스트-실행)

---

## 요구사항 및 설치

**요구사항**
- Java 17+
- Spring Boot 3.x
- Flyway (소비자가 제공 — `compileOnly`)

**Gradle:**

```gradle
implementation 'io.github.coder013:flyway-rollback-extension:0.0.3'
```

**Maven:**

```xml
<dependency>
    <groupId>io.github.coder013</groupId>
    <artifactId>flyway-rollback-extension</artifactId>
    <version>0.0.3</version>
</dependency>
```

---

## 빠른 시작

### 1. Rollback 스크립트 작성

`src/main/resources/db/rollback/` 경로에 rollback SQL 파일을 작성합니다.

**파일명 규칙**: `R{version}__{description}.sql`

```
src/main/resources/
└── db/
    ├── migration/
    │   ├── V4__add_status_column.sql
    │   └── V5__add_address_column.sql
    └── rollback/
        ├── R4__add_status_column.sql
        └── R5__add_address_column.sql
```

```sql
-- V4__add_status_column.sql
ALTER TABLE users ADD COLUMN status VARCHAR(50);

-- R4__add_status_column.sql  (rollback)
ALTER TABLE users DROP COLUMN status;
```

### 2. target-version 설정

```yaml
flyway-extension:
  rollback:
    target-version: "3"
```

target보다 높은 버전들을 역순으로 롤백하고, `flyway.migrate()`를 target까지 실행합니다.

---

## 설정 레퍼런스

```yaml
flyway-extension:
  rollback:
    target-version: "3"        # 이 버전 이후를 롤백. 미설정 시 표준 Flyway 동작
    dry-run: false             # true 시 DB 변경 없이 실행 계획만 로그 출력 (기본값: false)
    script-location: classpath:db/rollback/  # 롤백 스크립트 위치 (기본값)
    history:
      enabled: true            # rollback 이력 테이블 사용 여부 (기본값: true)
      table-name: flyway_rollback_history  # 이력 테이블명 (기본값)
```

**동작 표:**

| 설정 | DB 현재 상태 | 동작 |
|------|------------|------|
| `target-version` 없음 | 무관 | 일반 migrate 실행 |
| `target-version: "3"` | V5까지 적용 | V5 → V4 롤백 후 V3까지 migrate |
| `target-version: "5"` | V5까지 적용 | rollback 없음, V5까지 migrate |
| `target-version: "3"` | V3까지 적용 (재실행) | no-op (멱등성 보장) |
| `target-version: "2"` | V5까지 적용, R3 없음 | `RollbackScriptNotFoundException` (DB 무변경) |
| `target-version: "abc"` | 무관 | `InvalidTargetVersionException` |

---

## 아키텍처

### 실행 흐름

```
application.yaml에 target-version 설정
        │
        ├─ target-version 없음 → flyway.migrate() (표준 동작)
        │
        └─ target-version 있음
             │
             ├─ [형식 검증] 숫자 버전 형식이 아니면 → InvalidTargetVersionException
             │
             ├─ flyway_schema_history에서 target보다 높은 버전 조회
             │
             ├─ 롤백 불필요 (target 이하만 적용된 상태, 멱등성 보장)
             │    └─ target으로 제한한 migrate 실행
             │
             └─ 롤백 필요
                  │
                  ├─ [사전 검증] 모든 R{version}__*.sql 존재 여부 확인
                  │    └─ 하나라도 없으면 → RollbackScriptNotFoundException (DB 무변경)
                  │
                  ├─ dry-run=true
                  │    ├─ 실행 계획 로그 출력 (DB 변경 없음)
                  │    └─ flyway_rollback_history에 dry_run=true 기록
                  │
                  └─ dry-run=false
                       ├─ [단일 트랜잭션]
                       │    ├─ 버전 역순으로 각 rollback SQL 실행
                       │    ├─ 각 버전 flyway_schema_history에서 삭제
                       │    └─ 실패 시 전체 롤백 (all-or-nothing)
                       ├─ target 버전으로 제한한 migrate 실행
                       └─ flyway_rollback_history에 기록 (history.enabled=true 시)
```

### 클래스 의존 관계

```
RollbackAutoConfiguration
    ├─ creates RollbackMigrationStrategy
    │               ├─ uses RollbackProperties         (target-version, dry-run 읽기)
    │               ├─ uses RollbackScriptLocator      (R{v}__*.sql 탐색)
    │               ├─ uses SchemaHistoryRepository    (flyway_schema_history 조작)
    │               └─ uses RollbackHistoryRepository  (flyway_rollback_history 기록)
    ├─ creates RollbackHistoryRepository  (history.enabled=true 시)
    └─ creates RollbackEndpoint           (spring-boot-actuator 존재 시)
```

---

## 구현 기능

| 기능 | 상태 | 설명 |
|------|------|------|
| 롤백 실행 | ✅ | target-version 설정 시 역순으로 rollback SQL 실행 |
| 사전 검증 (fail-fast) | ✅ | DB 변경 전 필요한 모든 rollback 스크립트 존재 여부 확인 |
| 스크립트 탐색 | ✅ | `classpath:db/rollback/R{version}__*.sql` 패턴으로 자동 탐색 |
| 히스토리 정리 | ✅ | 롤백된 버전을 `flyway_schema_history`에서 삭제 |
| target으로 migrate | ✅ | 롤백 완료 후 target 버전까지 순방향 마이그레이션 실행 |
| 표준 모드 유지 | ✅ | `target-version` 미설정 시 기존 Flyway 동작 그대로 유지 |
| 커스텀 히스토리 테이블 지원 | ✅ | `spring.flyway.table` 설정 적용 |
| Spring Boot 자동 구성 | ✅ | `@AutoConfiguration` / SPI 등록 |
| 트랜잭션 처리 | ✅ | 롤백 실행 전체를 단일 트랜잭션으로 묶어 all-or-nothing 보장 |
| 버전 형식 검증 | ✅ | 잘못된 `target-version` 입력 시 `InvalidTargetVersionException` |
| 멱등성 보장 | ✅ | 이미 롤백된 상태에서 재실행 시 no-op |
| dry-run 모드 | ✅ | DB 변경 없이 실행 계획을 로그로만 출력 |
| rollback 이력 기록 | ✅ | 롤백 수행 내역을 `flyway_rollback_history` 테이블에 자동 기록 |
| Actuator 엔드포인트 | ✅ | `/actuator/flyway-rollback` 으로 현재 상태 노출 |

---

## 알려진 제약

| 제약 | 내용 |
|------|------|
| 부분 롤백 가능성 | DDL 트랜잭션을 지원하지 않는 DB(MySQL, MariaDB, H2)에서 롤백 도중 DDL 실패 시 부분 반영될 수 있음. PostgreSQL 등 DDL 트랜잭션 지원 DB에서는 all-or-nothing 보장 |
| Flyway repair 미지원 | `flyway_schema_history`에 `success=false` 행이 있는 경우 동작 미검증 |
| 다중 스크립트 금지 | 동일 버전에 rollback 스크립트가 2개 이상이면 `IllegalStateException` |
| MySQL/MariaDB 의존성 | Flyway 9+에서 MySQL/MariaDB 사용 시 `flyway-mysql` 모듈 별도 추가 필요 |

---

## 테스트 실행

### 단위 테스트 (H2, Docker 불필요)

```bash
./gradlew test
```

### 통합 테스트 (Testcontainers — Docker 필요)

```bash
./gradlew integrationTestPostgres   # PostgreSQL 16
./gradlew integrationTestMysql      # MySQL 8.0
./gradlew integrationTestMariadb    # MariaDB 11
./gradlew integrationTest           # 전체
```

> OrbStack 사용 시 소켓 경로가 자동 감지되므로 별도 환경변수 설정이 필요 없습니다.

### CI (GitHub Actions)

`main` 브랜치 push 및 PR 생성 시 자동 실행됩니다.

```
Unit Tests (H2)
Integration Tests (postgres)  ─┐
Integration Tests (mysql)      ├─ 병렬 실행
Integration Tests (mariadb)   ─┘
```
