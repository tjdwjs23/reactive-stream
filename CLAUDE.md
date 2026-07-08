# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 모노레포 구조

이 저장소는 **Gradle 멀티모듈 모노레포**(`rootProject.name = "search-platform"`)입니다. 게시판 정본 서비스와,
그 변경 이벤트를 소비해 검색 인덱스를 갱신하는 서비스를 이벤트(Kafka)로 잇습니다.

| 모듈 | 역할 |
|---|---|
| `event-contract` | 두 서비스가 공유하는 **순수 Kotlin 이벤트 계약**(`BoardChangedEvent`, 토픽명). 프레임워크 무의존 |
| `search-service` | 게시판 API(정본, JPA/PostgreSQL). 아래 Architecture 전체가 이 모듈을 설명합니다. 쓰기 시 **Transactional Outbox**로 이벤트를 기록하고 Kafka(`board-changed`)로 발행 |
| `search-indexer` | `board-changed`를 **@KafkaListener로 소비**해 Elasticsearch 색인을 upsert/삭제하는 독립 서비스(ES writer) |

루트 `subprojects{}`가 Kotlin/JDK25/ktlint 공통 컨벤션을 적용하고, 서비스별 `bootJar`/배포는 독립입니다.
아래 문서의 소스 경로(`adapter/...`, `application/...`, `domain/...`)는 별도 언급이 없으면 **`search-service/src/main/kotlin/demo/search/` 기준**입니다.

## Commands

```bash
# Build (전 모듈)
./gradlew clean build

# Run (서비스별 — 멀티모듈이라 루트 bootRun은 모호합니다)
./gradlew :search-service:bootRun
./gradlew :search-indexer:bootRun     # Kafka + ES 필요(search.outbox.relay.enabled=true로 search-service 릴레이도 켜야 발행됨)

# Test (전 모듈)
./gradlew test

# Test (단일 모듈 / 클래스 / 메서드)
./gradlew :search-service:test
./gradlew test --tests "demo.search.SearchApplicationTests"
./gradlew test --tests "demo.search.SearchApplicationTests.contextLoads"

# Lint (ktlint) — CI 게이트
./gradlew ktlintCheck
./gradlew ktlintFormat        # 자동 포맷

# Coverage (Kover) — CI 게이트(search-service 최소 라인 92%)
./gradlew koverVerify         # 기준선 검증
./gradlew koverHtmlReport     # search-service/build/reports/kover/html/index.html
```

> **Quality gates**: 아키텍처 규칙은 **Konsist**(`search-service/src/test/.../architecture/ArchitectureTest.kt`)가 테스트로 강제합니다 — 헥사고날 의존성 방향(Adapter→Application→Domain), 도메인의 프레임워크 무의존, `@Table`/`@Document` 엔티티의 패키지 격리 등. 일반 `./gradlew test`에 포함돼 돌아갑니다. 커버리지는 **Kover**가 search-service 하한 92%로 게이트(`koverVerify`)하며, 부트스트랩(`BoardApplication*`)은 집계에서 제외합니다.

> **Note**: The app runs on **Spring MVC + virtual threads (JDK 25) with blocking JDBC — Spring Data JPA/Hibernate + Kotlin JDSL, no R2DBC/WebFlux anywhere**. `application.yml` configures `spring.datasource.*` (HikariCP) and `spring.jpa.*` (`ddl-auto=validate`, `open-in-view=false`). The schema is owned by **Flyway**: `db/migration/V*.sql` runs at startup and is tracked in `flyway_schema_history` (`baseline-on-migrate=true`). Simple CRUD uses Spring Data derived queries; keyset/stale scans use Kotlin JDSL; the unnest batch UPDATE / outbox writes use native SQL via `JdbcTemplate`. `bootRun` needs a reachable PostgreSQL (see `application.yml`, default `localhost:5432/search`). Tests spin up PostgreSQL via Testcontainers (`support/PostgresTestContainer.kt`).

