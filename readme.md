# 🧩 event-driven-board

> 헥사고날 아키텍처(Ports & Adapters) 게시판을, **이벤트 기반 MSA**로 확장한 Kotlin + Spring Boot 4 / **JDK 25 LTS** 프로젝트입니다.
> 게시판 정본(PostgreSQL/JPA)과 검색 인덱스(Elasticsearch)를 **Kafka Transactional Outbox**로 분리하고, **Spring MVC + 가상 스레드(Virtual Threads)** 위에서 동작합니다.

이 저장소는 아키텍처의 경계(포트-어댑터)를 그대로 둔 채 **바깥(스택)만 여러 번 갈아 끼우며** 검증해 왔습니다 — 도메인 모델은 한 줄도 바뀌지 않습니다. 한때는 논블로킹 리액티브 스택(WebFlux + 코루틴 + R2DBC)이었고, 지금은 **JDK 25 LTS의 가상 스레드 위 블로킹 스택(MVC + JPA)** 입니다. 이 전환의 "왜"는 아래 [스택 전환 근거](#-스택-전환-근거-webfluxr2dbc--mv--가상-스레드--jpa)에 정리했습니다. 헥사고날의 값어치가 바로 여기서 드러납니다 — 웹/영속성 어댑터와 포트 시그니처(블로킹/논블로킹)만 바뀌고 도메인·유즈케이스의 비즈니스 규칙은 불변이었습니다.

핵심 변화는 **검색 색인의 분리**입니다. 예전에는 게시글을 쓸 때 같은 스레드에서 Elasticsearch에 인라인으로 색인했고, 실패하면 누락될 수 있었습니다. 지금은 게시글 쓰기와 **원자적으로** 아웃박스에 이벤트를 남기고, 릴레이가 Kafka로 발행하며, 별도 서비스(`search-indexer`)가 이를 소비해 색인합니다. 정합성은 "베스트에포트"에서 **아웃박스가 보장하는 최종 일관성**으로 올라갔습니다.

---

## 🧩 모노레포 구조

**Gradle 멀티모듈 모노레포**(`rootProject.name = "board-platform"`)입니다. 세 모듈이 하나의 이벤트로 이어집니다.

| 모듈 | 역할 | 스택 |
|---|---|---|
| **`event-contract`** | 두 서비스가 공유하는 **이벤트 계약**(`BoardChangedEvent`, 토픽명). 순수 Kotlin, 프레임워크 무의존 | Kotlin only |
| **`board-service`** | 게시판 API(정본, JPA/PostgreSQL) + 검색 쿼리/재색인 + **Transactional Outbox 프로듀서** | MVC(가상 스레드) · JPA(+JDSL) · Flyway · Redis · ES(reader) · Security · Kafka |
| **`search-indexer`** | `board-changed`를 소비해 ES 색인을 갱신하는 **컨슈머(ES writer)** | Spring Kafka · Elasticsearch · WebFlux/Netty(actuator) · OTLP 관측성 |

```
                                   ┌──────────────── event-contract (BoardChangedEvent) ────────────────┐
                                   │                          (컴파일 타임 공유)                          │
                                   ▼                                                                     ▼
┌───────────────────────────────────────────┐        Kafka         ┌────────────────────────────────────────┐
│  board-service                              │   (board-changed)    │  search-indexer                          │
│  ┌────────────────────────────────────┐    │  ═══════════════▶    │  @KafkaListener → ApplyBoardChange       │
│  │ write TX { board + outbox insert }  │    │                      │    → Elasticsearch upsert / delete       │
│  └────────────────────────────────────┘    │                      └────────────────────────────────────────┘
│  Outbox Relay(폴링) → Kafka 발행            │                                       │
│  검색 쿼리(GET /search) · 재색인(reindex) ──┼───────── read ───────────────────────┘ (같은 boards 인덱스)
└───────────────────────────────────────────┘
```

- **왜 모노레포인가**: `event-contract`를 두 서비스가 **컴파일 타임에 공유**해, 이벤트 스키마가 어긋나면 빌드가 깨집니다(MSA에서 가장 아픈 계약 드리프트를 구조로 차단). 배포는 서비스별 `bootJar`/이미지로 **독립**입니다(EKS 등에 파드 분리 배포).
- 루트 `subprojects{}`가 Kotlin/JDK25/ktlint 공통 컨벤션을 적용합니다. `koverVerify`(커버리지 게이트)는 `board-service`에 적용됩니다.

---

## 🛠 Tech Stack

- **Language / Runtime**: Kotlin, **JDK 25 LTS**
- **Framework**: Spring Boot **4.0.1**, Spring **MVC(Tomcat) + 가상 스레드**(`spring.threads.virtual.enabled=true`) — `board-service`
- **동시성**: 요청 처리는 **가상 스레드 위 블로킹** I/O. Kotlin Coroutines는 대용량 아카이브 배치의 구조적 동시성(바운드 Channel 팬아웃)에만 사용
- **DB(정본)**: Spring Data **JPA(Hibernate)** + **Kotlin JDSL**(코드젠 없는 타입세이프 쿼리), PostgreSQL. 스키마는 **Flyway**로 버전 관리
- **메시징**: **Apache Kafka**(KRaft) — Transactional Outbox로 `board-changed` 토픽 발행/소비
- **검색**: Elasticsearch **9.2.x** + Nori(한글 형태소). Spring Boot 4의 ES 클라이언트가 9.x라 **서버도 9.x**여야 합니다(ES 8 프로토콜 비호환)
- **캐시/카운터**: Redis(Lettuce, 블로킹 `StringRedisTemplate`) — 조회수 write-back 버퍼
- **인증**: (서블릿) Spring Security + 자체 발급 HS256 JWT(access) + 불투명 리프레시 토큰(회전·재사용 감지), 로그인 rate limiting, CORS
- **회복탄력성**: Resilience4j 서킷브레이커(어댑터 레벨, Redis/Kafka/ES) + 자원별 타임아웃 상한 — application/도메인은 무의존
- **관측성**: Grafana **Alloy + LGTM**(Mimir/Loki/Tempo) — metrics·logs·traces를 모두 OTLP로 push, **두 서비스 모두** 계측 + Kafka 경계 분산 트레이싱
- **문서**: springdoc-openapi(Spring MVC) — Swagger UI
- **빌드/품질**: Gradle 멀티모듈, ktlint, Kover(라인 ≥ 92%), **Konsist**(아키텍처 규칙을 테스트로 강제)
- **테스트**: Kotest, MockK, MockMvc, **Testcontainers**(PostgreSQL/Redis/Elasticsearch), **`@EmbeddedKafka`**(인-JVM 브로커)
- **CI**: GitHub Actions(모든 브랜치 push + main PR)

---

## 🔁 스택 전환 근거 (WebFlux/R2DBC → MVC + 가상 스레드 + JPA)

> 이 프로젝트는 원래 **WebFlux + 코루틴 + R2DBC** 풀 리액티브 스택이었습니다. 이를 **JDK 25 LTS + Spring MVC + 가상 스레드 + JPA(Kotlin JDSL) + Flyway** 로 옮겼습니다. "리액티브가 대세라더라"를 좇지 않고, **실제로 무엇이 이득이고 무엇이 비용인지**를 비교한 뒤 내린 결정입니다.

**왜 리액티브를 걷어냈나 (비용의 재평가)**
- **리액티브의 본래 값어치는 "적은 스레드로 높은 동시성"** 입니다. 하지만 그 대가로 스택트레이스가 끊기고(디버깅 난이도↑), 라이브러리가 리액티브 체인에 오염되며, `ThreadLocal`(MDC/트랜잭션/시큐리티 컨텍스트) 전파가 까다롭습니다.
- **R2DBC 생태계 미성숙**: JPA/Hibernate가 20년간 다져온 것(연관관계·더티체킹·2차 캐시·풍부한 쿼리 도구)이 R2DBC엔 없습니다. 실무에서 R2DBC는 여전히 "특수 상황용"이고, 대다수 서비스의 기본값은 JPA입니다.
- 게시판 CRUD 같은 **일반 트래픽 서비스**에서 리액티브의 이점은 대부분 과잉이며, 팀이 치르는 복잡도 비용이 더 큽니다.

**왜 지금은 "가상 스레드 + 블로킹"이 맞나 (JDK 25 LTS의 결정타)**
- **가상 스레드(Virtual Threads, JEP 444, JDK 21 GA)** 는 요청마다 값싼 가상 스레드를 배정합니다. 블로킹 I/O(JPA/Redis/ES) 동안 가상 스레드는 캐리어(플랫폼) 스레드를 **양보(unmount)** 하므로, **평범한 블로킹 코드가 리액티브에 준하는 확장성**을 냅니다 — 리액티브의 복잡도 없이.
- **JDK 24의 JEP 491(가상 스레드 피닝 해결)** 이 결정적입니다. 기존엔 `synchronized` 블록 안에서 블로킹하면 가상 스레드가 캐리어에 **고정(pinning)** 돼 확장성이 무너졌는데(그래서 라이브러리 전반을 `ReentrantLock`으로 바꿔야 했음), JDK 24에서 이 제약이 제거됐고 **JDK 25 LTS로 안정화**됐습니다. 즉 커넥션 풀·드라이버·로깅 등 `synchronized`가 깔린 기존 블로킹 생태계를 **그대로** 가상 스레드 위에 올려도 피닝 없이 확장됩니다. 이 한 조각 때문에 "블로킹 스택 + 가상 스레드"가 비로소 실무 기본값으로 올라섰습니다.
- **디버깅·관측 편의**: 스택트레이스가 온전하고, `ThreadLocal` 기반 MDC/트랜잭션/`SecurityContextHolder`가 그대로 동작합니다. 장애 분석·프로파일링이 리액티브보다 훨씬 쉽습니다.

**전환에서 지킨 것 / 바꾼 것**
- **도메인·유즈케이스(비즈니스 규칙)는 불변** — 포트-어댑터 경계 덕분에 바뀐 건 어댑터와 포트 시그니처(`suspend`/`Flow` → 블로킹 `fun`/`List`)뿐입니다.
- **코루틴은 버리지 않고 제자리로** — I/O 동시성용으로 전 계층에 흩뿌리는 대신, **진짜 구조적 동시성이 필요한 아카이브 배치**(생산자 1 + 워커 N, 바운드 `Channel` 백프레셔)에만 남겼습니다. "코루틴이 대세라 다 쓴다"가 아니라 "값을 내는 곳에만 쓴다".
- **조회는 Kotlin JDSL** — QueryDSL의 kapt 코드젠(느린 빌드·Kotlin 연동 마찰) 대신 순수 Kotlin DSL로 타입세이프 동적 쿼리(키셋 페이지네이션·stale 스캔). 단순 조회는 Spring Data 파생 쿼리, unnest 배치 UPDATE·아웃박스는 네이티브 SQL(JdbcTemplate)로 — **도구를 목적에 맞게** 씁니다.
- **Flyway 도입** — R2DBC 시절 `CREATE TABLE IF NOT EXISTS` 방식 초기화를 **버전 관리 마이그레이션**(`V1__init.sql`, `flyway_schema_history`)으로 교체해 스키마 진화·롤백 이력을 확보했습니다.

> 요약: **"리액티브가 빠르다더라"를 그대로 믿지 않고**, JDK 25 LTS에서 피닝이 해결된 가상 스레드가 "블로킹의 단순함 + 리액티브급 확장성"을 동시에 준다는 점을 근거로 스택을 되돌렸습니다. 아키텍처(헥사고날)가 이 교체를 **도메인 변경 0줄**로 흡수했다는 것이 이 저장소가 증명하려는 핵심입니다.

---54

## 📂 Project Structure

```
board-platform/                     (rootProject)
├── event-contract/                 공유 이벤트 계약 (순수 Kotlin)
│   └── demo.board.events
│       └── BoardChangedEvent        TOPIC="board-changed", eventId(멱등키), CREATED/UPDATED/DELETED
│
├── board-service/                  게시판 API + Outbox 프로듀서
│   └── demo.board
│       ├── adapter
│       │   ├── in
│       │   │   ├── web               Controller(블로킹), WebDto, GlobalExceptionHandler
│       │   │   └── batch             @Scheduled 트리거(아카이브·조회수 플러시·아웃박스 릴레이)
│       │   └── out
│       │       ├── persistence       JPA 엔티티/리포지토리/어댑터(+Kotlin JDSL), Outbox 어댑터, SpringTransactionRunner
│       │       ├── messaging         KafkaEventPublisherAdapter (EventPublisherPort 구현)
│       │       ├── redis             조회수 버퍼 · 분산 락 · 로그인 rate limiter
│       │       ├── search            ES 검색 어댑터(reader) · BoardDocument · 재색인
│       │       ├── security          BCrypt · Nimbus JWT · SHA-256 리프레시 토큰 해시
│       │       └── observability     Micrometer 어댑터
│       ├── application
│       │   ├── port.in               UseCase 인터페이스 + Command
│       │   ├── port.out              Repository/Search/Outbox/EventPublisher/TransactionRunner/RefreshToken/RateLimiter 포트
│       │   └── service               BoardService · BoardSearchService · RelayOutboxService · AuthService 등
│       ├── config                    Security(+CORS) · Kafka 프로듀서 · Resilience4j · OTel · Clock · OpenApi
│       └── domain                    model(Board, User) · exception (순수 Kotlin)
│
└── search-indexer/                 board-changed 컨슈머 (ES writer)
    └── demo.board.indexer
        ├── adapter
        │   ├── in.messaging          BoardChangedListener (@KafkaListener)
        │   └── out.search            ElasticsearchBoardIndexAdapter · BoardDocument · 인덱스 초기화
        ├── application
        │   ├── port.in               ApplyBoardChangeUseCase
        │   ├── port.out              BoardIndexPort
        │   └── service               BoardIndexService
        ├── config                    Kafka 컨슈머 · OTel
        └── domain                    IndexedBoard (순수 Kotlin)
```

> 각 서비스는 **헥사고날 계층**을 독립적으로 유지합니다 — 안쪽(Domain)으로만 의존이 향하고, ES/Kafka 같은 기술은 어댑터 뒤에 숨습니다.

---

## ⚡ Conventions (블로킹 + 가상 스레드)

- 포트 메서드는 **평범한 블로킹 `fun`**, 다건 조회는 포트 경계에서 `List<T>`. 요청은 가상 스레드 위에서 처리되므로 블로킹 I/O(JPA/Redis/ES)가 플랫폼 스레드를 점유하지 않습니다. **단 하나의 예외**는 `ArchiveStaleBoardsUseCase.archiveStaleBoards`로, 아카이브 배치가 내부에서 구조적 동시성(바운드 `Channel` 팬아웃)을 쓰기 때문에 `suspend`를 유지합니다.
- **트랜잭션 경계는 좁게 — DB 접근에만.** `BoardService`엔 클래스 레벨 `@Transactional`이 없습니다. Redis 증가 같은 외부 부수효과는 트랜잭션 밖에서, 조회는 단일 SELECT로 트랜잭션 없이 수행합니다. **단 하나의 예외**가 쓰기입니다 — "게시글 저장 + 아웃박스 기록"은 반드시 원자적이어야 하므로 이 둘만 트랜잭션으로 묶습니다([이벤트 파이프라인](#-이벤트-파이프라인-transactional-outbox--kafka--search-indexer) 참고).
- **코루틴은 값을 내는 곳에만**: 아카이브 배치의 생산자/워커 팬아웃(바운드 `Channel`)에만 남았고, 워커는 `Dispatchers.IO`에서 블로킹 JPA를 호출합니다. `suspend`가 될 수 없는 드라이빙 어댑터(`@Scheduled` 아카이브 트리거)는 어댑터 경계 안에서 `runBlocking`으로 브리지합니다.
- 목록/스캔은 **키셋(seek) 페이지네이션**: `WHERE id < :cursor ORDER BY id DESC LIMIT size+1`. OFFSET 없이 뒤쪽 페이지도 성능이 일정합니다.

---

## 📐 Architecture Principles

### 1. 의존성 규칙 (안쪽으로만)
`Adapter → Application → Domain`. 도메인은 어떤 바깥 계층에도 의존하지 않습니다. 이 규칙은 관례가 아니라 **Konsist 아키텍처 테스트로 강제**됩니다(`board-service/.../architecture/ArchitectureTest.kt`) — 깨지면 `./gradlew test`가 실패합니다. 도메인의 프레임워크 무의존, `@Table`/`@Document` 엔티티의 패키지 격리까지 검사합니다.

### 2. 도메인 중심 설계
`Board`는 불변 `data class`이며, 수정은 `Board.update()`가 새 인스턴스를 반환합니다(제목 길이·내용 최소 길이 같은 불변식을 도메인이 소유). 아카이브 대상 판정 `Board.isStale(now, retentionDays)`, 소유권 판정 `Board.isOwnedBy(userId)`도 도메인의 책임입니다.

### 3. 포트와 어댑터
In-port(UseCase)는 컨트롤러/스케줄러/리스너가 구동하고, Out-port(Repository/Search/Outbox/EventPublisher/…)는 어댑터가 구현합니다. 관심사가 다르면 포트를 나눕니다(ISP) — 예: 일반 CRUD `BoardRepositoryPort` vs 대용량 스트리밍 `BoardBatchQueryPort`, 트랜잭션 경계 `TransactionRunnerPort`.

### 4. 엔티티 ↔ 도메인 엄격 분리
`@Entity`/`@Table` JPA 엔티티 ↔ 도메인은 `BoardMapper`에서만, `@Document` ES 문서 ↔ 도메인은 `BoardDocumentMapper`(board-service)/`ElasticsearchBoardIndexAdapter`(search-indexer)에서만 변환합니다. 프레임워크 애노테이션이 도메인 모델로 새어 들어가지 않습니다.

---

## 🔄 이벤트 파이프라인 (Transactional Outbox → Kafka → search-indexer)

게시판 정본(DB)과 검색 인덱스(ES)를 분리하고, 이벤트로 최종 일관성을 맞추는 이 프로젝트의 **핵심 MSA 흐름**입니다.

```
[POST/PUT/DELETE /api/boards]
        │
        ▼  board-service
  ┌─────────────────────────────────────────────┐
  │ TransactionRunner.execute {                  │   ← 하나의 트랜잭션 (원자적)
  │     boardRepositoryPort.save(board)          │
  │     boardEventOutboxPort.record(event)       │   board_outbox 테이블에 INSERT
  │ }                                            │
  └─────────────────────────────────────────────┘
        │
        ▼  OutboxRelayScheduler (@Scheduled, opt-in)
  RelayOutboxService: 미발행 행을 id 순으로 읽어 발행, 성공분만 published 표시
        │
        ▼  KafkaEventPublisherAdapter → Kafka topic "board-changed" (key = boardId)
        ═══════════════════════════════════════════════════════════════════▶
        │
        ▼  search-indexer  @KafkaListener
  BoardIndexService: CREATED/UPDATED → ES upsert, DELETED → ES delete
```

- **왜 아웃박스인가**: "DB 저장 후 곧바로 Kafka 발행"은 두 자원에 걸친 이중쓰기라, 저장은 됐는데 발행 직전에 죽으면 이벤트가 **유실**됩니다. Transactional Outbox는 이벤트를 **게시글과 같은 DB 트랜잭션**으로 기록해 유실을 없애고, 발행은 릴레이가 별도로 재시도합니다. `BoardService`의 "no `@Transactional`" 원칙에서 이 쓰기 경로 하나만 트랜잭션을 쓰는 이유입니다(경계는 `TransactionRunnerPort` out-port로 추상화 — 서비스는 Spring 트랜잭션 기술을 모릅니다).
- **순서 보존**: 릴레이는 미발행 이벤트를 `id` 오름차순으로 발행하다 **한 건이라도 실패하면 그 지점에서 멈춥니다**(뒤 이벤트를 앞지르지 않음). 다음 사이클이 실패 지점부터 재시도합니다. Kafka는 `key = boardId`로 파티셔닝돼 **같은 게시글의 이벤트 순서**를 보장합니다.
- **at-least-once + 멱등**: 발행 후 `published` 표시 전에 죽으면 다음 사이클이 재발행합니다(유실 대신 중복). 소비자는 `_id = boardId` 기준 **upsert/삭제라 자연히 멱등**이고, 파티션 순서까지 있어 "마지막 이벤트가 이긴다"로 안전합니다(별도 `eventId` 중복 저장 불필요).
- **운영 스위치**: 릴레이 스케줄러는 아카이브 배치처럼 `board.outbox.relay.enabled=true`일 때만 활성화됩니다(로컬/테스트는 꺼진 채 Kafka 없이 조용).
- **정합성 회복(무중단 alias 재색인)**: 이벤트 유실·매핑 변경 시 `POST /api/boards/search/reindex`가 DB를 키셋 순회해 **새 버전 인덱스(`boards_v{n}`)에 전량 재구축한 뒤 `boards` alias를 원자적으로 스왑**합니다(스왑 전까지 검색은 옛 인덱스를 보므로 무중단). 색인 실패가 있으면 스왑하지 않고 새 인덱스를 폐기해 **자동 롤백**합니다(재구축이라 과거의 고아 prune이 불필요). 상품도 `POST /api/products/search/reindex`로 동일합니다.

관련 파일: `board_outbox`(db/migration/V1__init.sql), `OutboxPersistenceAdapter`, `RelayOutboxService`, `OutboxRelayScheduler`, `KafkaEventPublisherAdapter`, `SpringTransactionRunner` / (consumer) `BoardChangedListener`, `BoardIndexService`, `ElasticsearchBoardIndexAdapter`.

---

## 📝 API Specification (board-service)

`search-indexer`는 **공개 비즈니스 API가 없는 컨슈머**입니다 — `@RestController`는 없고, k8s liveness/readiness 프로브를 위해 경량 WebFlux/Netty 서버로 actuator(`/actuator/health`, 8081)만 노출합니다. 아래는 `board-service`(8080)의 엔드포인트입니다.

### 인증 (Auth)

| Method | Path | 설명 | 인가 |
|---|---|---|---|
| `POST` | `/api/auth/signup` | 회원가입(ROLE_USER 생성) → 201 | 공개 |
| `POST` | `/api/auth/login` | 로그인, access JWT + refresh 토큰 발급 → 200 (실패 반복 시 429) | 공개 |
| `POST` | `/api/auth/refresh` | 리프레시 토큰 회전 → 새 access+refresh → 200 (무효/재사용 시 401) | 공개 |

### 게시글 (Board)

| Method | Path | 설명 | 인가 |
|---|---|---|---|
| `POST` | `/api/boards` | 생성 → 201 | 인증 |
| `GET` | `/api/boards?cursor=&size=` | 목록(키셋 페이지네이션) → 200 | 공개 |
| `GET` | `/api/boards/{id}` | 단건 조회(조회수 +1) → 200 | 공개 |
| `PUT` | `/api/boards/{id}` | 수정 → 200 | 인증(소유자/관리자) |
| `DELETE` | `/api/boards/{id}` | 삭제 → 204 | 인증(소유자/관리자) |
| `GET` | `/api/boards/search?keyword=&size=` | 한글 전문검색(관련도순) → 200 | 공개 |

### 상품 (Product) — 초성/자동완성 검색

> 초성 검색은 긴 게시글엔 안 맞지만 **짧은 상품명**엔 딱 맞아, 초성이 값을 내는 별도 도메인으로 분리했습니다(Spoqa es-dev 사례의 맥락 그대로). board와 동일한 헥사고날 + Transactional Outbox → Kafka(`product-changed`) → search-indexer → `products` 인덱스 파이프라인을 미러링합니다(board 경로는 무변경, 별도 테이블·토픽·DLQ·게이지).

| Method | Path | 설명 | 인가 |
|---|---|---|---|
| `POST` | `/api/products` | 상품 생성 → 201 | ROLE_ADMIN |
| `GET` | `/api/products/{id}` | 단건 조회 → 200 | 공개 |
| `GET` | `/api/products?cursor=&size=` | 목록(키셋) → 200 | 공개 |
| `GET` | `/api/products/search?keyword=&size=` | 상품명 전문검색(Nori) → 200 | 공개 |
| `GET` | `/api/products/autocomplete?q=ㅅㄱ&size=` | **초성/접두 자동완성** → 200 | 공개 |
| `DELETE` | `/api/products/{id}` | 삭제 → 204 | ROLE_ADMIN |
| `POST` | `/api/products/search/reindex` | DB→ES 전체 재색인(무중단 alias 스왑) → 200 | ROLE_ADMIN |

> **초성 검색(ICU)**: `products` 인덱스의 `name.chosung` 서브필드는 **ICU 분석기**로 초성 색인합니다. `icu_normalizer`(nfkc, decompose)가 완성형 음절("삼각김밥")과 사용자가 타이핑한 호환 자모("ㅅㄱ")를 **모두 결합형 자모로 정규화**하고, `pattern_replace([^ᄀ-ᄒ])`로 **초성만 추출**한 뒤 `edge_ngram`으로 접두 자동완성을 만듭니다. `analyzer`(색인=초성+edge_ngram)와 `search_analyzer`(검색=초성 정규화만)를 분리해 별도 자모 매핑표 없이 동작합니다 — "ㅅㄱ"→삼각김밥·삼계탕, "ㅅㄱㄱ"→삼각김밥, 완성형 "삼"→ㅅ으로 정규화. ES 이미지에 `analysis-icu`가 필요합니다(`docker/elasticsearch/Dockerfile`). 일반 상품명 검색은 `name`(Nori)+`name.ngram`(부분)으로, 자동완성은 `name.chosung`으로 매칭합니다.

### 운영 (Admin / Operational)

| Method | Path | 설명 | 인가 |
|---|---|---|---|
| `POST` | `/api/boards/search/reindex` | DB→ES 전체 재색인 → 200 | ROLE_ADMIN |
| `POST` | `/api/admin/view-counts/flush` | 조회수 즉시 플러시 → 200 | ROLE_ADMIN |
| `POST` | `/api/admin/boards/archive` | 오래된 게시글 즉시 아카이브 → 200 | ROLE_ADMIN |
| `GET` | `/actuator/health` · `/actuator/metrics` · `/swagger-ui.html` · `/v3/api-docs` | 상태·메트릭·문서 | 공개 |

> **인증/인가**: 로그인이 HS256 JWT를 발급하고(`NimbusJwtTokenAdapter`), 이후 요청은 `Authorization: Bearer <token>`. JWT의 `sub`=사용자 id는 생성 시 `author_id`로 기록되고 **수정/삭제 소유권 검사**의 기준이 됩니다 — 소유자(`Board.isOwnedBy`) 또는 관리자만 변경 가능, 그 외엔 **403 `BOARD_ACCESS_DENIED`**(IDOR 차단), 토큰 없으면 401. 인가 규칙은 도메인 + 서비스(`assertCanModify`, 관리자 우회)에 두고, 컨트롤러는 `AuthenticatedUserProvider`로 요청자 정보를 커맨드에 실어 넘깁니다. 비밀번호는 BCrypt, 사용자 영속화·해싱·토큰 발급은 각각 out-port로 분리돼 서비스는 Spring Security를 모릅니다. 로그인은 access JWT와 함께 **불투명 리프레시 토큰**을 발급하고(서버는 SHA-256 해시만 `refresh_tokens`에 저장), `POST /api/auth/refresh`가 **회전 + 재사용 감지**(폐기된 토큰 재제시 시 해당 사용자 전체 무효화)를 수행합니다. 로그인 **brute-force 방어**는 Redis 슬라이딩 윈도우 rate limiter로 username별 실패를 세어 임계치 초과 시 **429**로 차단합니다. SPA용 **CORS**는 `board.security.cors.allowed-origins`로 허용 오리진을 주입합니다.

> **응답 포맷**: 모든 응답은 `BaseResponse<T>{code, status, result}`. 예외는 `@RestControllerAdvice`(`GlobalExceptionHandler`)가 `ErrorCode`로 매핑합니다 — `BoardNotFoundException`→404, `BoardValidationException`/`IllegalArgumentException`→400, `BoardAccessDeniedException`→403, 그 외→500. 도메인은 `ErrorCode`를 모릅니다.

---

## 🗑 Batch: Stale Board Archiving

오래된 게시글을 대량 삭제하는 배치(`ArchiveStaleBoardsService`, board-service). 클래스 레벨 `@Transactional` 없이:
- **스트리밍 읽기**: `BoardBatchQueryPort.findStaleBoards()`가 키셋 페이지네이션 `Flow`로 흘려보내 전체를 메모리에 올리지 않습니다.
- **백프레셔 + 동시성**: `coroutineScope` 안에서 바운드 `Channel`(용량 = concurrency)로 생산자 1 + 워커 N. 소비가 밀리면 압력이 DB 읽기까지 자동 전파됩니다.
- **도메인이 최종 권위**: SQL 사전 필터 후에도 `Board.isStale()`로 다시 확인하고 삭제합니다.
- **내결함성**: 청크별 커밋. 일부 청크 실패는 건너뛰고 `ArchiveResult.failedChunks`에 집계(skip-and-continue), **전체 실패**는 `IllegalStateException`으로 신호(스케줄러가 성공으로 오인하지 않도록). `CancellationException`은 재전파.
- 튜닝: `board.archiving.*`(enabled 기본 false, cron, retention-days, chunk-size, concurrency). 온디맨드 트리거는 `POST /api/admin/boards/archive`.

> 이 서브시스템의 "왜"와 흐름을 그림·비유로 풀어 쓴 심화 설명은 [`info.md`](./info.md)에 있습니다.

---

## 👁 View Count (Write-Back)

조회마다 DB를 때리지 않도록 Redis에 델타를 모아 주기적으로 DB에 반영하는 write-back 버퍼(board-service).
- **조회**: DB `findById` → Redis `HINCRBY board:views:pending {id} 1` → 응답 = DB 누적값 + 미반영 델타(실시간처럼).
- **플러시**(`@Scheduled`): `RENAME pending → draining`으로 원자적 스냅샷을 뜨고, `UPDATE ... FROM unnest(:ids,:deltas)`로 청크 배치 반영, **commit-then-delete**(반영 성공분만 삭제)로 유실 없이 at-least-once. 다중 인스턴스는 `DistributedLockPort`(Redis SET NX + Lua CAS)로 직렬화.
- **우아한 강등**: Redis가 느리거나 죽으면 `withTimeout` 예산 초과 시 델타 0으로 강등해 DB 값으로 200 응답(조회 자체는 실패하지 않음).
- 튜닝: `board.view-count.*`. 온디맨드 플러시는 `POST /api/admin/view-counts/flush`.

> 심화 설명(포스트잇/회계장부 비유, RENAME 스냅샷의 이유 등)은 [`info.md`](./info.md).

---

## 🔎 Korean Full-Text Search (Elasticsearch + Nori)

한글 형태소 전문검색은 **정본(JPA/PostgreSQL)과 분리된 ES 인덱스**로 제공되며, 색인 동기화는 [이벤트 파이프라인](#-이벤트-파이프라인-transactional-outbox--kafka--search-indexer)을 통합니다. 역할 분담이 명확합니다:

- **`search-indexer` = writer**: `board-changed` 이벤트를 소비해 `boards` 인덱스를 upsert/삭제하고, 인덱스 스키마(Nori 설정/매핑) 생성도 소유합니다.
- **`board-service` = reader**: 검색 쿼리(`GET /api/boards/search`)와 전체 재색인(`reindex`)만 담당합니다.
- **Nori 분석**: `boards` 인덱스는 `korean` 분석기(nori_tokenizer, decompound_mode=mixed + nori_part_of_speech/readingform/lowercase). 정의는 `resources/elasticsearch/board-settings.json`(analysis) / `board-mappings.json`(title/content). `BoardDocument`의 `@Setting`/`@Mapping`이 이 JSON을 가리킵니다.
- **검색 쿼리**: `BoardSearchAdapter`가 `NativeQuery` + `multi_match(title^2, content)`로 검색, 기본 `_score` 내림차순 + `<em>` 하이라이트, `List<BoardSearchHit>` 반환.
- **버전 제약**: Spring Boot 4는 ES **9.2.x 클라이언트**를 번들합니다. ES 8 서버는 프로토콜이 안 맞아 통신이 깨지므로 **서버도 ES 9.2.x**(`docker/elasticsearch/Dockerfile`이 Nori 포함 이미지를 빌드 — 로컬 k8s는 `deploy/build-and-load.sh`가 빌드해 `kind load`로 주입).

> **알려진 tradeoff(중복)**: reader(board-service)와 writer(search-indexer)가 같은 `boards`·`products` 인덱스를 공유하므로 `BoardDocument`/`ProductDocument`와 분석기 설정 JSON(Nori/ICU)이 **두 모듈에 중복**됩니다(매핑 변경 시 양쪽 동기화 필요 — 공유 스키마 모듈 추출은 후속 과제). 또한 `reindex`가 board-service에서 ES에 직접 쓰는 dual-writer 경로라, 이벤트 리플레이 기반으로 옮기는 것도 후속 과제입니다.

---

## 👁‍🗨 Observability (분산)

**두 서비스 모두** metrics·logs·traces를 OTLP로 단일 수집기 **Grafana Alloy**(:4318)에 push하고, Alloy가 **Mimir**(metrics)·**Loki**(logs)·**Tempo**(traces)로 팬아웃합니다(스크레이프 없는 순수 OTLP push).

- **service.name**으로 구분: `board-service` / `search-indexer`. Grafana 대시보드의 `$job` 드롭다운에 각각 나타납니다.
- **로그**: Spring Boot 4 네이티브 구조화 로깅(ECS JSON) + OTel logback appender → OTLP → Loki. trace/span id가 로그에 실려 로그↔트레이스 상관.
- **트레이스(Kafka 경계 포함)**: Micrometer Tracing → OTLP → Tempo. **프로듀서(`KafkaTemplate.observationEnabled`) + 컨슈머(`containerProperties.observationEnabled`)** 관측을 켜 `traceparent`를 Kafka 헤더로 전파하므로, `board-service`의 릴레이 발행 span과 `search-indexer`의 소비 span이 **하나의 트레이스로 연결**됩니다.
- **비즈니스 메트릭**: `ObservabilityPort` out-port로 도메인 사건을 기록(`board_create_total`, `board_view_total`, `board_search_total` 등) — 서비스는 Micrometer를 모릅니다.
- 테스트 프로필에선 OTLP export를 모두 끕니다(Alloy 없이도 조용히 통과).

> 이벤트 파이프라인 관측성(구현됨): search-indexer는 **DLQ**(`board-changed-dlq`, `DefaultErrorHandler`+`DeadLetterPublishingRecoverer`)로 포이즌 메시지를 격리하고, **컨슈머 랙**(`kafka.consumer.*`)과 **아웃박스 미발행 백로그 게이지**(`board.outbox.unpublished`)를 노출하며 Grafana "이벤트 파이프라인" 행에 관련 패널이 있습니다. 외부 자원 장애는 서킷브레이커로 빠르게 실패시킵니다.
> 알려진 한계: HTTP 요청 → 아웃박스 기록 트레이스와 릴레이 폴링 → 발행 트레이스는 여전히 별도입니다(요청 컨텍스트를 아웃박스 행에 저장해 발행 시 복원하면 request→index까지 이을 수 있음 — 후속 과제).

---

## 🧪 Test Strategy

- **단위 테스트**(Kotest + MockK): 서비스/도메인 로직. 포트를 목으로 대체(예: `RelayOutboxService`의 순서보존·실패중단, `BoardIndexService`의 이벤트→색인 매핑).
- **통합 테스트**(Testcontainers): `board-service`는 PostgreSQL/Redis/Elasticsearch 컨테이너로 웹→DB→ES 경로를 검증. ES는 Nori Dockerfile을 빌드해 띄웁니다.
- **end-to-end 이벤트 테스트**: `search-indexer`는 **`@EmbeddedKafka`(인-JVM 브로커) + 실제 ES(Testcontainers)** 로 `발행 → @KafkaListener 소비 → ES 색인/삭제`를 실증합니다(컨텍스트 wiring까지 검증).
- **아키텍처 테스트**(Konsist): 헥사고날 의존성 방향·계층 순수성·엔티티 패키지 격리를 강제. 모노레포에서 다른 모듈은 스코프에서 제외합니다.

```bash
./gradlew test                                   # 전 모듈
./gradlew :board-service:test                    # 단일 모듈
./gradlew test --tests "demo.board.BoardApplicationTests"
```

---

## ✅ Lint (ktlint) & 📊 Coverage (Kover) & Konsist

```bash
./gradlew ktlintCheck            # 코드 스타일 게이트 (전 모듈)
./gradlew ktlintFormat           # 자동 포맷
./gradlew koverVerify            # 커버리지 게이트 (board-service 라인 ≥ 92%)
./gradlew koverHtmlReport        # board-service/build/reports/kover/html/index.html
```
- **Kover**: `board-service` 라인 커버리지 하한 **92%**(부트스트랩 `BoardApplication*` 제외).
- **Konsist**: 아키텍처 규칙을 테스트로 강제 — 일반 `./gradlew test`에 포함돼 돌아갑니다.

---

## 🚀 Getting Started

전체 스택은 **로컬 쿠버네티스(Colima + kind + Helm)** 에서 실행합니다. (IntelliJ에서 앱을 host로 직접 `bootRun`할 때 데이터스토어만 컨테이너로 띄우는 루트 `docker-compose.yml`도 병행 제공합니다.) 상세 런북은 [`deploy/README.md`](./deploy/README.md).

### Prerequisites
JDK 21+, `colima` · `kind` · `helm`(+ `docker` CLI). `brew install colima kind helm`.

### 한 번에 실행
```bash
brew install colima kind helm       # 최초 1회
./deploy/up.sh                      # colima → kind → 이미지 빌드/주입 → helm → 롤아웃 대기, 한 방에 (멱등)
#   관측성(LGTM)까지 함께:  ./deploy/up.sh --obs
kubectl get pods -w                 # 6개(코어) 또는 11개(--obs) Ready 대기
```
끝나면 `board-service`가 kind 포트 매핑으로 **호스트 `localhost:8080`** 에 열립니다(데이터스토어 PostgreSQL/Redis/Elasticsearch/Kafka + 앱이 모두 클러스터 안). 정리는 `./deploy/down.sh`. 단계별 수동 절차·values 튜닝·end-to-end curl 예시는 [`deploy/README.md`](./deploy/README.md).

> `--obs`를 주면 관측성(LGTM: Alloy/Mimir/Loki/Tempo/Grafana)까지 클러스터에 함께 뜨고(파드 11개, colima 12G 권장), Grafana는 `http://localhost:3000`. 기본은 코어만(6개)이라 앱 OTLP export는 꺼집니다.

> `board-service`는 기동 시 **Flyway**가 `db/migration/V*.sql`을 적용해 스키마를 만들고 `flyway_schema_history`로 이력을 추적합니다(JPA는 `ddl-auto=validate`라 매핑 불일치만 감지). 모든 설정은 `${ENV:default}`로 외부화돼 Helm이 ConfigMap/Secret으로 주입합니다.

### 동작 확인 (end-to-end)
회원가입·로그인으로 JWT를 받고 게시글을 생성하면 → board-service가 아웃박스에 이벤트 기록 → 릴레이가 Kafka 발행 → search-indexer가 소비해 ES 색인 → `GET /api/boards/search?keyword=...`로 검색됩니다(ES refresh ~1s 지연 감안). 구체 curl 예시는 `deploy/README.md`.

> **빠른 개발 반복(선택)**: 코드를 고치며 IDE에서 바로 띄우고 싶으면 `:board-service:bootRun`도 됩니다 — 단, 데이터스토어가 필요하므로 `kubectl port-forward`로 클러스터의 postgres/redis/es/kafka를 로컬 포트로 당겨 연결하세요.

---

## 🔄 CI

GitHub Actions(`.github/workflows/ci.yml`): **모든 브랜치 push + main PR**에서 JDK 21로 `ktlintCheck → test → koverVerify → koverHtmlReport`를 실행하고, 테스트/커버리지 리포트를 아티팩트로 업로드합니다(멀티모듈 경로).

---

## 💡 Key Code Features

1. **이벤트 기반 색인 분리** — 쓰기 경로 인라인 색인을 걷어내고 Transactional Outbox → Kafka → search-indexer로 분리(유실 없는 최종 일관성).
2. **트랜잭션 경계 추상화** — `TransactionRunnerPort` out-port로 "쓰기 + 아웃박스"만 원자화, 서비스는 Spring 트랜잭션을 모름.
3. **가상 스레드 위 블로킹 E2E** — MVC + JPA(Hibernate) + Kotlin JDSL, 가상 스레드로 블로킹 I/O를 확장. 코루틴은 아카이브 배치 팬아웃에만.
4. **자가 검증 커맨드 + 통일 응답/예외** — `CreateBoardCommand.init` require, `BaseResponse`/`GlobalExceptionHandler`.
5. **키셋 페이지네이션** — OFFSET 없이 깊이 무관 일정 성능.
6. **분산 관측성** — 두 서비스 OTLP → Alloy → LGTM, Kafka 경계 트레이스 연결.
7. **한글 검색(Nori)** — reader/writer 분리, `multi_match` + 하이라이트.
8. **조회수 write-back** — Redis 버퍼 + commit-then-delete + 분산 락 + 우아한 강등.
9. **대용량 아카이브 배치** — 스트리밍 + 바운드 채널 백프레셔 + 청크 커밋 내결함성.
10. **인증 운영요소** — access JWT + 리프레시 토큰 회전·재사용 감지, 로그인 rate limiting(429), CORS.
11. **회복탄력성** — Resilience4j 서킷브레이커를 어댑터에서만 적용(Redis/Kafka/ES) + 자원별 타임아웃 상한, DLQ로 포이즌 메시지 격리.
12. **강제되는 아키텍처·품질 게이트** — Konsist + Kover 92% + ktlint, CI에서 검증.
