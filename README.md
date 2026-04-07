# flyway-rollback-extension

Flyway Community(무료) 버전에서 롤백 기능을 사용할 수 있도록 해주는 Spring Boot 스타터 라이브러리입니다.

Flyway Teams(유료)의 `flyway undo`와 동일한 방식으로 동작하며, 사용자가 직접 작성한 rollback SQL 파일을 역순으로 실행합니다.

## 목차

- [현재 구현 현황](#현재-구현-현황)
- [실행 계획 (로드맵)](#실행-계획-로드맵)
- [고려할 점](#고려할-점)
- [아키텍처](#아키텍처)
- [사용법](#사용법)
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

### 구현된 클래스

```
io.github.coder013.flyway.rollback
├── RollbackAutoConfiguration        # Spring Boot 자동 구성 진입점
├── RollbackMigrationStrategy        # 롤백 흐름 오케스트레이터
├── RollbackScriptLocator            # R{version}__*.sql 파일 탐색
├── RollbackProperties               # flyway-extension.* 설정 바인딩
├── SchemaHistoryRepository          # flyway_schema_history 조회/삭제
└── exception/
    └── RollbackScriptNotFoundException
```

### 테스트 커버리지

`RollbackMigrationStrategyTest` — JUnit 5 + H2 + AssertJ

| 테스트 | 검증 내용 |
|--------|-----------|
| `whenTargetVersionIsNull` | target-version 없으면 표준 migrate 실행 |
| `whenTargetVersionEqualsMaxApplied` | 현재 최대 버전과 target 동일 시 no-op |
| `whenTargetVersionIsLower` | V5→V4 롤백 후 V3까지 migrate, 컬럼 삭제 확인 |
| `whenRollbackScriptIsMissing` | 스크립트 누락 시 예외 발생, DB 무변경 확인 |

---

## 실행 계획 (로드맵)

### Phase 1 — 핵심 기능 (완료)

- [x] `FlywayMigrationStrategy` 기반 롤백 인터셉터 구현
- [x] 사전 검증(fail-fast) 로직
- [x] 버전 역순 rollback 실행 + 히스토리 삭제
- [x] Spring Boot Autoconfiguration SPI 등록
- [x] H2 기반 통합 테스트

### Phase 2 — 안정성 강화 (예정)

- [ ] **트랜잭션 처리**: 롤백 실행 중 실패 시 부분 반영 상태 방지
  - 현재는 실패 지점 이전 버전까지만 롤백됨 (문서화된 동작)
  - 단일 트랜잭션으로 묶어 all-or-nothing 보장 고려
- [ ] **버전 형식 검증**: `target-version`에 잘못된 문자열 입력 시 명확한 에러 메시지
- [ ] **이미 롤백된 버전 처리**: target이 현재 DB보다 낮은 상태에서 재실행 시 멱등성 보장

### Phase 3 — 운영 편의성 (예정)

- [ ] **dry-run 모드**: 실제 실행 없이 어떤 스크립트가 실행될지 로깅만
- [ ] **rollback 이력 기록**: 롤백 수행 내역을 별도 테이블에 기록 (감사 추적)
- [ ] **Spring Boot Actuator 엔드포인트**: 현재 적용 버전, rollback 가능 여부 노출

### Phase 4 — 배포 (예정)

- [ ] Maven Central 배포 (`io.github.coder013:flyway-rollback-extension`)
- [ ] GitHub Actions CI/CD 파이프라인
- [ ] 다중 DB 검증 (PostgreSQL, MySQL, MariaDB)

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
| 부분 롤백 가능성 | 롤백 실행 중 실패 시 이전 버전까지만 롤백됨 (트랜잭션 미구현) |
| 반복 실행 주의 | `target-version`을 설정한 채 애플리케이션을 재시작하면 이미 rollback된 버전을 다시 시도할 수 있음 |
| Flyway repair 미지원 | `flyway_schema_history`에 `success=false` 행이 있는 경우 동작 미검증 |
| 다중 스크립트 금지 | 동일 버전에 rollback 스크립트가 2개 이상이면 `IllegalStateException` |

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
             ├─ flyway_schema_history에서 target보다 높은 버전 조회
             │
             ├─ 롤백 불필요 (target == 현재 최대 버전)
             │    └─ target으로 제한한 migrate 실행
             │
             └─ 롤백 필요
                  │
                  ├─ [사전 검증] 모든 R{version}__*.sql 존재 여부 확인
                  │    └─ 하나라도 없으면 → RollbackScriptNotFoundException (DB 무변경)
                  │
                  ├─ 버전 역순으로 각 rollback SQL 실행
                  ├─ 각 버전 flyway_schema_history에서 삭제
                  │
                  └─ target 버전으로 제한한 migrate 실행
```

### 클래스 의존 관계

```
RollbackAutoConfiguration
    └─ creates RollbackMigrationStrategy
                ├─ uses RollbackProperties      (target-version 읽기)
                ├─ uses RollbackScriptLocator   (R{v}__*.sql 탐색)
                └─ uses SchemaHistoryRepository (flyway_schema_history 조작)
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

### 2. target-version 설정

```yaml
# application.yaml
flyway-extension:
  target-version: "3"   # 이 버전 이후의 마이그레이션을 롤백
```

| 설정 | DB 현재 상태 | 동작 |
|------|------------|------|
| `target-version` 없음 | 무관 | 일반 migrate 실행 |
| `target-version: 3` | V5까지 적용 | V5 → V4 롤백 후 V3까지 migrate |
| `target-version: 5` | V5까지 적용 | rollback 없음, V5까지 migrate |
| `target-version: 2` | V5까지 적용, R3 없음 | `RollbackScriptNotFoundException` (DB 무변경) |

### 3. 동작 규칙

**스크립트 누락 시 예외 (DB 무변경)**

```
RollbackScriptNotFoundException: Rollback script not found for version: 4.
Expected file at classpath:db/rollback/R4__*.sql
```

**버전 역순 실행**

V5 → V4 순서로 실행됩니다. 각 실행 후 `flyway_schema_history`에서 해당 버전 행이 삭제됩니다.

**실패 시 해당 지점까지만 반영**

V5, V4 롤백 중 V4에서 실패하면 V5만 롤백된 상태로 남습니다 (트랜잭션 미적용).

---

## 요구사항 및 설치

**요구사항**

- Java 17+
- Spring Boot 3.x
- Flyway (별도 의존성 필요, `compileOnly`로 제공)

**설치**

> 현재 로컬 빌드만 지원합니다. Maven Central 배포 예정.

```bash
./gradlew publishToMavenLocal
```

사용할 프로젝트의 `build.gradle`에 추가:

```gradle
implementation 'io.github.coder013:flyway-rollback-extension:0.0.1-SNAPSHOT'
```

Maven (`pom.xml`):

```xml
<dependency>
    <groupId>io.github.coder013</groupId>
    <artifactId>flyway-rollback-extension</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**빌드 명령어**

```bash
./gradlew build          # 컴파일 + 테스트
./gradlew test           # 테스트만
./gradlew assemble       # JAR 생성 (테스트 제외)
./gradlew publishToMavenLocal   # 로컬 Maven 레포에 설치
```