## 로컬 실행 / 배포 (Colima + kind + Helm)

로컬 실행은 **k8s(Helm)** 로 통일됐습니다(클라우드 미연결) — 모든 인프라와 두 서비스가 kind 클러스터 안에서 뜹니다. 자산은 `deploy/`에 있습니다. **별도로**, IntelliJ에서 앱을 host로 직접 `bootRun`/디버그할 때 데이터스토어 4종(PostgreSQL/Redis/Elasticsearch/Kafka)만 컨테이너로 띄우는 루트 `docker-compose.yml`을 병행 제공합니다(kind 없이 가벼운 디버깅 루프용 — 이 경로에선 앱만 host에서 실행).

- **원클릭**: `./deploy/up.sh`(코어 = 앱 2 + PostgreSQL/Redis/Elasticsearch/Kafka = 6파드, 멱등) / `./deploy/up.sh --obs`(+ LGTM 관측성 5파드 = 11, colima 12G). 정리 `./deploy/down.sh`(`--all`이면 colima까지).
- **차트**: `deploy/helm/search-platform`(Chart/values/templates). 데이터스토어·앱은 항상, LGTM(Alloy/Mimir/Loki/Tempo/Grafana)은 `observability.enabled`로 토글(기본 off — 앱 OTLP export도 함께 꺼짐). LGTM 설정은 `files/`(compose에서 이관, 서비스명이 같아 그대로 재사용).
- **앱 설정 주입**: ConfigMap/Secret env — `SPRING_DATASOURCE_URL`=jdbc:postgresql://postgres, `SPRING_DATA_REDIS_HOST`=redis, `SPRING_ELASTICSEARCH_URIS`=http://elasticsearch:9200, `KAFKA_BOOTSTRAP_SERVERS`=kafka:9092, `SEARCH_OUTBOX_RELAY_ENABLED`=true.
- **이미지**: `search-service/Dockerfile`·`search-indexer/Dockerfile`(멀티스테이지, 빌드 컨텍스트=레포 루트). `deploy/build-and-load.sh`가 빌드→`kind load`(로컬 이미지라 pull 불가, `imagePullPolicy: IfNotPresent`). 두 앱 모듈은 plain jar 비활성(`tasks.named<Jar>("jar")`)이라 `build/libs`에 bootJar 하나만 남습니다.
- **접근**: search-service `localhost:8080`(NodePort 30080), search-indexer `localhost:8081`, Grafana `localhost:3000`(--obs). 코드 변경 반영은 재빌드+load 후 `kubectl rollout restart deploy/<svc>`.
- 빠른 개발 반복은 `:search-service:bootRun`(host 실행)도 됩니다 — 데이터스토어는 루트 `docker compose up -d`로 4종을 host 포트에 띄우거나(가장 가벼움), 이미 kind가 떠 있으면 `kubectl port-forward`로 당깁니다(단 `deploy/pf.sh`는 postgres만 forward하므로 redis/es/kafka는 직접 forward 필요). 상세 런북: `deploy/README.md`.

## Architecture

This is a **Hexagonal Architecture (Ports and Adapters)** board API in Kotlin + Spring Boot 4 / JDK 25, on a **blocking stack: Spring MVC + virtual threads + Spring Data JPA (Hibernate) + Kotlin JDSL**. Requests run on virtual threads, so plain blocking I/O (JPA/Redis/ES) scales without the reactive complexity (see the "스택 전환 근거" section of `readme.md` for the why).

