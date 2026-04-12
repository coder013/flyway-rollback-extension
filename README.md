# flyway-rollback-extension

Flyway Community(무료) 버전에서 롤백 기능을 사용할 수 있도록 해주는 Spring Boot 스타터 라이브러리입니다.

Flyway Teams(유료)의 `flyway undo`와 동일한 방식으로 동작하며, 사용자가 직접 작성한 rollback SQL 파일을 역순으로 실행합니다.

## 목차

- [현재 구현 현황](#현재-구현-현황)
- [실행 계획 (로드맵)](#실행-계획-로드맵)
- [고려할 점](#고려할-점)
- [아키텍처](#아키텍처)
- [사용법](#사용법)
- [테스트 실행](#테스트-실행)
- [요구사항 및 설치](#요구사항-및-설치)

---

## 현재 구현 현황

### 핵심 기능 (완료)

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
| 트랜잭션 처리 | ✅ | 롤백 실행 전체를 단일 트랜잭션으로 묶어 all-or-nothing 보장 (DDL 트랜잭션 지원 DB 기준) |
| 버전 형식 검증 | ✅ | 잘못된 `target-version` 입력 시 `InvalidTargetVersionException`으로 명확한 에러 |
| 멱등성 보장 | ✅ | 이미 롤백된 상태에서 재실행 시 no-op |
| dry-run 모드 | ✅ | DB 변경 없이 실행 계획을 로그로만 출력 |
| rollback 이력 기록 | ✅ | 롤백 수행 내역을 `flyway_rollback_history` 테이블에 자동 기록 |
| Actuator 엔드포인트 | ✅ | `/actuator/flyway-rollback` 으로 현재 상태 노출 |

### 구현된 클래스

```
io.github.coder013.flyway.rollback
├── RollbackAutoConfiguration        # Spring Boot 자동 구성 진입점
├── RollbackMigrationStrategy        # 롤백 흐름 오케스트레이터
├── RollbackScriptLocator            # R{version}__*.sql 파일 탐색
├── RollbackProperties               # flyway-extension.rollback.* 설정 바인딩
├── SchemaHistoryRepository          # flyway_schema_history 조회/삭제
├── RollbackHistoryRepository        # flyway_rollback_history 기록
├── RollbackEndpoint                 # Actuator @Endpoint
└── exception/
    ├── RollbackScriptNotFoundException
    └── InvalidTargetVersionException
```

### 테스트 커버리지

`RollbackMigrationStrategyTest` — JUnit 5 + H2 + AssertJ

| 테스트 | 검증 내용 |
|--------|-----------|
| `whenTargetVersionIsNull` | target-version 없으면 표준 migrate 실행 |
| `whenTargetVersionEqualsMaxApplied` | 현재 최대 버전과 target 동일 시 no-op |
| `whenTargetVersionIsLower` | V5→V4 롤백 후 V3까지 migrate, 컬럼 삭제 확인 |
| `whenRollbackScriptIsMissing` | 스크립트 누락 시 예외 발생, DB 무변경 확인 |
| `whenRollbackScriptFailsMidway` | 실행 중 실패 시 트랜잭션 롤백으로 이전 변경 취소 확인 |
| `whenAlreadyAtTargetVersion` | 동일 target으로 재실행 시 no-op (멱등성) |
| `whenTargetVersionIsInvalidFormat` | 잘못된 버전 형식 입력 시 `InvalidTargetVersionException` 발생 |
| `whenDryRun` | DB 변경 없이 로그만 출력 |
| `whenDryRunWithMissingScript` | dry-run이어도 스크립트 누락 시 예외 발생 |
| `whenHistoryEnabled` | 롤백 성공 후 이력 테이블에 기록 |
| `whenDryRunWithHistory` | dry-run 실행 후 dry_run=true로 이력 기록 |
| `rollbackEndpoint_showsAppliedVersionsAndAvailableScripts` | 적용된 버전과 rollback 스크립트 목록 노출 |
| `rollbackEndpoint_reflectsConfiguredTargetAndDryRun` | 설정값(target, dryRun) 반영 확인 |

#### 통합 테스트 (Testcontainers — Docker 필요)

각 DB별 4개 시나리오 × 3개 DB = **12개**

| 테스트 | 검증 내용 |
|--------|-----------|
| `whenTargetVersionIsLower_thenRollbackAndMigrate` | 실제 DB에서 롤백 후 마이그레이션 |
| `whenRollbackScriptIsMissing_thenThrowWithNoDbChanges` | 스크립트 누락 시 예외, DB 무변경 |
| `whenDryRun_thenNoDbChanges` | dry-run 시 DB 변경 없음 |
| `whenHistoryEnabled_thenRecordsRollback` | 이력 테이블 기록 |

```bash
./gradlew integrationTestPostgres   # PostgreSQL 16
./gradlew integrationTestMysql      # MySQL 8.0
./gradlew integrationTestMariadb    # MariaDB 11
./gradlew integrationTest           # 전체
```

---

## 실행 계획 (로드맵)

### Phase 1 — 핵심 기능 (완료)

- [x] `FlywayMigrationStrategy` 기반 롤백 인터셉터 구현
- [x] 사전 검증(fail-fast) 로직
- [x] 버전 역순 rollback 실행 + 히스토리 삭제
- [x] Spring Boot Autoconfiguration SPI 등록
- [x] H2 기반 통합 테스트

### Phase 2 — 안정성 강화 (완료)

- [x] **트랜잭션 처리**: 롤백 전체를 단일 트랜잭션으로 묶어 all-or-nothing 보장
- [x] **버전 형식 검증**: 잘못된 `target-version` 입력 시 `InvalidTargetVersionException`
- [x] **멱등성 보장**: 이미 롤백된 상태에서 재실행 시 no-op 동작 확인 및 테스트 명문화

### Phase 3 — 운영 편의성 (완료)

- [x] **dry-run 모드**: 실제 실행 없이 어떤 스크립트가 실행될지 로깅만
- [x] **rollback 이력 기록**: 롤백 수행 내역을 별도 테이블에 기록 (감사 추적)
- [x] **Spring Boot Actuator 엔드포인트**: 현재 적용 버전, rollback 가능 여부 노출

### Phase 4 — 배포 (완료)

- [x] Maven Central 배포 (`io.github.coder013:flyway-rollback-extension`)
- [x] **GitHub Actions CI 파이프라인**: push/PR 시 단위 테스트 + DB별 통합 테스트 병렬 실행
- [x] **다중 DB 검증**: PostgreSQL, MySQL, MariaDB — Testcontainers 통합 테스트 통과

---

## 고려할 점

### 설계 결정

**왜 `FlywayMigrationStrategy`를 사용하는가?**
Spring Boot는 `FlywayMigrationStrategy` 빈이 등록되어 있으면 기본 `flyway.migrate()` 호출 대신 이 전략을 사용합니다. Flyway 내부를 건드리지 않고 동작을 교체할 수 있는 공식 확장 지점입니다.

**왜 사전 검증(fail-fast)을 하는가?**
스크립트가 없을 때 롤백 도중 실패하면 DB가 중간 상태로 남습니다. 모든 스크립트의 존재를 DB 변경 전에 확인함으로써 원자적 실패를 보장합니다.

**왜 rollback 스크립트를 자동 생성하지 않는가?**
SQL의 역산은 DDL에서 불완전합니다. `ALTER TABLE ADD COLUMN`의 역은 `DROP COLUMN`이지만, 데이터가 있는 경우 삭제 여부는 비즈니스 결정입니다. 안전을 위해 사용자가 직접 작성하도록 설계했습니다.

### 알려진 제약

| 제약 | 내용 |
|------|------|
| 부분 롤백 가능성 | 롤백 실행 중 DDL 포함 스크립트가 실패하면 DDL을 지원하지 않는 DB(예: H2, MySQL, MariaDB)에서는 부분 반영될 수 있음. PostgreSQL 등 DDL 트랜잭션을 지원하는 DB에서는 all-or-nothing 보장 |
| Flyway repair 미지원 | `flyway_schema_history`에 `success=false` 행이 있는 경우 동작 미검증 |
| 다중 스크립트 금지 | 동일 버전에 rollback 스크립트가 2개 이상이면 `IllegalStateException` |
| MySQL/MariaDB 의존성 | Flyway 9+에서 MySQL/MariaDB 사용 시 `flyway-mysql` 모듈 별도 추가 필요 |

### 운영 권장 사항

- `target-version`은 롤백이 완료된 후 제거하거나 최신 버전으로 업데이트하세요. 설정이 남아있으면 재기동 시 불필요한 검증이 반복됩니다.
- Rollback 스크립트는 migration 스크립트와 함께 버전 관리(Git)하세요.
- 프로덕션 롤백 전 반드시 스테이징 환경에서 검증하세요.

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
                  ├─ dry-run=false
                  │    ├─ [단일 트랜잭션 시작]
                  │    │    ├─ 버전 역순으로 각 rollback SQL 실행
                  │    │    ├─ 각 버전 flyway_schema_history에서 삭제
                  │    │    └─ 실패 시 전체 롤백 (all-or-nothing)
                  │    ├─ target 버전으로 제한한 migrate 실행
                  │    └─ flyway_rollback_history에 기록 (history.enabled=true 시)
                  │
                  └─ (history.enabled=false 시 이력 기록 생략)
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

## 사용법

### 1. Rollback 스크립트 작성

`src/main/resources/db/rollback/` 경로에 rollback SQL 파일을 작성합니다.

**파일명 규칙**: `R{version}__{description}.sql`

```
src/main/resources/
└── db/
    ├── migration/
    │   ├── V4__add_status_column.sql      ← Flyway 일반 마이그레이션
    │   └── V5__add_address_column.sql
    └── rollback/
        ├── R4__add_status_column.sql      ← rollback 스크립트 (직접 작성)
        └── R5__add_address_column.sql
```

```sql
-- V4__add_status_column.sql
ALTER TABLE users ADD COLUMN status VARCHAR(50);

-- R4__add_status_column.sql  (rollback)
ALTER TABLE users DROP COLUMN status;
```

### 2. 설정

```yaml
# application.yaml
flyway-extension:
  rollback:
    target-version: "3"        # 이 버전 이후의 마이그레이션을 롤백
    dry-run: false             # true 시 DB 변경 없이 실행 계획만 로그 출력 (기본값: false)
    history:
      enabled: true            # rollback 이력 테이블 사용 여부 (기본값: true)
      table-name: flyway_rollback_history  # 이력 테이블명 (기본값)
```

**target-version 동작 표**

| 설정 | DB 현재 상태 | 동작 |
|------|------------|------|
| `target-version` 없음 | 무관 | 일반 migrate 실행 |
| `target-version: 3` | V5까지 적용 | V5 → V4 롤백 후 V3까지 migrate |
| `target-version: 5` | V5까지 적용 | rollback 없음, V5까지 migrate |
| `target-version: 3` | V3까지 적용 (재실행) | no-op (멱등성 보장) |
| `target-version: 2` | V5까지 적용, R3 없음 | `RollbackScriptNotFoundException` (DB 무변경) |
| `target-version: "abc"` | 무관 | `InvalidTargetVersionException` |

### 3. dry-run 모드

실제 DB를 건드리지 않고 어떤 스크립트가 실행될지 미리 확인할 수 있습니다. 스크립트 존재 여부 검증(fail-fast)은 동일하게 수행됩니다.

```yaml
flyway-extension:
  rollback:
    target-version: "3"
    dry-run: true
```

```
[DRY-RUN] Would rollback versions: [5, 4]
[DRY-RUN] Would execute: R5__add_address_column.sql
[DRY-RUN] Would execute: R4__add_status_column.sql
[DRY-RUN] Would then migrate to target version: 3
```

### 4. rollback 이력 기록

롤백 실행(dry-run 포함) 후 `flyway_rollback_history` 테이블에 자동으로 기록됩니다. 테이블이 없으면 앱 시작 시 자동 생성됩니다.

| 컬럼 | 내용 |
|------|------|
| `target_version` | 롤백 목표 버전 |
| `rolled_back_versions` | 실제 롤백된 버전 목록 (쉼표 구분) |
| `dry_run` | dry-run 여부 |
| `executed_at` | 실행 시각 |

이력 기록이 필요 없으면 비활성화:

```yaml
flyway-extension:
  rollback:
    history:
      enabled: false
```

### 5. Spring Boot Actuator 엔드포인트

`spring-boot-starter-actuator` 의존성이 있으면 `/actuator/flyway-rollback` 엔드포인트가 자동 등록됩니다.

```json
GET /actuator/flyway-rollback

{
  "appliedVersions": ["1", "2", "3"],
  "rollbackScriptVersions": ["2", "3"],
  "targetVersion": "2",
  "dryRun": false
}
```

노출 설정:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "flyway-rollback,flyway,health"
```

### 6. 동작 규칙

**스크립트 누락 시 예외 (DB 무변경)**

```
RollbackScriptNotFoundException: Rollback script not found for version: 4.
Expected file at classpath:db/rollback/R4__*.sql
```

**버전 역순 실행**

V5 → V4 순서로 실행됩니다. 각 실행 후 `flyway_schema_history`에서 해당 버전 행이 삭제됩니다.

**실패 시 트랜잭션 롤백**

롤백 실행 전체가 단일 트랜잭션으로 묶입니다. V5, V4 롤백 중 V4에서 실패하면 V5 롤백도 함께 취소되어 원래 상태로 복원됩니다. DDL 트랜잭션을 지원하는 DB(PostgreSQL 등)에서 보장되며, H2 등 DDL 자동 커밋 DB에서는 부분 반영될 수 있습니다.

---

## 테스트 실행

### 단위 테스트 (H2)

Docker 없이 실행 가능합니다. 모든 커밋에서 기본으로 실행됩니다.

```bash
./gradlew test
```

### 통합 테스트 (Testcontainers)

실제 DB 컨테이너를 띄워 검증합니다. **Docker가 필요합니다.**

```bash
# DB별 단독 실행
./gradlew integrationTestPostgres   # PostgreSQL 16
./gradlew integrationTestMysql      # MySQL 8.0
./gradlew integrationTestMariadb    # MariaDB 11

# 전체 실행
./gradlew integrationTest
```

> **OrbStack 사용 시** 소켓 경로가 자동 감지되므로 별도 환경변수 설정이 필요 없습니다.  
> Docker Desktop 사용 시에도 별도 설정 없이 동작합니다.

각 DB에서 검증하는 시나리오:

| 시나리오 | 내용 |
|---------|------|
| 정상 롤백 | target 이하로 롤백 후 마이그레이션 실행, 스키마 변경 확인 |
| fail-fast | 스크립트 누락 시 예외 발생, DB 무변경 확인 |
| dry-run | DB 변경 없이 로그만 출력 확인 |
| 이력 기록 | `flyway_rollback_history` 테이블에 정상 기록 확인 |

### CI (GitHub Actions)

`main` 브랜치 push 및 PR 생성 시 자동 실행됩니다.

```
Unit Tests (H2)
Integration Tests (postgres)  ─┐
Integration Tests (mysql)      ├─ 병렬 실행
Integration Tests (mariadb)   ─┘
```

---

## 요구사항 및 설치

**요구사항**

- Java 17+
- Spring Boot 3.x
- Flyway (별도 의존성 필요, `compileOnly`로 제공)

**설치**

Gradle (`build.gradle`):

```gradle
implementation 'io.github.coder013:flyway-rollback-extension:0.0.1'
```

Maven (`pom.xml`):

```xml
<dependency>
    <groupId>io.github.coder013</groupId>
    <artifactId>flyway-rollback-extension</artifactId>
    <version>0.0.1</version>
</dependency>
```

**빌드 명령어**

```bash
./gradlew build                    # 컴파일 + 단위 테스트
./gradlew test                     # 단위 테스트 (H2)
./gradlew integrationTestPostgres  # 통합 테스트 — PostgreSQL
./gradlew integrationTestMysql     # 통합 테스트 — MySQL
./gradlew integrationTestMariadb   # 통합 테스트 — MariaDB
./gradlew integrationTest          # 통합 테스트 — 전체
./gradlew assemble                 # JAR 생성 (테스트 제외)
./gradlew publishToMavenLocal      # 로컬 Maven 레포에 설치
```
