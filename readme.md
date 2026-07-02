# 🌊 Reactive Stream — Board API

> **Strict Hexagonal Architecture (Ports and Adapters)** implementation using **Kotlin** & **Spring Boot 4**, on a fully **non-blocking reactive stack**: **Spring WebFlux + Kotlin Coroutines + Spring Data R2DBC**.

이 프로젝트는 **순수 도메인 로직의 격리**와 **유연한 어댑터 구조**를 목표로 하는 헥사고날 아키텍처 기반의 게시판 API입니다. 외부 시스템(Web, Database)이 변경되어도 핵심 비즈니스 로직(Domain)은 영향받지 않도록 설계되었습니다.

블로킹 스택(Spring MVC + JPA)에서 **논블로킹 리액티브 스택(WebFlux + 코루틴 + R2DBC)** 으로 전환하면서도, 도메인 모델은 **한 줄도 바뀌지 않았습니다**. 스택 교체의 충격이 어댑터/포트 경계에서 흡수되는 것이 헥사고날 아키텍처의 핵심 이점입니다.

## 🛠 Tech Stack

* **Language**: Kotlin (JDK 21)
* **Framework**: Spring Boot 4.0.1, **Spring WebFlux** (Netty, 논블로킹)
* **Concurrency**: **Kotlin Coroutines** (`suspend` / `Flow`), `kotlinx-coroutines-reactor` 브리지
* **Persistence**: **Spring Data R2DBC** (`CoroutineCrudRepository`), PostgreSQL — **JDBC 미사용**
* **Cache/Counter**: **Reactive Redis (Lettuce)** — 조회수 카운터(INCR + 주기적 DB write-back), 논블로킹
* **Schema Init**: R2DBC `ConnectionFactoryInitializer` (기동 시 `db/schema.sql` 실행)
* **Observability**: Spring Boot Actuator + **Micrometer Prometheus** (health/metrics/prometheus, HTTP 요청 지연 히스토그램), Reactor↔코루틴 컨텍스트 자동 전파
* **API Docs**: **springdoc-openapi (WebFlux)** — Swagger UI (`/swagger-ui.html`), OpenAPI JSON (`/v3/api-docs`)
* **Build Tool**: Gradle
* **Architecture**: Hexagonal Architecture (Ports and Adapters)
* **Test**: Kotest (BehaviorSpec), MockK, WebTestClient, Testcontainers
* **Lint**: ktlint
* **CI**: GitHub Actions

## 📂 Project Structure

패키지 구조는 기술적인 계층이 아닌 **아키텍처의 의도**를 명확히 드러내도록 구성되었습니다.

```text
demo.reactivestream
├── 📂 adapter                 # [Infra] 외부 세계와 소통하는 어댑터
│   ├── 📂 in                  # Driving Adapter (요청을 받아들이는 곳)
│   │   ├── 📂 web             # WebFlux Controller(suspend), Web DTO, BaseResponse/ErrorCode, GlobalExceptionHandler
│   │   └── 📂 batch           # @Scheduled 트리거 (오래된 게시글 아카이브, 조회수 플러시)
│   └── 📂 out                 # Driven Adapter (요청을 내보내는 곳)
│       ├── 📂 persistence     # R2DBC Entity, Coroutine Repository, Mapper, Schema Initializer
│       └── 📂 redis           # 조회수 카운터 어댑터 (Reactive Redis, 논블로킹)
│
├── 📂 config                  # [Infra] 횡단 관심사 설정 (관측성, OpenAPI 문서)
│
├── 📂 application             # [App] 도메인과 어댑터를 연결하는 오케스트레이션
│   ├── 📂 port                # 인터페이스 (Port) 정의
│   │   ├── 📂 in              # UseCase Interface (Input Port, suspend/Flow), Self-Validating Command
│   │   └── 📂 out             # Repository / Batch Query Interface (Output Port)
│   └── 📂 service             # UseCase 구현체 (트랜잭션 관리, 흐름 제어)
│
└── 📂 domain                  # [Core] 외부 의존성이 전혀 없는 순수 비즈니스 로직
    ├── 📂 exception           # 도메인 비즈니스 예외 (BoardException)
    └── 📂 model               # 핵심 도메인 모델 (Pure Kotlin Class)
```