**Conventions (apply everywhere):**
- Port methods are **plain blocking `fun`s**; multi-value reads return `List<T>` at the port boundary. The **one deliberate exception** is `ArchiveStaleBoardsUseCase.archiveStaleBoards`, which stays `suspend` because the archive batch uses structured concurrency (a bounded `Channel` fan-out) internally.
- Transaction boundaries stay **narrow — DB access only**. `BoardService` has **no class-level `@Transactional`**: external side-effects (Redis increment) run *outside* the DB path so a Redis round-trip doesn't hold a DB connection, and reads (`getBoard`, `getBoards`) are single SELECTs with no tx. The **one deliberate exception** is writes: create/update/delete wrap **`boardRepositoryPort.save/delete` + `boardEventOutboxPort.record` in a single transaction** (via the `TransactionRunnerPort` out-port, implemented by `SpringTransactionRunner` over `TransactionTemplate`/`PlatformTransactionManager`) — Transactional Outbox requires the DB change and the event row to be atomic. **ES indexing is no longer done inline in the write path**; the outbox event is published to Kafka and `search-indexer` applies it to ES. (`BoardService` no longer depends on `BoardSearchPort` at all — search *query* and *reindex* still live in `BoardSearchService`.)
- Coroutines survive **only** where they earn their keep: the archive batch's producer/worker fan-out over a bounded `Channel` (workers make blocking JPA calls on `Dispatchers.IO`). Driving adapters that can't be `suspend` (the `@Scheduled` archive trigger) bridge with `runBlocking` inside the adapter.

The three concentric layers — with dependency arrows pointing strictly inward:

```
Adapter (in/out)  →  Application (ports + service)  →  Domain (model + exception)
```

### Layer responsibilities

| Layer | Package | Role |
|---|---|---|
| **Domain** | `domain.model`, `domain.exception` | Pure Kotlin classes; zero Spring/persistence annotations |
| **Application** | `application.port.in`, `application.port.out`, `application.service` | UseCase interfaces (in-ports, blocking `fun`/`List`), Repository interfaces (out-ports), `BoardService` orchestrates between them |
| **Adapter-in** | `adapter.in.web` | `BoardController` (plain blocking handlers) injects UseCase interfaces (never the concrete service); `BoardWebDto` holds Request/Response DTOs. Also `adapter.in.batch` (`@Scheduled` trigger) |
| **Adapter-out** | `adapter.out.persistence` | `BoardJpaEntity` (JPA `@Entity`/`@Table`), `BoardJpaRepository` (`JpaRepository` + `KotlinJdslJpqlExecutor`), `BoardPersistenceAdapter` implements `BoardRepositoryPort`, `BoardMapper` converts between Entity ↔ Domain |

### Key patterns

- **Domain model is immutable** (`data class Board`). Mutations return a new instance via `.copy()` (see `Board.update()`).
- **Self-validating commands**: `CreateBoardCommand` validates in its `init` block (title not blank, title ≤ 255 chars, content ≥ 10 chars). `UpdateBoardCommand` does **not** self-validate — the same rules are enforced by `Board.update()` (title blank/length ≤ 255, content ≥ 10). Both length limits are domain invariants shared by create and update: `Board.MAX_TITLE_LENGTH` (255, matching the DB `VARCHAR(255)` so an over-length title is a 400, not a raw DB 500) and `Board.MIN_CONTENT_LENGTH` (10). Create and update validate content symmetrically.
- **Strict separation of persistence Entity and Domain Model**: `BoardMapper` is the only place where `BoardJpaEntity` ↔ `Board` conversion happens. Never put `@Entity`/`@Table`/`@Id` on a domain model.
- `BoardService` keeps transactions narrow: the **only** transaction is the write path, which wraps `boardRepositoryPort.save/delete` + `boardEventOutboxPort.record` atomically via `TransactionRunnerPort` (Transactional Outbox). **ES indexing is not done in the write path** — the outbox event is published to Kafka and `search-indexer` applies it to ES. `getBoard` reads the DB then increments Redis outside any tx, and `getBoards` is a single keyset SELECT with no tx (returns `BoardPage`). `BoardService` no longer depends on `BoardSearchPort`.
- **Batch (`ArchiveStaleBoardsService`)**: no class-level `@Transactional`; a `coroutineScope` fans out a bounded `Channel` (a producer on `Dispatchers.IO` reads keyset-paginated `List` pages via the blocking `BoardBatchQueryPort.findStalePage` and sends them; N workers delete chunks). The bounded channel provides backpressure — a slow consumer suspends the producer's `send`, throttling DB reads. Each chunk commits **`deleteByIds` + a `DELETED` outbox event per id atomically** (via `TransactionRunnerPort` + `boardEventOutboxPort.recordAll`) — like the single-delete write path, an archived board must emit a `DELETED` event so `search-indexer` removes its ES document (no orphaned index docs). Domain rule `Board.isStale()` is the final authority over the SQL pre-filter. **Partial** chunk failures are reported in `ArchiveResult.failedChunks` (skip-and-continue; a failed chunk commits neither the delete nor its events, so it is retried next run); a **total** failure (every attempted chunk failed) throws `IllegalStateException` so a scheduler that only watches for exceptions doesn't mistake it for success.
- **View-count flush (`FlushBoardViewCountsService`)**: after write-back, each chunk commits **`addViewCountsBatch` (`UPDATE … RETURNING`) + an `UPDATED` outbox event per affected board atomically**, so the ES document's `viewCount` is eventually synced too (the event carries the *absolute* post-update count, so redelivery is idempotent and reuses the existing `UPDATED` upsert path in `search-indexer` — no new event type).
- `BoardService` implements all four UseCase interfaces; `BoardController` injects them individually by interface, not by the concrete class.
- Business exceptions (`BoardNotFoundException`, `BoardValidationException`) live in `domain.exception` and extend `RuntimeException`.

