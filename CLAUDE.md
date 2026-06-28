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

> **Note**: No database is configured. `application.properties` only sets `spring.application.name` and `build.gradle` has no H2/embedded-DB dependency. `bootRun` will fail at startup without a DataSource. Add an H2 test-scope dependency and configure `spring.datasource.*` before running.

## Architecture

This is a **Hexagonal Architecture (Ports and Adapters)** board API in Kotlin + Spring Boot 4 / JDK 21.

The three concentric layers — with dependency arrows pointing strictly inward:

```
Adapter (in/out)  →  Application (ports + service)  →  Domain (model + exception)
```

### Layer responsibilities

| Layer | Package | Role |
|---|---|---|
| **Domain** | `domain.model`, `domain.exception` | Pure Kotlin classes; zero Spring/JPA annotations |
| **Application** | `application.port.in`, `application.port.out`, `application.service` | UseCase interfaces (in-ports), Repository interfaces (out-ports), `BoardService` orchestrates between them |
| **Adapter-in** | `adapter.in.web` | `BoardController` injects UseCase interfaces (never the concrete service); `BoardWebDto` holds Request/Response DTOs |
| **Adapter-out** | `adapter.out.persistence` | `BoardJpaEntity` (JPA-annotated), `BoardPersistenceAdapter` implements `BoardRepositoryPort`, `BoardMapper` converts between Entity ↔ Domain |

### Key patterns

- **Domain model is immutable** (`data class Board`). Mutations return a new instance via `.copy()` (see `Board.update()`).
- **Self-validating commands**: `CreateBoardCommand` validates both fields in its `init` block (title not blank, content ≥ 10 chars). `UpdateBoardCommand` does **not** self-validate — title-blank validation lives in `Board.update()` and there is no content-length check on update.
- **Strict separation of JPA Entity and Domain Model**: `BoardMapper` is the only place where `BoardJpaEntity` ↔ `Board` conversion happens. Never put `@Entity` on a domain model.
- `BoardService` is annotated `@Transactional` at the class level; read operations override with `@Transactional(readOnly = true)`.
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
  boardMapper.toEntity() → boardJpaRepository.save() → boardMapper.toDomain()
     │
     ▼
BoardJpaRepository       adapter/out/persistence/BoardJpaRepository.kt
  JpaRepository.save() → DB
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
- `BoardService` calls `boardRepositoryPort.save()` — it never sees `BoardPersistenceAdapter` or JPA.
- `CreateBoardCommand.init` fires at construction time inside the controller, so the service always receives valid data.
- `BoardMapper` and `BoardWebMapper` are the only places where layer boundaries are crossed via object conversion. Neither the domain model nor the JPA entity ever leaks past their respective mappers.
