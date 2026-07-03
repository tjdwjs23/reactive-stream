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
./gradlew test --tests "demo.reactivestream.ReactiveStreamApplicationTests"

# Test (single method)
./gradlew test --tests "demo.reactivestream.ReactiveStreamApplicationTests.contextLoads"
```

> **Note**: The app uses **R2DBC (non-blocking) only — no JDBC anywhere**. `application.yml` configures `spring.r2dbc.*`. Schema is initialized at startup by a **`ConnectionFactoryInitializer`** bean (`adapter/out/persistence/R2dbcSchemaInitializer.kt`) that runs `db/schema.sql` over R2DBC (Flyway was removed because it requires JDBC). `db/schema.sql` uses `CREATE TABLE IF NOT EXISTS`, so it is safe to re-run. `bootRun` needs a reachable PostgreSQL (see `application.yml`, default `localhost:5432/hexagonal`). Tests spin up PostgreSQL via Testcontainers (`support/PostgresTestContainer.kt`), which registers only the r2dbc URL and uses a **log-based wait strategy** (the default readiness probe uses JDBC, which is no longer on the classpath).

## Architecture

This is a **Hexagonal Architecture (Ports and Adapters)** board API in Kotlin + Spring Boot 4 / JDK 21, on a **reactive stack: Spring WebFlux + Kotlin coroutines + Spring Data R2DBC**.

**Reactive conventions (apply everywhere):**
- Single-value operations that touch I/O are `suspend fun`. Multi-value reads return `Flow<T>` (never `List` at the port boundary).
- `BoardService`'s `@Transactional` runs on Spring's `ReactiveTransactionManager` (auto-configured by R2DBC) and works on `suspend` functions.
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
- **Self-validating commands**: `CreateBoardCommand` validates both fields in its `init` block (title not blank, content ≥ 10 chars). `UpdateBoardCommand` does **not** self-validate — title-blank validation lives in `Board.update()` and there is no content-length check on update.
- **Strict separation of persistence Entity and Domain Model**: `BoardMapper` is the only place where `BoardR2dbcEntity` ↔ `Board` conversion happens. Never put `@Table`/`@Id` on a domain model.
- `BoardService` is annotated `@Transactional` at the class level (reactive tx manager); read operations override with `@Transactional(readOnly = true)`. `getAllBoards()` returns `Flow<Board>` and is collected at the controller boundary.
- **Batch (`ArchiveStaleBoardsService`)**: no class-level `@Transactional`; a `coroutineScope` fans out a bounded `Channel` (producer collects the keyset-paginated `Flow`, N workers delete chunks). Deletes commit per-chunk. Domain rule `Board.isStale()` is the final authority over the SQL pre-filter.
- `BoardService` implements all four UseCase interfaces; `BoardController` injects them individually by interface, not by the concrete class.
- Business exceptions (`BoardNotFoundException`, `BoardValidationException`) live in `domain.exception` and extend `RuntimeException`.

### REST endpoints

| Method | Path | UseCase |
|---|---|---|
| `POST` | `/api/boards` | `CreateBoardUseCase` → 201 Created |
| `GET` | `/api/boards/{id}` | `GetBoardUseCase` → 200 OK |
| `GET` | `/api/boards` | `GetBoardUseCase` → 200 OK |
| `PUT` | `/api/boards/{id}` | `UpdateBoardUseCase` → 200 OK |
| `DELETE` | `/api/boards/{id}` | `DeleteBoardUseCase` → 204 No Content |
| `GET` | `/api/boards/search?keyword=&size=` | `SearchBoardUseCase` → 200 OK (한글 전문검색, 관련도순) |
| `POST` | `/api/boards/search/reindex` | `ReindexBoardsUseCase` → 200 OK (DB→ES 전체 재색인) |

### Korean full-text search (Elasticsearch + Nori)

한글 형태소 전문검색은 **별도 out-port(`BoardSearchPort`) + ES 어댑터**로 붙어 있습니다. R2DBC(정본)와 ES(검색 인덱스)는 분리되며, 쓰기 경로에서 **베스트에포트 인라인 색인**으로 동기화합니다(조회수 Redis 패턴과 동일 — 색인 실패는 로그만 남기고 게시글 저장은 성공).

- **버전 제약**: Spring Boot 4는 Elasticsearch **9.2.x 클라이언트**(`spring-data-elasticsearch 6.x` + `elasticsearch-java`)를 번들합니다. ES 8 서버는 프로토콜(호환 미디어 타입)이 맞지 않아 통신이 깨지므로 **서버도 ES 9.2.x**를 써야 합니다. docker-compose는 `docker/elasticsearch/Dockerfile`(공식 이미지 + `analysis-nori` 플러그인)을 빌드해 `hexagonal-elasticsearch-nori:9.2.2`로 띄웁니다.
- **인덱스/분석기**: `boards` 인덱스는 기동 시 `BoardSearchIndexInitializer`(`@EventListener(ApplicationReadyEvent)` + `runBlocking` 브리지)가 멱등 생성합니다. Nori 분석기 정의는 `resources/elasticsearch/board-settings.json`(nori_tokenizer, decompound_mode=mixed + nori_part_of_speech/readingform/lowercase 필터), 필드 매핑은 `board-mappings.json`(title/content를 `korean` 분석기로). `BoardDocument`의 `@Setting`/`@Mapping`이 이 JSON을 가리키며 매핑의 최종 권위입니다.
- **검색 쿼리**: `BoardSearchAdapter`가 `NativeQuery` + `multi_match`(title^2, content)로 검색하고, 기본 `_score` 내림차순(관련도순)으로 정렬 + `<em>` 하이라이트를 반환합니다. 다건은 관례대로 `Flow<BoardSearchHit>`.
- **엔티티 분리**: 도메인 `Board` ↔ `BoardDocument` 변환은 `BoardDocumentMapper`에서만. 도메인 모델에는 ES 애노테이션이 새어 들어가지 않습니다(R2DBC 엔티티 규칙과 동일).
- **정합성 회복**: 인라인 색인 누락이나 인덱스 재생성 시 `POST /api/boards/search/reindex`로 DB를 키셋 순회하며 전체 재색인합니다. ES refresh 간격(기본 ~1s) 탓에 색인 직후 검색에는 지연이 있을 수 있습니다.

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