### REST endpoints

| Method | Path | UseCase | 인가 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | `SignUpUseCase` → 201 Created | 공개 |
| `POST` | `/api/auth/login` | `LoginUseCase` → 200 OK (access JWT + refresh 토큰 발급, 실패 반복 시 429) | 공개 |
| `POST` | `/api/auth/refresh` | `RefreshTokenUseCase` → 200 OK (리프레시 토큰 회전 → 새 access+refresh) | 공개 |
| `POST` | `/api/boards` | `CreateBoardUseCase` → 201 Created | 인증 |
| `GET` | `/api/boards/{id}` | `GetBoardUseCase` → 200 OK | 공개 |
| `GET` | `/api/boards` | `GetBoardUseCase` → 200 OK | 공개 |
| `PUT` | `/api/boards/{id}` | `UpdateBoardUseCase` → 200 OK | 인증 (소유자/관리자) |
| `DELETE` | `/api/boards/{id}` | `DeleteBoardUseCase` → 204 No Content | 인증 (소유자/관리자) |
| `GET` | `/api/boards/search?keyword=&size=` | `SearchBoardUseCase` → 200 OK (한글 전문검색, 관련도순) | 공개 |
| `POST` | `/api/boards/search/reindex` | `ReindexBoardsUseCase` → 200 OK (DB→ES 무중단 재색인, alias 스왑) | ROLE_ADMIN |
| `POST` | `/api/products` | `CreateProductUseCase` → 201 Created (상품 생성) | ROLE_ADMIN |
| `GET` | `/api/products/{id}` · `/api/products` | `GetProductUseCase` → 200 OK (단건/키셋 목록) | 공개 |
| `GET` | `/api/products/search?keyword=` | `SearchProductUseCase` → 200 OK (상품명 Nori 검색) | 공개 |
| `GET` | `/api/products/autocomplete?q=ㅅㄱ` | `AutocompleteProductUseCase` → 200 OK (**ICU 초성/접두 자동완성**) | 공개 |
| `DELETE` | `/api/products/{id}` | `DeleteProductUseCase` → 204 No Content | ROLE_ADMIN |
| `POST` | `/api/products/search/reindex` | `ReindexProductsUseCase` → 200 OK (DB→ES 무중단 재색인, alias 스왑) | ROLE_ADMIN |
| `POST` | `/api/admin/view-counts/flush` | `FlushBoardViewCountsUseCase` → 200 OK (조회수 즉시 플러시) | ROLE_ADMIN |
| `POST` | `/api/admin/boards/archive` | `ArchiveStaleBoardsUseCase` → 200 OK (오래된 게시글 즉시 아카이브) | ROLE_ADMIN |