## ⚡ Reactive Conventions

포트/어댑터/서비스 전 계층에 걸쳐 다음 규칙을 일관되게 적용합니다.

* **단건 I/O는 `suspend fun`**, **대용량 스트리밍 조회는 `Flow<T>`** 로 반환합니다. 포트 경계에서 `List`로 전체를 메모리에 올리지 않습니다.
* `BoardService`의 `@Transactional`은 R2DBC가 자동 구성하는 **`ReactiveTransactionManager`** 위에서 동작하며, `suspend` 함수에도 그대로 적용됩니다.
* **목록 조회는 키셋(seek) 페이지네이션**으로, 한 페이지(`size`)만 읽어 `suspend` 함수 안에서 즉시 소비합니다. 요청당 메모리/지연이 일정하며, `@Transactional(readOnly)`가 실제 읽기를 정확히 감쌉니다. (배치처럼 전체를 흘려보내야 하는 경로만 `Flow`를 유지합니다.)
* **블로킹 세계의 구동 어댑터**(예: `suspend`를 지원하지 않는 `@Scheduled` 배치 트리거)는 어댑터 내부에서 `runBlocking`으로 코루틴 세계에 연결합니다. 블로킹 경계를 어댑터 안으로 가둡니다.

## 📐 Architecture Principles

이 프로젝트는 헥사고날 아키텍처의 핵심 원칙을 **절대적으로 준수**합니다.

### 1. 의존성 규칙 (Dependency Rule)
모든 의존성은 **바깥쪽(Adapter)에서 안쪽(Domain)** 으로만 향합니다.
* `Domain`은 `Application`, `Adapter`에 대해 전혀 알지 못합니다.
* `Application`은 `Adapter`에 대해 알지 못합니다 (Port 인터페이스를 통해서만 소통).
* **R2DBC Entity(`@Table`)** 와 **Domain Model(Pure Class)** 은 철저히 분리되어 있으며, `Mapper`를 통해 변환됩니다.

### 2. 도메인 중심 설계 (Rich Domain Model)
* **Service**는 단순히 로직의 흐름(Orchestration)만 제어합니다.
* 실제 상태 변경과 비즈니스 규칙 검증은 **Domain Model** 내부의 메서드가 책임집니다 (`Board.update()`, `Board.isStale()`).

### 3. 포트와 어댑터 (Ports and Adapters)
* **In-Port (UseCase)**: 클라이언트가 애플리케이션에 무엇을 요청할 수 있는지 정의합니다.
* **Out-Port (Repository / Batch Query Port)**: 애플리케이션이 데이터를 저장/조회하기 위해 무엇이 필요한지 정의합니다. 일반 CRUD용 `BoardRepositoryPort`와 대용량 배치용 `BoardBatchQueryPort`를 **ISP로 분리**해, `CoroutineCrudRepository`가 기본 제공하는 "전체를 List로 올리는" `findAll()` 같은 조회가 포트 경계로 새어 들어오지 않게 합니다(각 포트는 키셋 페이지네이션/스트리밍만 노출).

## 📝 API Specification

### 게시글 (Board)

| Method | URI | Description |
| :--- | :--- | :--- |
| `POST` | `/api/boards` | 게시글 생성 |
| `GET` | `/api/boards?cursor={id}&size={n}` | 게시글 목록 (키셋 페이지네이션, id 내림차순) |
| `GET` | `/api/boards/{id}` | 특정 게시글 단건 조회 (조회수 +1) |
| `PUT` | `/api/boards/{id}` | 게시글 수정 |
| `DELETE` | `/api/boards/{id}` | 게시글 삭제 |

> 단건 조회 시 조회수(`viewCount`)가 1 증가합니다. DB를 직접 갱신하지 않고 Redis에 델타를 누적한 뒤 주기적으로 DB에 반영하며, 응답의 `viewCount`는 **DB 누적값 + 아직 반영 안 된 델타**로 실시간 값을 보여줍니다. **Redis가 장애여도 조회는 실패하지 않고 DB 누적값으로 200을 반환**합니다(조회수 집계는 best-effort). (아래 **👁 View Count** 섹션 참고)

