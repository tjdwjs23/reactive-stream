# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew clean build

# Run
./gradlew bootRun

# Test (all)
./gradlew test

# Test (single class)
./gradlew test --tests "demo.board.BoardApplicationTests"

# Test (single method)
./gradlew test --tests "demo.board.BoardApplicationTests.contextLoads"

# Lint (ktlint) — CI 게이트
./gradlew ktlintCheck
./gradlew ktlintFormat        # 자동 포맷

# Coverage (Kover) — CI 게이트(최소 라인 85%)
./gradlew koverVerify         # 기준선 검증
./gradlew koverHtmlReport     # build/reports/kover/html/index.html
```

> **Quality gates**: 아키텍처 규칙은 **Konsist**(`src/test/.../architecture/ArchitectureTest.kt`)가 테스트로 강제합니다 — 헥사고날 의존성 방향(Adapter→Application→Domain), 도메인의 프레임워크 무의존, `@Table`/`@Document` 엔티티의 패키지 격리 등. 일반 `./gradlew test`에 포함돼 돌아갑니다. 커버리지는 **Kover**가 하한 85%로 게이트(`koverVerify`)하며, 부트스트랩(`BoardApplication*`)은 집계에서 제외합니다.

> **Note**: The app uses **R2DBC (non-blocking) only — no JDBC anywhere**. `application.yml` configures `spring.r2dbc.*`. Schema is initialized at startup by a **`ConnectionFactoryInitializer`** bean (`adapter/out/persistence/R2dbcSchemaInitializer.kt`) that runs `db/schema.sql` over R2DBC (Flyway was removed because it requires JDBC). `db/schema.sql` uses `CREATE TABLE IF NOT EXISTS`, so it is safe to re-run. `bootRun` needs a reachable PostgreSQL (see `application.yml`, default `localhost:5432/reactive`). Tests spin up PostgreSQL via Testcontainers (`support/PostgresTestContainer.kt`), which registers only the r2dbc URL and uses a **log-based wait strategy** (the default readiness probe uses JDBC, which is no longer on the classpath).

## Architecture

This is a **Hexagonal Architecture (Ports and Adapters)** board API in Kotlin + Spring Boot 4 / JDK 21, on a **reactive stack: Spring WebFlux + Kotlin coroutines + Spring Data R2DBC**.

**Reactive conventions (apply everywhere):**
- Single-value operations that touch I/O are `suspend fun`. Multi-value reads return `Flow<T>` (never `List` at the port boundary).
- Transaction boundaries stay **narrow — DB access only**. Spring's `ReactiveTransactionManager` (auto-configured by R2DBC) works on `suspend` functions, but `BoardService` deliberately has **no class-level `@Transactional`**: external side-effects (ES indexing, Redis increment) must run *outside* (and, for writes, *after*) the DB transaction so a rollback can't leave ES ahead of DB and so a Redis round-trip doesn't hold a DB connection. Writes are single-statement (R2DBC autocommit) + best-effort index after; reads (`getBoard`, `getBoards`) are single SELECTs with **no `@Transactional`** — a read-only tx around one autocommit statement buys nothing.
- Blocking-world driving adapters (e.g. the `@Scheduled` batch trigger, which can't be `suspend`) bridge into coroutines with `runBlocking` — keep that bridge inside the adapter.

The three concentric layers — with dependency arrows pointing strictly inward:

```
Adapter (in/out)  →  Application (ports + service)  →  Domain (model + exception)
```

### Layer responsibilities

| Layer | Package | Role |
|---|---|---|
| **Domain** | `domain.model`, `domain.exception` | Pure Kotlin classes; zero Spring/persistence annotations |
| **Application** | `application.port.in`, `application.port.out`, `application.service` | UseCase interfaces (in-ports, `suspend`/`Flow`), Repository interfaces (out-ports), `BoardService` orchestrates between them |
| **Adapter-in** | `adapter.in.web` | `BoardController` (`suspend` handlers) injects UseCase interfaces (never the concrete service); `BoardWebDto` holds Request/Response DTOs. Also `adapter.in.batch` (`@Scheduled` trigger) |
| **Adapter-out** | `adapter.out.persistence` | `BoardR2dbcEntity` (Spring Data R2DBC `@Table`), `BoardR2dbcRepository` (`CoroutineCrudRepository`), `BoardPersistenceAdapter` implements `BoardRepositoryPort`, `BoardMapper` converts between Entity ↔ Domain |

### Key patterns

- **Domain model is immutable** (`data class Board`). Mutations return a new instance via `.copy()` (see `Board.update()`).
- **Self-validating commands**: `CreateBoardCommand` validates in its `init` block (title not blank, title ≤ 255 chars, content ≥ 10 chars). `UpdateBoardCommand` does **not** self-validate — the same rules are enforced by `Board.update()` (title blank/length ≤ 255, content ≥ 10). Both length limits are domain invariants shared by create and update: `Board.MAX_TITLE_LENGTH` (255, matching the DB `VARCHAR(255)` so an over-length title is a 400, not a raw DB 500) and `Board.MIN_CONTENT_LENGTH` (10). Create and update validate content symmetrically.
- **Strict separation of persistence Entity and Domain Model**: `BoardMapper` is the only place where `BoardR2dbcEntity` ↔ `Board` conversion happens. Never put `@Table`/`@Id` on a domain model.
- `BoardService` keeps transactions narrow (no `@Transactional` at all): writes are single-statement autocommits with ES indexing done *after* the write, `getBoard` reads the DB then increments Redis outside any tx, and `getBoards` is a single keyset SELECT with no tx. `getBoards()` collects a keyset page and returns `BoardPage`.
- **Batch (`ArchiveStaleBoardsService`)**: no class-level `@Transactional`; a `coroutineScope` fans out a bounded `Channel` (producer collects the keyset-paginated `Flow`, N workers delete chunks). Deletes commit per-chunk. Domain rule `Board.isStale()` is the final authority over the SQL pre-filter. **Partial** chunk failures are reported in `ArchiveResult.failedChunks` (skip-and-continue); a **total** failure (every attempted chunk failed) throws `IllegalStateException` so a scheduler that only watches for exceptions doesn't mistake it for success.
- `BoardService` implements all four UseCase interfaces; `BoardController` injects them individually by interface, not by the concrete class.
- Business exceptions (`BoardNotFoundException`, `BoardValidationException`) live in `domain.exception` and extend `RuntimeException`.

### REST endpoints

| Method | Path | UseCase | 인가 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | `SignUpUseCase` → 201 Created | 공개 |
| `POST` | `/api/auth/login` | `LoginUseCase` → 200 OK (JWT 발급) | 공개 |
| `POST` | `/api/boards` | `CreateBoardUseCase` → 201 Created | 인증 |
| `GET` | `/api/boards/{id}` | `GetBoardUseCase` → 200 OK | 공개 |
| `GET` | `/api/boards` | `GetBoardUseCase` → 200 OK | 공개 |
| `PUT` | `/api/boards/{id}` | `UpdateBoardUseCase` → 200 OK | 인증 |
| `DELETE` | `/api/boards/{id}` | `DeleteBoardUseCase` → 204 No Content | 인증 |
| `GET` | `/api/boards/search?keyword=&size=` | `SearchBoardUseCase` → 200 OK (한글 전문검색, 관련도순) | 공개 |
| `POST` | `/api/boards/search/reindex` | `ReindexBoardsUseCase` → 200 OK (DB→ES 전체 재색인) | ROLE_ADMIN |
| `POST` | `/api/admin/view-counts/flush` | `FlushBoardViewCountsUseCase` → 200 OK (조회수 즉시 플러시) | ROLE_ADMIN |

> **인증/인가 (리액티브 Spring Security + 자체 발급 JWT)**: `SecurityConfig`가 필터 체인을 정의합니다 — 읽기(GET 단건/목록/검색)·문서·actuator는 **공개**, 게시글 쓰기(POST/PUT/DELETE)는 **인증**, 운영 트리거(reindex/flush)는 **ROLE_ADMIN**. 로그인(`/api/auth/login`)이 HS256 JWT를 발급하고(`NimbusJwtTokenAdapter`), 이후 요청은 `Authorization: Bearer <token>`로 전달합니다. JWT의 `sub`=사용자 id는 게시글 생성 시 `author_id`로 기록됩니다(수정/삭제는 인증된 사용자면 누구나 — 소유권 강제 없음). 관리자 계정은 기동 시 `board.security.admin.*`로 부트스트랩됩니다(가입 API는 ROLE_USER만 생성). JWT 서명키는 `board.security.jwt.secret`(env `BOARD_JWT_SECRET`, 미설정 시 dev 기본값 + 경고). 비밀번호는 BCrypt 해시로 저장하며, 사용자 영속화(`users` 테이블)·해싱·토큰 발급은 각각 out-port(`UserRepositoryPort`/`PasswordEncoderPort`/`AuthTokenPort`)로 분리돼 서비스는 Spring Security를 모릅니다.

### Korean full-text search (Elasticsearch + Nori)

한글 형태소 전문검색은 **별도 out-port(`BoardSearchPort`) + ES 어댑터**로 붙어 있습니다. R2DBC(정본)와 ES(검색 인덱스)는 분리되며, 쓰기 경로에서 **베스트에포트 인라인 색인**으로 동기화합니다(조회수 Redis 패턴과 동일 — 색인 실패는 로그만 남기고 게시글 저장은 성공).

- **버전 제약**: Spring Boot 4는 Elasticsearch **9.2.x 클라이언트**(`spring-data-elasticsearch 6.x` + `elasticsearch-java`)를 번들합니다. ES 8 서버는 프로토콜(호환 미디어 타입)이 맞지 않아 통신이 깨지므로 **서버도 ES 9.2.x**를 써야 합니다. docker-compose는 `docker/elasticsearch/Dockerfile`(공식 이미지 + `analysis-nori` 플러그인)을 빌드해 `reactive-elasticsearch-nori:9.2.2`로 띄웁니다.
- **인덱스/분석기**: `boards` 인덱스는 기동 시 `BoardSearchIndexInitializer`(`@EventListener(ApplicationReadyEvent)` + `runBlocking` 브리지)가 멱등 생성합니다. Nori 분석기 정의는 `resources/elasticsearch/board-settings.json`(nori_tokenizer, decompound_mode=mixed + nori_part_of_speech/readingform/lowercase 필터), 필드 매핑은 `board-mappings.json`(title/content를 `korean` 분석기로). `BoardDocument`의 `@Setting`/`@Mapping`이 이 JSON을 가리키며 매핑의 최종 권위입니다.
- **검색 쿼리**: `BoardSearchAdapter`가 `NativeQuery` + `multi_match`(title^2, content)로 검색하고, 기본 `_score` 내림차순(관련도순)으로 정렬 + `<em>` 하이라이트를 반환합니다. 다건은 관례대로 `Flow<BoardSearchHit>`.
- **엔티티 분리**: 도메인 `Board` ↔ `BoardDocument` 변환은 `BoardDocumentMapper`에서만. 도메인 모델에는 ES 애노테이션이 새어 들어가지 않습니다(R2DBC 엔티티 규칙과 동일).
- **정합성 회복**: 인라인 색인 누락이나 인덱스 재생성 시 `POST /api/boards/search/reindex`로 DB를 키셋 순회하며 전체 재색인합니다. 색인은 **페이지 단위 벌크(`BoardSearchPort.indexAll` → ES `saveAll`)**로 수행하고, 한 페이지가 실패해도 그 페이지만 건너뛰며 실패 건수를 집계합니다. 결과는 `ReindexResult(indexed, failed)`로 반환됩니다(응답 `result.reindexed`/`result.failed`). ES refresh 간격(기본 ~1s) 탓에 색인 직후 검색에는 지연이 있을 수 있습니다.

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
  boardMapper.toEntity() → boardR2dbcRepository.save() → boardMapper.toDomain()
     │
     ▼
BoardR2dbcRepository     adapter/out/persistence/BoardR2dbcRepository.kt
  CoroutineCrudRepository.save() → DB (R2DBC, non-blocking)
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
- The whole chain is `suspend` — no thread is blocked on I/O; R2DBC drives the DB round-trip non-blocking.
- `BoardService` calls `boardRepositoryPort.save()` — it never sees `BoardPersistenceAdapter` or R2DBC.
- `CreateBoardCommand.init` fires at construction time inside the controller, so the service always receives valid data.
- `BoardMapper` and `BoardWebMapper` are the only places where layer boundaries are crossed via object conversion. Neither the domain model nor the R2DBC entity ever leaks past their respective mappers.