> **인증/인가 (서블릿 Spring Security + 자체 발급 JWT)**: `SecurityConfig`(`@EnableWebSecurity`, `HttpSecurity`, STATELESS 세션)가 필터 체인을 정의합니다 — 읽기(GET 단건/목록/검색)·문서·actuator는 **공개**, 게시글 쓰기(POST/PUT/DELETE)는 **인증**, 운영 트리거(reindex/flush/archive)는 **ROLE_ADMIN**. 로그인(`/api/auth/login`)이 HS256 JWT를 발급하고(`NimbusJwtTokenAdapter`), 이후 요청은 `Authorization: Bearer <token>`로 전달합니다. JWT의 `sub`=사용자 id는 게시글 생성 시 `author_id`로 기록되고, **수정/삭제 시 소유권 검사**의 기준이 됩니다 — 소유자(`Board.isOwnedBy`) 또는 관리자만 수정/삭제할 수 있고, 그 외에는 `BoardAccessDeniedException`→**403 `BOARD_ACCESS_DENIED`**(IDOR 차단). 인가 규칙은 도메인(`Board.isOwnedBy`)+서비스(`BoardService.assertCanModify`, 관리자 우회)에 두고, 컨트롤러는 `AuthenticatedUserProvider.current()`(`SecurityContextHolder` 기반)로 요청자 id·관리자 여부를 커맨드(`UpdateBoardCommand`/`DeleteBoardCommand`)에 실어 넘깁니다. 관리자 계정은 기동 시 `search.security.admin.*`로 부트스트랩됩니다(가입 API는 ROLE_USER만 생성). JWT 서명키는 `search.security.jwt.secret`(env `SEARCH_JWT_SECRET`, 미설정 시 dev 기본값 + 경고). 비밀번호는 BCrypt 해시로 저장하며, 사용자 영속화(`users` 테이블)·해싱·토큰 발급은 각각 out-port(`UserRepositoryPort`/`PasswordEncoderPort`/`AuthTokenPort`)로 분리돼 서비스는 Spring Security를 모릅니다.
>
> **리프레시 토큰(회전 + 재사용 감지)**: 로그인은 이제 access JWT 하나가 아니라 **access + 불투명 refresh 토큰 쌍**을 반환합니다(`TokenResponse`). refresh 원문은 클라이언트에게만 주어지고 서버는 **SHA-256 해시만** `refresh_tokens` 테이블에 저장합니다(`RefreshTokenPort`/`RefreshTokenHashPort` out-port, `Sha256RefreshTokenHashAdapter`). `POST /api/auth/refresh`는 사용한 토큰을 즉시 폐기(1회용 회전)하고 새 쌍을 발급하며, **이미 폐기된 토큰이 다시 제시되면(재사용=탈취 의심) 해당 사용자의 모든 토큰을 무효화**하고 401(`INVALID_REFRESH_TOKEN`)을 반환합니다. TTL은 `search.security.refresh-token.ttl-days`(기본 14).
> **로그인 brute-force 방어(rate limiting)**: `LoginRateLimiterPort`(Redis 구현 `RedisLoginRateLimiterAdapter`)가 username별 실패를 슬라이딩 윈도우로 세고, `search.security.login.max-attempts`(기본 5)/`window-minutes`(기본 15)를 넘으면 자격 검증 이전에 **429 `TOO_MANY_LOGIN_ATTEMPTS`**로 차단합니다(성공 시 카운터 리셋).
> **CORS**: SPA 등 브라우저 클라이언트의 교차 오리진 호출을 위해 `SecurityConfig`가 CORS를 명시합니다 — 허용 오리진은 `search.security.cors.allowed-origins`(기본 `http://localhost:3000`, 운영은 `SEARCH_CORS_ALLOWED_ORIGINS`).
>
> **회복탄력성(Resilience4j 서킷브레이커)**: 외부 자원(Redis/Kafka/ES) 호출은 **어댑터에서만** 프로그래매틱 서킷브레이커로 감쌉니다(`ResilienceConfig`가 레지스트리 bean 제공, application/도메인은 무의존). 반복 실패 시 서킷이 열려 매 호출 타임아웃을 낭비하지 않고 즉시 실패합니다 — 조회수 Redis(→BoardService가 예외를 삼켜 DB 값으로 강등), Kafka 발행(→릴레이가 멈췄다 다음 사이클 재시도), ES 검색(→즉시 실패). 각 자원 클라이언트에는 타임아웃 상한(`application.yml`의 HikariCP pool/redis/elasticsearch)도 함께 둡니다.