> 목록은 **키셋(seek) 페이지네이션**입니다. `cursor`는 마지막으로 본 게시글 id(생략 시 최신부터), `size`는 페이지 크기(1~100, 기본 20)입니다. 응답 `BoardPageResponse`는 `items`, `nextCursor`, `hasNext`를 담으며, `hasNext`가 `true`면 `nextCursor`를 다음 요청의 `cursor`로 넘겨 다음 페이지를 조회합니다. (내부적으로 `size+1`건을 조회해 `hasNext`를 판정합니다.)

### 관측성 · 문서 (Operational Endpoints)

| Method | URI | Description |
| :--- | :--- | :--- |
| `GET` | `/actuator/health` | 애플리케이션/의존성 상태 (r2dbc·redis 헬스 포함) |
| `GET` | `/actuator/metrics` | 애플리케이션 메트릭 |
| `GET` | `/actuator/prometheus` | Prometheus 스크레이프 엔드포인트 (HTTP 요청 지연 히스토그램 포함) |
| `GET` | `/swagger-ui.html` | Swagger UI (API 문서 뷰어) |
| `GET` | `/v3/api-docs` | OpenAPI 3 문서 (JSON) |
| `POST` | `/api/admin/view-counts/flush` | 조회수 델타를 즉시 DB로 write-back (온디맨드 플러시) |

## 🗑 Batch: Stale Board Archiving

대용량 데이터를 논블로킹으로 처리하는 배치 유즈케이스(`ArchiveStaleBoardsService`)를 코루틴 기반으로 구현했습니다.

* **스트리밍 조회**: `BoardBatchQueryPort.findStaleBoards()`가 **키셋(seek) 페이지네이션**으로 한 페이지씩 `Flow`로 흘려보냅니다. OFFSET 방식과 달리 뒤쪽 페이지에서도 성능이 일정하고, 전체를 메모리에 올리지 않습니다.
* **백프레셔 + 동시성**: `coroutineScope` 안에서 바운드 `Channel`을 두고, 생산자(페이지 스트림)와 N개의 워커(청크 삭제)를 팬아웃합니다. 소비자가 밀리면 `send`가 suspend되어 생산자가 다음 페이지를 읽지 않습니다.
* **도메인 규칙이 최종 권위**: SQL의 `created_at` 필터는 1차 성능 필터일 뿐, 삭제 대상은 `Board.isStale()`이 다시 확정합니다. (`created_at` 범위 조회가 대용량에서 풀스캔이 되지 않도록 `idx_board_created_at` 인덱스를 둡니다.)
* **내결함성**: 한 청크 삭제가 실패해도 배치 전체를 멈추지 않고 건너뜁니다.
* **운영 튜닝**: `board.archiving.*`(cron, retentionDays, chunkSize, concurrency)로 코드 변경 없이 조절합니다. 기본은 비활성(`enabled: false`).

## 👁 View Count (조회수, Write-Back)

조회가 몰리는 게시글에서 **매 조회마다 DB `UPDATE`를 날리는 부담**을 없애기 위해, 조회수를 **리액티브 Redis에 누적하고 주기적으로 DB에 반영(write-back)** 합니다. 전 구간 논블로킹을 유지하기 위해 블로킹 `RedisTemplate`/Jedis가 아닌 **Lettuce 기반 `ReactiveStringRedisTemplate`** + 코루틴 브리지(`awaitSingle`)를 사용합니다.

