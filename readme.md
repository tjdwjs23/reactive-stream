# ⬢ Hexagonal Architecture Board API

> **Strict Hexagonal Architecture (Ports and Adapters)** implementation using **Kotlin** & **Spring Boot 4**.

이 프로젝트는 **순수 도메인 로직의 격리**와 **유연한 어댑터 구조**를 목표로 하는 헥사고날 아키텍처 기반의 게시판 API입니다. 외부 시스템(Web, Database)이 변경되어도 핵심 비즈니스 로직(Domain)은 영향받지 않도록 설계되었습니다.

## 🛠 Tech Stack

* **Language**: Kotlin (JDK 21)
* **Framework**: Spring Boot 4.0.1
* **Persistence**: Spring Data JPA (Hibernate), PostgreSQL
* **Migration**: Flyway
* **Build Tool**: Gradle
* **Architecture**: Hexagonal Architecture (Ports and Adapters)
* **Test**: Kotest (BehaviorSpec), MockK, Testcontainers
* **Lint**: ktlint
* **CI**: GitHub Actions

## 📂 Project Structure

패키지 구조는 기술적인 계층이 아닌 **아키텍처의 의도**를 명확히 드러내도록 구성되었습니다.

```text
demo.hexagonal.hexagonalback
├── 📂 adapter                 # [Infra] 외부 세계와 소통하는 어댑터
│   ├── 📂 in                  # Driving Adapter (요청을 받아들이는 곳)
│   │   └── 📂 web             # Web Controller, Web DTO, GlobalExceptionHandler
│   └── 📂 out                 # Driven Adapter (요청을 내보내는 곳)
│       └── 📂 persistence     # JPA Entity, Repository Impl, Mapper
│
├── 📂 application             # [App] 도메인과 어댑터를 연결하는 오케스트레이션
│   ├── 📂 port                # 인터페이스 (Port) 정의
│   │   ├── 📂 in              # UseCase Interface (Input Port), Self-Validating Command
│   │   └── 📂 out             # Repository Interface (Output Port)
│   └── 📂 service             # UseCase 구현체 (트랜잭션 관리, 흐름 제어)
│
└── 📂 domain                  # [Core] 외부 의존성이 전혀 없는 순수 비즈니스 로직
    ├── 📂 exception           # 도메인 비즈니스 예외 (BoardException)
    └── 📂 model               # 핵심 도메인 모델 (Pure Kotlin Class)
```

## 📐 Architecture Principles

이 프로젝트는 헥사고날 아키텍처의 핵심 원칙을 **절대적으로 준수**합니다.

### 1. 의존성 규칙 (Dependency Rule)
모든 의존성은 **바깥쪽(Adapter)에서 안쪽(Domain)** 으로만 향합니다.
* `Domain`은 `Application`, `Adapter`에 대해 전혀 알지 못합니다.
* `Application`은 `Adapter`에 대해 알지 못합니다 (Port 인터페이스를 통해서만 소통).
* **JPA Entity(@Entity)** 와 **Domain Model(Pure Class)** 은 철저히 분리되어 있으며, `Mapper`를 통해 변환됩니다.

### 2. 도메인 중심 설계 (Rich Domain Model)
* **Service**는 단순히 로직의 흐름(Orchestration)만 제어합니다.
* 실제 상태 변경과 비즈니스 규칙 검증은 **Domain Model** 내부의 메서드가 책임집니다.

### 3. 포트와 어댑터 (Ports and Adapters)
* **In-Port (UseCase)**: 클라이언트가 애플리케이션에 무엇을 요청할 수 있는지 정의합니다.
* **Out-Port (Repository Port)**: 애플리케이션이 데이터를 저장/조회하기 위해 무엇이 필요한지 정의합니다.

## 📝 API Specification

### 게시글 (Board)

| Method | URI | Description |
| :--- | :--- | :--- |
| `POST` | `/api/boards` | 게시글 생성 |
| `GET` | `/api/boards` | 전체 게시글 조회 |
| `GET` | `/api/boards/{id}` | 특정 게시글 단건 조회 |
| `PUT` | `/api/boards/{id}` | 게시글 수정 |
| `DELETE` | `/api/boards/{id}` | 게시글 삭제 |

## 🧪 Test Strategy