### Korean full-text search (Elasticsearch + Nori)

한글 형태소 전문검색은 **별도 out-port(`BoardSearchPort`) + ES 어댑터**로 붙어 있습니다. JPA/PostgreSQL(정본)와 ES(검색 인덱스)는 분리됩니다. **색인 동기화는 더 이상 쓰기 경로 인라인이 아닙니다** — search-service가 쓰기와 원자적으로 남긴 아웃박스 이벤트를 Kafka(`board-changed`)로 발행하고, **`search-indexer`가 소비해 ES에 upsert/삭제**합니다(이벤트 기반 최종 일관성). search-service의 `BoardSearchService`는 ES에 대해 **검색 쿼리(`GET /api/boards/search`)와 전체 재색인**만 담당합니다(reader). 아래 항목은 search-service의 **검색/재색인(reader)** 관점 설명이며, ES **문서 스키마·색인 생성은 search-indexer가 소유(writer)**합니다.

> **알려진 tradeoff(중복)**: 같은 `boards` 인덱스를 search-service(검색/재색인 reader)와 search-indexer(이벤트 writer)가 공유하므로, `BoardDocument`와 Nori 설정 JSON(`board-settings.json`/`board-mappings.json`)이 **두 모듈에 중복**됩니다(현재는 byte-identical, 컴파일 타임 결합이 없어 한쪽만 바꾸면 런타임 스키마 불일치로 드러남). 인덱스 생성 이니셜라이저도 양쪽에 각각 있습니다(둘 다 멱등). 매핑 변경 시 양쪽을 함께 고쳐야 하며, **공유 스키마 모듈 추출**(event-contract 선례처럼)이 후속 과제입니다. 또한 `reindex`는 search-service가 ES에 직접 쓰는 dual-writer 경로라(마지막 쓰기 승리 경합 가능), 이벤트 리플레이 기반으로 옮기는 것도 후속 과제입니다.

> **이벤트 파이프라인 관측성(구현됨)**: ① `search-indexer`는 배치 `@KafkaListener` + **DLQ/에러 핸들러**를 둡니다 — `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`가 재시도 후 실패 레코드를 `board-changed-dlq`로 격리해 조용한 유실/무한 재시도 정체를 막습니다(`KafkaConsumerConfig`). DLQ로 격리되는 레코드는 발행 시 **`board.indexer.dlq` 카운터**로 집계돼(리졸버 콜백에서 증가) 격리가 조용히 묻히지 않습니다. ② **컨슈머 랙 메트릭**(`kafka.consumer.*` records-lag, `MicrometerConsumerListener` 바인딩)과 **아웃박스 미발행 백로그 게이지**(`search.outbox.unpublished`, 릴레이 사이클마다 `RelayOutboxService`가 갱신)를 노출합니다. ③ `search-indexer`는 search-service와 같은 **헥사고날 관측 포트**(`IndexerObservabilityPort` ← `MicrometerIndexerObservabilityAdapter`)로 색인 도메인 메트릭을 냅니다 — 반영 문서 수(`board.indexer.indexed`/`board.indexer.deleted`)와 배치 ES 벌크 쓰기 지연(`board.indexer.batch` 타이머, `publishPercentileHistogram`). Grafana "이벤트 파이프라인" 행에 컨슈머 랙·아웃박스 백로그·소비 처리량 + **DLQ 격리·색인 처리량(upsert/delete)·색인 배치 지연(avg/p95/p99)** 패널이 있습니다. **외부 자원 장애는 서킷브레이커**로 빠르게 실패시킵니다(위 회복탄력성 항목 참조).
>
> **관측성 알려진 한계/후속 과제**: HTTP 요청→아웃박스 기록 트레이스와 릴레이 폴링→Kafka 발행 트레이스는 여전히 **분리**됩니다(비동기 경계가 outbox 테이블이라 traceparent가 자동 전파되지 않음 — 릴레이→컨슈머 구간은 연결됨). 완전한 요청→색인 계보가 필요하면 traceparent를 outbox 행에 저장해 발행 시 복원. Grafana **컨슈머 랙 메트릭명**(`kafka_consumer_fetch_manager_records_lag_max` 등 클라이언트 노출 메트릭)은 배포 환경의 `/actuator/metrics` 실측에 맞춰 조정이 필요할 수 있습니다(반면 앱 자체가 emit하는 `board_*`/`board_indexer_*`는 이름이 고정). 대시보드는 시각화 전용이라 **임계치 기반 알림(alert rule)은 아직 미정의**입니다(예: DLQ 격리율>0, 컨슈머 랙 지속 상승, 아웃박스 백로그 비수렴 → 알림).