* **읽기 경로**: `GET /api/boards/{id}` 시 Redis Hash(`board:views:pending`)에 `HINCRBY`로 원자적 증가. 응답 `viewCount`는 **DB 누적값 + 미반영 델타**로 실시간 값을 돌려줍니다.
* **원자적 드레인**: 플러시는 `RENAME pending → draining`으로 버퍼를 통째로 스냅샷한 뒤 읽고 삭제합니다. `RENAME` 직후 들어오는 조회는 새로 생성되는 `pending`에 쌓이므로 **델타가 유실되지 않습니다**.
* **write-back(배치)**: `@Scheduled` 트리거(`BoardViewCountFlushScheduler`)가 드레인한 델타를 **청크 단위 단일 `UPDATE ... FROM unnest(:ids, :deltas)`** 로 반영합니다(`BoardRepositoryPort.addViewCountsBatch`). 게시글별 순차 `UPDATE` 대비 DB 왕복이 대상 수 `N`에서 `ceil(N/chunkSize)`로 줄어, 플러시 대상이 많을수록 큰 이득입니다. 아카이브 배치와 동일하게 `runBlocking`으로 코루틴 경계를 어댑터 안에 가두고, 한 청크 실패가 전체를 막지 않도록 내결함성 처리합니다.
* **Redis 장애 내성(graceful degradation)**: 조회수 집계는 부가 기능(best-effort)입니다. `getBoard`는 `HINCRBY`를 `withTimeout` 시간 예산 안에서 시도하고, Redis가 죽거나(연결 거부) 느려도(행) **델타 0으로 강등해 DB 누적값으로 정상 조회(200)** 를 반환합니다. 즉 Redis 장애가 핵심 읽기 경로를 막지 않으며, 행 상태에서도 조회 지연이 예산 안에서 상한을 가집니다.
* **온디맨드 플러시**: `POST /api/admin/view-counts/flush`로 스케줄과 무관하게 즉시 write-back합니다(배포·셧다운 직전 버퍼 비우기, 결정적 부하 테스트에 사용). 스케줄러와 동일한 `FlushBoardViewCountsUseCase`에만 의존합니다.
* **헥사고날 분리**: 조회수 버퍼는 out-port `BoardViewCountPort`(Redis 어댑터)로, DB 반영은 `BoardRepositoryPort.addViewCountsBatch`로 분리됩니다. 서비스는 구체 기술(Redis)을 모릅니다.
* **운영 튜닝**: `board.view-count.*`로 코드 변경 없이 조절합니다 — `flush-enabled`, `flush-interval-ms`(주기를 짧게 하면 DB 정합성↑·쓰기 부하↑), `flush-chunk-size`(한 배치 UPDATE에 묶을 게시글 수), `increment-timeout-ms`(조회 시 Redis 증가 시간 예산).

> 학습용 단순화: 드레인 이후 DB 반영 전에 프로세스가 죽으면 그 델타는 유실될 수 있습니다. 실무에선 반영 성공분만 커밋 후 삭제하거나 실패분을 버퍼로 되돌리는 보상 로직을 둡니다.

## 🧪 Test Strategy