헥사고날 아키텍처의 각 계층을 **독립적으로** 테스트합니다. 모든 테스트는 [Kotest](https://kotest.io/) `BehaviorSpec`의 `Given / When / Then` DSL로 의도를 명확히 표현하고, mock은 [MockK](https://mockk.io/)를 사용합니다.

### 테스트 구조

```text
src/test
└── demo.hexagonal.hexagonalback
    ├── HexagonalBackApplicationTests          # 전체 ApplicationContext 로딩 검증 (Testcontainers)
    ├── domain/model/
    │   └── BoardTest                          # Domain 단위 테스트
    ├── application/port/in/
    │   └── CreateBoardCommandTest             # Command 자가 검증 테스트
    ├── application/service/
    │   └── BoardServiceTest                   # Service 단위 테스트 (MockK)
    ├── adapter/in/web/
    │   └── BoardControllerTest                # Controller 슬라이스 테스트 (MockMvc + MockK)
    ├── adapter/out/persistence/
    │   └── BoardPersistenceAdapterTest        # 영속성 계층 통합 테스트 (Testcontainers)
    └── support/
        └── PostgresTestContainer              # Postgres 컨테이너 싱글톤 (위 두 통합 테스트가 공유)
```

### 계층별 테스트 전략

| 테스트 대상 | 테스트 유형 | 주요 검증 내용 |
| :--- | :--- | :--- |
| `BoardTest` | Domain 단위 | `update()` 후 새 인스턴스 반환, 원본 불변성 보장, 빈 제목 시 `BoardValidationException` 발생 |
| `CreateBoardCommandTest` | Command 자가 검증 | 빈 제목 / 10자 미만 내용 시 `IllegalArgumentException` 발생, 유효 입력 시 정상 생성 |
| `BoardServiceTest` | Service 단위 (MockK) | `BoardRepositoryPort`를 Mock하여 각 UseCase 메서드의 흐름 및 예외 위임 검증 |
| `BoardControllerTest` | Web 슬라이스 (MockMvc + MockK) | HTTP 상태 코드, 응답 JSON, Location 헤더, `GlobalExceptionHandler` 동작 검증 |
| `BoardPersistenceAdapterTest` | 영속성 통합 (Testcontainers) | 실제 PostgreSQL 컨테이너에 `save`/`findById`/`findAll`/`deleteById`가 정상 반영되는지 검증 |
| `HexagonalBackApplicationTests` | 전체 컨텍스트 (Testcontainers) | 모든 빈이 정상 구성되어 ApplicationContext가 로딩되는지 검증 |

> Kotest 기본 isolation mode(`SingleInstance`)에서는 스펙 인스턴스가 한 번만 생성되므로, `Given` 블록마다 mock/fixture를 **새로** 만들어야 테스트 간 호출 기록이 섞이지 않습니다 (`ServiceFixture`, `ControllerFixture` 참고).

### 통합 테스트와 Testcontainers

`BoardPersistenceAdapterTest`와 `HexagonalBackApplicationTests`는 `PostgresTestContainer` 싱글톤을 공유합니다.

1. 테스트 실행 시 `postgres:16-alpine` 이미지를 받아 컨테이너를 띄웁니다 (호스트의 **랜덤 포트**에 매핑되며, `application.yml`의 `localhost:5432`와는 무관합니다).
2. `@DynamicPropertySource`가 컨테이너의 실제 JDBC URL/계정 정보를 `spring.datasource.*`에 런타임으로 주입합니다.
3. ApplicationContext가 뜨면서 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 그 컨테이너에 자동으로 적용해 스키마를 구성합니다.
4. JVM(Gradle 테스트 프로세스) 종료 시 Testcontainers의 Ryuk이 컨테이너를 자동으로 정리합니다.

즉 별도 설정 없이 `./gradlew test`만 실행해도 PostgreSQL이 자동으로 뜨고 내려갑니다. **Docker가 실행 중이어야** 합니다.

### 테스트 실행

```bash
# 전체 테스트 실행
./gradlew test

# 특정 클래스만 실행
./gradlew test --tests "*.BoardTest"
./gradlew test --tests "*.CreateBoardCommandTest"
./gradlew test --tests "*.BoardServiceTest"
./gradlew test --tests "*.BoardControllerTest"
./gradlew test --tests "*.BoardPersistenceAdapterTest"
```

## ✅ Lint (ktlint)

[ktlint](https://github.com/JLLeitschuh/ktlint-gradle)의 표준 룰셋(standard, 실험적 룰 제외)을 사용합니다. 헥사고날 패키지 네이밍(`adapter.in`, `application.port.in`)이 코틀린 예약어 `in`을 포함해 `package-name` 룰만 `.editorconfig`에서 비활성화했습니다.

```bash
# 스타일 검사
./gradlew ktlintCheck

# 자동 수정
./gradlew ktlintFormat

# commit 전 자동으로 ktlintCheck를 수행하는 git pre-commit hook 등록 (최초 1회)
./gradlew addKtlintCheckGitPreCommitHook
```

린트로 인해 빌드가 실패하면 `build/reports/ktlint`에서 원인을 확인하고, 자동 수정이 안 되는 항목(예: wildcard import)은 직접 수정합니다.

## 🚀 Getting Started

### Prerequisites
* JDK 21+
* Docker (로컬 실행용 PostgreSQL 및 테스트용 Testcontainers에 필요)
* Gradle

### Run

```bash
# Clone Repository
git clone <repository-url>

# Build
./gradlew clean build

# 로컬 실행용 PostgreSQL 기동 (application.yml: localhost:5432/hexagonal, hexagonal/hexagonal1234)
# 직접 PostgreSQL을 띄우거나 docker run으로 대체 가능

# Run
./gradlew bootRun
```

`bootRun`은 `src/main/resources/application.yml`에 고정된 `jdbc:postgresql://localhost:5432/hexagonal`로 접속하므로, 로컬에 해당 정보로 접속 가능한 PostgreSQL이 떠 있어야 합니다. 애플리케이션 기동 시 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 자동으로 적용해 스키마를 구성하며(`ddl-auto: validate`로 Hibernate는 그 스키마와 엔티티 매핑이 일치하는지만 검증), 새 변경은 `Vn__설명.sql` 형식의 새 마이그레이션 파일을 추가하는 방식으로 관리합니다.

## 🔄 CI

`main` 브랜치 push/PR마다 [GitHub Actions](.github/workflows/ci.yml)가 JDK 21 환경에서 `ktlintCheck`와 `test`(Testcontainers 기반 통합 테스트 포함)를 자동 실행합니다.

---

### 💡 Key Code Features

#### 1. Self-Validating Commands
Controller에서 넘어온 데이터는 UseCase로 진입하기 전, Command 객체 생성 시점에 **생성자 내부에서 유효성이 검증**됩니다. 이를 통해 애플리케이션 계층은 항상 유효한 데이터만 다룹니다.

#### 2. Isolation of Persistence
DB 테이블 구조(JPA Entity)가 변경되어도 비즈니스 로직(Domain Model)은 영향을 받지 않습니다. `BoardPersistenceAdapter`가 중간에서 `Mapper`를 이용해 두 객체 간의 변환을 담당합니다.

#### 3. Common Response Envelope & Global Exception Handling
모든 응답은 `ApiResponse<T>`(`success`, `data`, `error`)로 감싸 클라이언트가 일관된 형태로 파싱하도록 합니다. `GlobalExceptionHandler`(`@RestControllerAdvice`)가 도메인 예외부터 잘못된 요청, 예상치 못한 예외까지 한 곳에서 매핑합니다.

| 예외 | HTTP Status | error.code |
| :--- | :--- | :--- |
| `BoardNotFoundException` | 404 | `BOARD_NOT_FOUND` |
| `BoardValidationException` | 400 | `VALIDATION_ERROR` |
| `IllegalArgumentException` (Command 자가 검증) | 400 | `VALIDATION_ERROR` |
| `HttpMessageNotReadableException` (잘못된 요청 Body) | 400 | `INVALID_REQUEST_BODY` |
| `MethodArgumentTypeMismatchException` (잘못된 PathVariable) | 400 | `INVALID_PARAMETER` |
| 그 외 모든 예외 | 500 | `INTERNAL_SERVER_ERROR` |

```json
// 성공
{ "success": true, "data": { "id": 1, "title": "..." }, "error": null }

// 실패
{ "success": false, "data": null, "error": { "code": "BOARD_NOT_FOUND", "message": "Board not found with id: 999" } }
```