- **버전 제약**: Spring Boot 4는 Elasticsearch **9.2.x 클라이언트**(`spring-data-elasticsearch 6.x` + `elasticsearch-java`)를 번들합니다. ES 8 서버는 프로토콜(호환 미디어 타입)이 맞지 않아 통신이 깨지므로 **서버도 ES 9.2.x**를 써야 합니다. `docker/elasticsearch/Dockerfile`(공식 이미지 + `analysis-nori` 플러그인)이 `search-elasticsearch-nori:9.2.2` 이미지를 빌드합니다(로컬 k8s는 `deploy/build-and-load.sh`가 빌드해 `kind load`로 주입, 테스트는 Testcontainers가 같은 Dockerfile을 빌드).
- **인덱스/분석기**: `boards` 인덱스는 기동 시 `BoardSearchIndexInitializer`(`@EventListener(ApplicationReadyEvent)`, 블로킹)가 멱등 생성합니다. Nori 분석기 정의는 `resources/elasticsearch/board-settings.json`(nori_tokenizer, decompound_mode=mixed + nori_part_of_speech/readingform/lowercase 필터), 필드 매핑은 `board-mappings.json`(title/content를 `korean` 분석기로). `BoardDocument`의 `@Setting`/`@Mapping`이 이 JSON을 가리키며 매핑의 최종 권위입니다.
- **검색 쿼리**: `BoardSearchAdapter`가 `NativeQuery` + `multi_match`(title^2, content)로 검색하고, 기본 `_score` 내림차순(관련도순)으로 정렬 + `<em>` 하이라이트를 반환합니다. 다건은 관례대로 `List<BoardSearchHit>`.
- **엔티티 분리**: 도메인 `Board` ↔ `BoardDocument` 변환은 `BoardDocumentMapper`에서만. 도메인 모델에는 ES 애노테이션이 새어 들어가지 않습니다(JPA 엔티티 규칙과 동일).
- **정합성 회복(무중단 alias 재색인)**: 이벤트 유실·매핑 변경 시 `POST /api/boards/search/reindex`가 DB를 키셋 순회해 **새 버전 인덱스(`boards_v{n}`, `boards_<ts>`)에 전량 재구축한 뒤 `boards` alias를 원자적으로 스왑**합니다. `boards`는 이제 고정 인덱스가 아니라 **alias**이며, 검색/이벤트색인은 alias로만 접근하고 실제 데이터는 뒤의 버전 인덱스에 있습니다. 흐름: `BoardSearchPort.createNewVersionIndex()` → 페이지 벌크 `indexInto(page, newIndex)` → 전량 성공 시 `promote(newIndex)`(alias 원자 이동 + 옛 버전 삭제). **한 페이지라도 실패하면 스왑하지 않고 `deleteVersionIndex`로 새 인덱스를 폐기**해 검색이 기존 인덱스를 그대로 보게 합니다(무중단·자동 롤백). 새 인덱스로 깨끗이 재구축하므로 과거의 고아(orphan) prune이 불필요해졌습니다. alias 이동/삭제는 저수준 `ElasticsearchClient`, 문서 색인/검색은 `ElasticsearchOperations`로 분리합니다. 결과는 `ReindexResult(indexed, failed, swapped)`(응답 `result.reindexed`/`result.failed`/`result.swapped`). 인덱스 이니셜라이저는 alias-native(`boards_v1` + alias)로 멱등 생성합니다. **상품(`products`)도 동일한 alias 재색인**을 씁니다. ES refresh(~1s) 탓에 색인 직후 검색엔 지연이 있을 수 있습니다.