헥사고날 아키텍처의 각 계층을 **독립적으로** 테스트합니다. 모든 테스트는 [Kotest](https://kotest.io/) `BehaviorSpec`의 `Given / When / Then` DSL로 의도를 명확히 표현하고, mock은 [MockK](https://mockk.io/)를 사용합니다. 코루틴/`Flow`는 Kotest의 suspend 테스트 컨텍스트에서 직접 호출하며, suspend 함수 mock은 `coEvery`/`coVerify`로 검증합니다.

### 테스트 구조

```text
src/test
└── demo.reactivestream
    ├── ReactiveStreamApplicationTests          # 전체 ApplicationContext 로딩 검증 (Testcontainers)
    ├── domain/model/
    │   └── BoardTest                          # Domain 단위 테스트
    ├── application/port/in/
    │   └── CreateBoardCommandTest             # Command 자가 검증 테스트
    ├── application/service/
    │   ├── BoardServiceTest                   # Service 단위 테스트 (MockK, coEvery/coVerify)
    │   ├── ArchiveStaleBoardsServiceTest      # 배치 서비스 단위 테스트 (인메모리 Fake Port)
    │   └── FlushBoardViewCountsServiceTest    # 조회수 플러시 서비스 단위 테스트 (MockK)
    ├── adapter/in/web/
    │   ├── BoardControllerTest                # Controller 슬라이스 테스트 (WebTestClient + MockK)
    │   ├── ActuatorEndpointTest               # Actuator/Prometheus 구성 검증 (전체 서버, Testcontainers)
    │   ├── OpenApiDocsTest                    # springdoc OpenAPI 문서 서빙 검증 (전체 서버)
    │   └── RouteNotFoundTest                  # 미존재 경로가 404 통일 포맷으로 응답하는지 검증
    ├── adapter/out/persistence/
    │   ├── BoardPersistenceAdapterTest        # 영속성 계층 통합 테스트 (Testcontainers)
    │   └── BoardBatchPersistenceAdapterTest   # 배치 영속성 통합 테스트 (키셋 페이지네이션/벌크 삭제)
    ├── adapter/out/redis/
    │   └── BoardViewCountRedisAdapterTest     # 조회수 버퍼 통합 테스트 (Redis Testcontainers: 증가/드레인)
    └── support/
        ├── PostgresTestContainer              # Postgres 컨테이너 싱글톤
        ├── RedisTestContainer                 # Redis 컨테이너 싱글톤
        └── TestContainers                     # 두 컨테이너 접속 정보를 함께 등록하는 헬퍼
```

### 계층별 테스트 전략

| 테스트 대상 | 테스트 유형 | 주요 검증 내용 |
| :--- | :--- | :--- |
| `BoardTest` | Domain 단위 | `update()` 후 새 인스턴스 반환, 원본 불변성 보장, 빈 제목 시 `BoardValidationException` 발생 |
| `CreateBoardCommandTest` | Command 자가 검증 | 빈 제목 / 10자 미만 내용 시 `IllegalArgumentException` 발생, 유효 입력 시 정상 생성 |
| `BoardServiceTest` | Service 단위 (MockK) | 각 UseCase 메서드의 흐름·예외 위임, 키셋 페이지(`BoardPage`)의 `hasNext`/`nextCursor` 판정, 단건 조회 시 조회수 증가 및 `DB값+델타` 보정, **Redis 장애 시 강등(DB값으로 200)** 검증 |
| `ArchiveStaleBoardsServiceTest` | 배치 서비스 단위 (Fake Port) | 스트리밍/청크/동시성/내결함성 흐름을 결정적으로 검증, `isStale` 도메인 규칙이 최종 권위임을 검증 |
| `FlushBoardViewCountsServiceTest` | 플러시 서비스 단위 (MockK) | 드레인한 델타를 청크 단위 `addViewCountsBatch`로 반영, 청크 크기 초과 시 여러 배치로 분할, 빈 델타 시 DB 미접근, 일부 청크 실패 시 나머지 반영 + `failed` 집계 검증 |
| `BoardControllerTest` | Web 슬라이스 (WebTestClient + MockK) | HTTP 상태 코드, 응답 JSON(페이지 포함), Location 헤더, `GlobalExceptionHandler` 동작 검증 |
| `RouteNotFoundTest` | Web 통합 (전체 서버) | 매핑되지 않은 경로가 500이 아닌 **404** 통일 실패 포맷으로 응답하는지 검증 |
| `ActuatorEndpointTest` | 관측성 통합 (전체 서버, Testcontainers) | `/actuator/health`(r2dbc·redis 헬스 포함)와 `/actuator/prometheus` 노출 검증 |
| `OpenApiDocsTest` | 문서 통합 (전체 서버, Testcontainers) | `/v3/api-docs`에 Board 경로가 포함되고 `/swagger-ui.html`이 리다이렉트되는지 검증 |
| `BoardPersistenceAdapterTest` | 영속성 통합 (Testcontainers) | 실제 PostgreSQL에 `save`/`findById`/`findPage`(키셋)/`deleteById`가 정상 반영되고, `addViewCountsBatch`(`unnest` 배치 UPDATE)가 존재하는 id만 가산하는지 검증 |
| `BoardBatchPersistenceAdapterTest` | 배치 영속성 통합 (Testcontainers) | 키셋 페이지네이션이 여러 페이지에 걸쳐 동작하는지, `deleteByIds` 벌크 삭제가 정확한지 검증 |
| `BoardViewCountRedisAdapterTest` | 조회수 버퍼 통합 (Redis Testcontainers) | 반복 `increment`가 델타를 누적하는지, `drainPendingDeltas`가 전체를 반환하고 버퍼를 비우는지 검증 |
| `ReactiveStreamApplicationTests` | 전체 컨텍스트 (Testcontainers) | 모든 빈이 정상 구성되어 ApplicationContext가 로딩되는지 검증 |

> Kotest 기본 isolation mode(`SingleInstance`)에서는 스펙 인스턴스가 한 번만 생성되므로, `Given` 블록마다 mock/fixture를 **새로** 만들어야 테스트 간 호출 기록이 섞이지 않습니다 (`ServiceFixture`, `ControllerFixture` 참고).

### 통합 테스트와 Testcontainers

영속성/컨텍스트 통합 테스트는 `PostgresTestContainer`·`RedisTestContainer` 싱글톤을 공유하며, `TestContainers.registerAll()`이 두 컨테이너의 접속 정보를 함께 주입합니다(앱 컨텍스트에 R2DBC와 리액티브 Redis가 모두 포함되고 Actuator health가 둘 다 확인하므로).

1. 테스트 실행 시 `postgres:16-alpine`·`redis:7-alpine` 이미지를 받아 컨테이너를 띄웁니다 (호스트의 **랜덤 포트**에 매핑되며, `application.yml`의 `localhost:5432`/`6379`와는 무관합니다). JDBC 드라이버가 없으므로 Postgres readiness는 **로그 메시지 기반 대기 전략**으로 판정합니다.
2. `@DynamicPropertySource`가 컨테이너의 실제 접속 정보를 **`spring.r2dbc.*`(R2DBC URL)** 와 **`spring.data.redis.*`** 로 주입합니다.
3. ApplicationContext가 뜨면서 `ConnectionFactoryInitializer`가 `src/main/resources/db/schema.sql`을 R2DBC로 실행해 스키마를 구성하고, 이후 모든 쿼리도 R2DBC로 실행됩니다.
4. JVM(Gradle 테스트 프로세스) 종료 시 Testcontainers의 Ryuk이 컨테이너를 자동으로 정리합니다.

즉 별도 설정 없이 `./gradlew test`만 실행해도 PostgreSQL·Redis가 자동으로 뜨고 내려갑니다. **Docker가 실행 중이어야** 합니다.

### 테스트 실행

```bash
# 전체 테스트 실행
./gradlew test

# 특정 클래스만 실행
./gradlew test --tests "*.BoardTest"
./gradlew test --tests "*.CreateBoardCommandTest"
./gradlew test --tests "*.BoardServiceTest"
./gradlew test --tests "*.ArchiveStaleBoardsServiceTest"
./gradlew test --tests "*.BoardControllerTest"
./gradlew test --tests "*.FlushBoardViewCountsServiceTest"
./gradlew test --tests "*.BoardPersistenceAdapterTest"
./gradlew test --tests "*.BoardBatchPersistenceAdapterTest"
./gradlew test --tests "*.BoardViewCountRedisAdapterTest"
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
docker run --name hexagonal-postgres -e POSTGRES_DB=hexagonal \
  -e POSTGRES_USER=hexagonal -e POSTGRES_PASSWORD=hexagonal1234 \
  -p 5432:5432 -d postgres:16-alpine

# 조회수 카운터용 Redis 기동 (application.yml: localhost:6379)
docker run --name hexagonal-redis -p 6379:6379 -d redis:7-alpine

# Run
./gradlew bootRun
```

기동 후 확인할 수 있는 엔드포인트:

* **API 문서 (Swagger UI)**: <http://localhost:8080/swagger-ui.html>
* **헬스 체크**: <http://localhost:8080/actuator/health>
* **Prometheus 메트릭**: <http://localhost:8080/actuator/prometheus>

`bootRun`은 `src/main/resources/application.yml`에 고정된 접속 정보로 DB·Redis에 연결하므로, 로컬에 해당 정보로 접속 가능한 PostgreSQL과 Redis가 떠 있어야 합니다.

* **모든 DB 접근**은 `spring.r2dbc.url`(`r2dbc:postgresql://localhost:5432/hexagonal`)로 **논블로킹** 실행됩니다. **JDBC는 어디에서도 사용하지 않습니다.**
* **조회수 카운터**는 `spring.data.redis`(`localhost:6379`)의 **리액티브 Redis(Lettuce)** 로 논블로킹 처리됩니다.
* **스키마 초기화**는 `R2dbcSchemaInitializer`가 등록한 R2DBC `ConnectionFactoryInitializer`가 담당합니다. 애플리케이션 기동 시 `src/main/resources/db/schema.sql`을 R2DBC 커넥션으로 실행합니다.
* `schema.sql`은 `CREATE TABLE IF NOT EXISTS`와 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`(예: `view_count`)로 작성되어 여러 번 실행돼도 안전하며, 기존 테이블에도 새 컬럼을 멱등적으로 추가합니다. 스키마 변경은 이 파일을 수정/추가하는 방식으로 관리합니다.

## 🔄 CI

`main` 브랜치 push/PR마다 [GitHub Actions](.github/workflows/ci.yml)가 JDK 21 환경에서 `ktlintCheck`와 `test`(Testcontainers 기반 통합 테스트 포함)를 자동 실행합니다.

---

### 💡 Key Code Features

#### 1. Self-Validating Commands
Controller에서 넘어온 데이터는 UseCase로 진입하기 전, Command 객체 생성 시점에 **생성자 내부에서 유효성이 검증**됩니다. 이를 통해 애플리케이션 계층은 항상 유효한 데이터만 다룹니다.

#### 2. Non-Blocking End-to-End
HTTP 요청 수신부터 DB 왕복까지 전 구간이 `suspend`로 이어져 I/O 대기 중에도 스레드를 점유하지 않습니다. WebFlux(Netty)가 요청을 받고, R2DBC가 DB를 논블로킹으로 처리합니다.

#### 3. Isolation of Persistence
DB 테이블 구조(R2DBC Entity)가 변경되어도 비즈니스 로직(Domain Model)은 영향을 받지 않습니다. `BoardPersistenceAdapter`가 중간에서 `Mapper`를 이용해 두 객체 간의 변환을 담당합니다. 실제로 이 프로젝트는 JPA → R2DBC로 영속성 기술을 통째로 교체했지만 도메인은 변경되지 않았습니다.

#### 4. Unified Response Format & Global Exception Handling
성공/실패 모든 응답을 `BaseResponse<T>`(`code`, `status`, `result`) 하나로 통일합니다.

* **성공**: `SuccessResponse<T>` — `status = "Success"`, `code = HTTP 상태값`, `result = 데이터`. 컨트롤러는 `SuccessResponse.ok(...)` / `created(res, location)` / `noContent()` 같은 팩토리로 본문·상태 코드·헤더를 함께 통일합니다.
* **실패**: `FailureResponse` — `status = "Failure"`, `code = 에러의 statusCode`, `result = ErrorCode(code/label/statusCode)`, `message = 상황별 상세 메시지`(없으면 `ErrorCode.label`로 폴백). `error` 필드는 `private`으로 두어 직렬화에서 제외하고 노출은 `result`로만 통일합니다.

에러 코드는 `ErrorCode` 인터페이스로 정의하고 `CommonErrorCode`(공통) / `BoardErrorCode`(도메인)로 모읍니다. `GlobalExceptionHandler`(`@RestControllerAdvice`)가 예외를 `ErrorCode`로 매핑하며, 도메인은 `ErrorCode`를 전혀 알지 못합니다(매핑은 web 어댑터의 책임). WebFlux에서는 입력 바인딩 실패가 대부분 `ServerWebInputException`으로 수렴하므로, 원인(`TypeMismatchException` 여부)을 구분해 파라미터 오류와 바디 오류로 나눕니다. 또한 프레임워크가 던지는 `ResponseStatusException`(존재하지 않는 경로의 `NoResourceFoundException`=404 등)은 그 **HTTP 상태를 보존**해 응답하며, 이때 사전 정의되지 않은 상태 코드는 `DynamicErrorCode`로 감쌉니다(포괄 `Exception` 핸들러가 이를 500으로 뭉개지 않도록 더 구체적인 핸들러가 먼저 가로챕니다).

| 예외 | ErrorCode | HTTP Status | result.code |
| :--- | :--- | :--- | :--- |
| `BoardNotFoundException` | `BoardErrorCode.NotFound` | 404 | `BOARD_NOT_FOUND` |
| `BoardValidationException` | `CommonErrorCode.ValidationError` | 400 | `VALIDATION_ERROR` |
| `IllegalArgumentException` (Command 자가 검증) | `CommonErrorCode.ValidationError` | 400 | `VALIDATION_ERROR` |
| `ServerWebInputException` (PathVariable 타입 불일치) | `CommonErrorCode.InvalidParameter` | 400 | `INVALID_PARAMETER` |
| `ServerWebInputException` (잘못된/빈 요청 Body) | `CommonErrorCode.InvalidRequestBody` | 400 | `INVALID_REQUEST_BODY` |
| `ResponseStatusException` (예: 미존재 경로 `NoResourceFoundException`) | `DynamicErrorCode` (상태 보존) | 예외의 상태값 (예: 404) | 예: `NOT_FOUND` |
| 그 외 모든 예외 | `CommonErrorCode.InternalServerError` | 500 | `INTERNAL_SERVER_ERROR` |

```json
// 성공
{ "code": 200, "status": "Success", "result": { "id": 1, "title": "...", "viewCount": 42 } }

// 실패 (message는 상황별 상세, result는 에러 코드 정의)
{
  "code": 404,
  "status": "Failure",
  "result": { "code": "BOARD_NOT_FOUND", "label": "게시글을 찾을 수 없습니다.", "statusCode": 404 },
  "message": "Board not found with id: 999"
}
```

#### 5. Keyset(Seek) Pagination
목록 조회는 `OFFSET` 대신 **"마지막으로 본 id 이후"** 를 조건으로 다음 페이지를 읽습니다. 뒤쪽 페이지에서도 성능이 일정하고, 한 페이지(`size`)만 읽으므로 요청당 메모리/지연이 커지지 않습니다. `hasNext` 판정을 위해 `size+1`건을 조회해 초과분이 있으면 다음 페이지가 있다고 봅니다.

#### 6. Observability
Actuator + Micrometer Prometheus로 상태·메트릭을 노출합니다. `http.server.requests` 타이머에 지연 히스토그램을 켜 처리량/지연을 관측할 수 있고, `/actuator/health`에는 r2dbc 헬스가 포함됩니다. WebFlux+코루틴 환경에서 `suspend`/`Flow` 경계를 넘어 추적 컨텍스트가 유실되지 않도록 Reactor 자동 컨텍스트 전파(`Hooks.enableAutomaticContextPropagation`)를 켭니다.

#### 7. Self-Documenting API
springdoc-openapi(WebFlux)가 컨트롤러의 `@Tag`/`@Operation`/`@Parameter`를 읽어 OpenAPI 3 문서를 자동 생성합니다. Swagger UI(`/swagger-ui.html`)에서 바로 API를 탐색·호출할 수 있습니다.

#### 8. View Count Write-Back (Reactive Redis)
조회수를 조회마다 DB에 쓰지 않고, 리액티브 Redis에 `HINCRBY`로 누적한 뒤 `@Scheduled`로 주기적으로 DB에 반영(write-back)합니다. 고빈도 쓰기를 Redis가 흡수해 DB 부하를 분리하고, `RENAME`으로 버퍼를 원자적으로 스냅샷해 플러시 중에도 델타가 유실되지 않습니다. write-back은 게시글별 순차 UPDATE가 아니라 **청크 단위 단일 `unnest` 배치 UPDATE**라 대상이 많아도 DB 왕복이 `ceil(N/chunkSize)`로 억제됩니다. 또한 조회수 집계는 **best-effort**라, Redis 장애/지연 시 `withTimeout` 예산 안에서 강등해 조회 자체는 DB 값으로 정상 응답합니다. Redis 접근도 Lettuce(논블로킹) + 코루틴 브리지로 처리해 엔드투엔드 논블로킹을 유지합니다. (자세한 내용은 위 **👁 View Count** 섹션)
