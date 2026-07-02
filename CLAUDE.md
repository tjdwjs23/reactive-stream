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
./gradlew test --tests "demo.hexagonal.hexagonalback.HexagonalBackApplicationTests"

# Test (single method)
./gradlew test --tests "demo.hexagonal.hexagonalback.HexagonalBackApplicationTests.contextLoads"
```

> **Note**: The app uses **R2DBC (non-blocking)** against PostgreSQL. `application.yml` configures `spring.r2dbc.*` for the runtime and `spring.flyway.*` (JDBC) for schema migrations — Flyway does not support R2DBC, so a JDBC URL is kept solely for migrations. `bootRun` needs a reachable PostgreSQL (see `application.yml`, default `localhost:5432/hexagonal`). Tests spin up PostgreSQL via Testcontainers (`support/PostgresTestContainer.kt`), which registers both the r2dbc URL and the flyway JDBC URL.

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
