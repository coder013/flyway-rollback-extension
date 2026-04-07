# flyway-rollback-extension

Flyway Community(무료) 버전에서 롤백 기능을 사용할 수 있도록 해주는 Spring Boot 스타터 라이브러리입니다.

Flyway Teams(유료)의 `flyway undo`와 동일한 방식으로 동작하며, 사용자가 직접 작성한 rollback SQL 파일을 역순으로 실행합니다.

## 동작 방식

```
application.yaml에 target-version 설정
        │
        ├─ target-version 없음 → 일반 flyway.migrate() 실행
        │
        └─ target-version 있음
             │
             ├─ flyway_schema_history에서 target보다 높은 버전 조회
             ├─ 없으면 → target으로 제한한 migrate 실행
             │
             └─ 있으면
                  ├─ 모든 rollback 스크립트 존재 여부 사전 검증
                  ├─ 버전 역순으로 rollback SQL 실행
                  ├─ flyway_schema_history에서 해당 버전 행 삭제
                  └─ target 버전으로 제한한 migrate 실행
```

## 요구사항

- Java 17+
- Spring Boot 3.x
- Flyway (별도 의존성 필요)

## 설치

> 현재 로컬 빌드만 지원합니다. Maven Central 배포 예정.

```bash
./gradlew publishToMavenLocal
```

사용할 프로젝트의 `build.gradle`에 추가:

```gradle
implementation 'io.github.coder013:flyway-rollback-extension:0.0.1-SNAPSHOT'
```

## 사용법

### 1. Rollback 스크립트 작성

`src/main/resources/db/rollback/` 경로에 rollback SQL 파일을 작성합니다.

파일명 규칙: `R{version}__{description}.sql`

```
src/main/resources/
├── db/
│   ├── migration/
│   │   ├── V4__add_status_column.sql      ← Flyway 일반 마이그레이션
│   │   └── V5__add_address_column.sql
│   └── rollback/
│       ├── R4__add_status_column.sql      ← 직접 작성한 rollback 스크립트
│       └── R5__add_address_column.sql
```

예시:

```sql
-- V4__add_status_column.sql
ALTER TABLE users ADD COLUMN status VARCHAR(50);

-- R4__add_status_column.sql
ALTER TABLE users DROP COLUMN status;
```

### 2. target-version 설정

```yaml
# application.yaml
flyway-extension:
  target-version: 3   # 이 버전 이후의 마이그레이션을 롤백
```

| 설정 | 동작 |
|------|------|
| `target-version` 없음 | 일반 마이그레이션 실행 |
| `target-version: 3` (DB가 V5까지 적용된 상태) | V5 → V4 순서로 rollback 후 V3까지 migrate |
| `target-version: 5` (DB가 V5까지 적용된 상태) | rollback 없음, V5까지 migrate |

## 동작 규칙

### Rollback 스크립트 누락 시 예외 발생

Rollback 대상 버전 중 대응하는 `R{version}__*.sql` 파일이 없으면, **DB를 변경하기 전에** 예외를 던집니다.

```
RollbackScriptNotFoundException: Rollback script not found for version: 4.
Expected file at classpath:db/rollback/R4__*.sql
```

### 실패 시 해당 지점까지만 반영

Rollback 실행 중 특정 버전에서 실패하면 이후 버전의 rollback은 실행되지 않습니다.

### flyway_schema_history 처리

Rollback이 완료된 버전의 행은 `flyway_schema_history` 테이블에서 삭제됩니다.

## 주의사항

- Rollback 스크립트는 사용자가 직접 작성해야 합니다. SQL을 자동으로 역산하지 않습니다.
- `target-version`은 배포 후 제거하거나 최신 버전으로 업데이트하는 것을 권장합니다.
- Flyway의 `spring.flyway.table` 설정으로 커스텀 히스토리 테이블명을 사용하는 경우에도 정상 동작합니다.