- **상품 초성/자동완성 검색(ICU)**: 초성 검색은 긴 게시글엔 안 맞지만 짧은 상품명엔 맞아 별도 `Product` 도메인 + `products` 인덱스로 분리했습니다(board 파이프라인 미러링). `name.chosung` 서브필드는 **ICU 분석기**로 색인 — `icu_normalizer(nfkc, decompose)`가 완성형 음절과 사용자가 친 호환 자모(ㅅㄱ)를 결합형 자모로 정규화, `pattern_replace([^ᄀ-ᄒ])`로 초성만 추출, `edge_ngram`으로 접두 자동완성. `analyzer`(색인)/`search_analyzer`(검색) 분리로 별도 매핑표 불필요. `GET /api/products/autocomplete?q=ㅅㄱ`. ES 이미지에 `analysis-icu` 필요.

### Adding a new feature

Follow this sequence: Domain model → Out-port interface → Service → In-port UseCase interface + Command → Controller + DTOs → Persistence adapter.

## Request flow walkthrough — `POST /api/boards`

The most representative business logic. Traces every layer in order.

```
[HTTP Request]
     │
     ▼
BoardController          adapter/in/web/BoardController.kt
  CreateBoardRequest → new CreateBoardCommand()
     │
     ▼
CreateBoardCommand.init  application/port/in/BoardUseCase.kt
  (self-validation via require())
     │
     ▼
BoardService             application/service/BoardService.kt
  new Board(...) → boardRepositoryPort.save()
     │
     ▼
BoardRepositoryPort      application/port/out/BoardRepositoryPort.kt
  (interface — no DB knowledge)
     │
     ▼
BoardPersistenceAdapter  adapter/out/persistence/BoardPersistenceAdapter.kt
  boardMapper.toEntity() → boardJpaRepository.save() → boardMapper.toDomain()
     │
     ▼
BoardJpaRepository       adapter/out/persistence/BoardJpaRepository.kt
  JpaRepository.save() → DB (JDBC/Hibernate, blocking on a virtual thread)
     │  (returns entity with generated id)
     ▼
BoardMapper              adapter/out/persistence/BoardMapper.kt
  toDomain() → Board returned up the chain
     │
     ▼
BoardWebMapper           adapter/in/web/BoardWebMapper.kt
  toResponse(board) → BoardResponse
     │
     ▼
[HTTP 201 Created + BoardResponse]
```

**Key points to observe:**
- The whole chain is plain blocking code running on a **virtual thread** — the JPA/JDBC round-trip blocks the virtual thread, which unmounts from its carrier so no platform thread is pinned.
- `BoardService` calls `boardRepositoryPort.save()` — it never sees `BoardPersistenceAdapter` or JPA.
- `CreateBoardCommand.init` fires at construction time inside the controller, so the service always receives valid data.
- `BoardMapper` and `BoardWebMapper` are the only places where layer boundaries are crossed via object conversion. Neither the domain model nor the JPA entity ever leaks past their respective mappers.
