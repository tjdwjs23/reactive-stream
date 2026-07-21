# 📖 대용량·분산 처리, 쉽게 이해하기 (info.md)

> 이 문서는 처음 보는 사람도 이해할 수 있게, 이 프로젝트의 까다로운 세 가지를 **비유와 그림**으로 풀어 씁니다.
> ① 조회수 카운터(Redis Write-Back), ② 오래된 게시글 아카이브 배치, 그리고 ③ **검색 색인 분리(Kafka Transactional Outbox)**.
> README가 "무엇을/어떻게"를 정식으로 설명한다면, 여기서는 "왜 이렇게 만들었는지"를 천천히 그려 보입니다.

---

## 0. 먼저, 큰 그림

세 시스템은 서로 다른 종류의 어려움을 풉니다.

| | ① 조회수 카운터 | ② 아카이브 배치 | ③ 검색 색인 분리 |
|---|---|---|---|
| **문제** | 인기 글에 쓰기(UPDATE)가 폭주 | 삭제할 게 너무 많아 한 번에 못 읽음 | 두 저장소(DB·검색엔진)를 어긋나지 않게 |
| **핵심 아이디어** | 빠른 곳(Redis)에 **모았다가** 몰아 반영 | **조금씩 흘려보내며** 나눠 처리 | 쓰기와 **원자적으로** 이벤트를 남겨 나중에 전달 |
| **방향** | 쓰기 홍수를 버퍼로 흡수 | 대량 읽기를 스트림으로 분해 | 서비스 사이를 이벤트로 잇기 |

①②는 **한 서비스 안의 성능/자원 문제**, ③은 **서비스 사이의 정합성 문제**입니다.

---

# 1️⃣ 조회수 카운터 (Redis Write-Back)

## 무엇이 문제인가?

인기 글 하나가 **1초에 1,000번** 조회된다고 합시다. 순진하게 만들면 조회 1번마다 DB에 `UPDATE board SET view_count = view_count + 1`을 날립니다 → **1초에 1,000번의 DB 쓰기**. DB가 비명을 지릅니다. 😱

## 핵심 아이디어: 포스트잇에 모았다가 회계장부에 옮기기

카페 사장이 주문 하나하나를 회계장부에 적지 않고, **포스트잇(Redis)** 에 바를 정(正)자로 모아뒀다가, 한가할 때 **회계장부(DB)** 에 몰아서 옮겨 적는 것과 같습니다.

## 자료구조: Redis Hash 하나

```
board:views:pending   (Redis Hash)
 ├─ "42"  → 17     ← 42번 글이 아직 DB에 반영 안 된 조회 +17
 ├─ "101" → 3
 └─ ...
```

## 흐름 ① : 게시글을 조회할 때 (GET /api/boards/{id})

```
 ┌── DB에서 확정값 읽기 (SELECT만!)  view_count = 1,000
 │
 ├── Redis:  HINCRBY board:views:pending 42 1   → 누적 델타 18 반환
 │
 └── 응답 = DB값(1,000) + 미반영 델타(18) = 1,018   ← 실시간처럼 보임
```

- `HINCRBY`는 **원자적**입니다 — 1,000명이 동시에 눌러도 정확히 +1,000, 유실 없음.
- DB 쓰기는 여기서 **한 번도** 일어나지 않습니다.

### 만약 Redis가 죽으면? (Graceful Degradation)

조회수 하나 때문에 글 조회 전체가 실패하면 안 됩니다. 서킷브레이커 + Redis 명령 타임아웃(1s)으로 빠르게 실패하고, Redis가 느리거나 죽으면 예외를 삼켜 **델타 0으로 강등**해 DB값(1,000)으로 정상 200 응답합니다. ✅ 조회는 살아 있고, 조회수만 잠깐 안 오를 뿐입니다.

## 흐름 ② : 주기적 반영 (Write-Back / 플러시)

`@Scheduled`로 **30초마다** 포스트잇을 회계장부에 옮깁니다.

```
 ┌── RENAME  board:views:pending → board:views:draining      ← 원자적 스냅샷!
 │      (이 순간부터 새 조회는 새 pending에 쌓임 — 섞이지 않음)
 │
 ├── draining을 Map으로 읽어서
 │      UPDATE board AS b SET view_count = b.view_count + d.delta
 │      FROM unnest(:ids, :deltas) AS d(id, delta) WHERE b.id = d.id   ← 청크 배치 UPDATE
 │
 └── 성공한 청크만 draining에서 HDEL  ← commit-then-delete
```

### 왜 `RENAME`을 쓸까? (델타 유실 방지)
그냥 "읽고 나서 지우면" 읽는 사이에 들어온 조회가 사라집니다. `RENAME`으로 **통째로 옮겨** 스냅샷을 만들면, 반영 대상은 draining에 고정되고 새 조회는 새 pending에 안전하게 쌓입니다.

### 왜 청크로 나눠 배치 UPDATE 할까?
5만 개를 하나씩 UPDATE하면 **5만 번 왕복(~13초)**. `unnest`로 1,000개씩 묶으면 **50번 왕복(~0.25초)** — 약 **50배** 빠릅니다.

### commit-then-delete = 유실 대신 재시도(at-least-once)
DB 반영에 **성공한 청크만** 삭제합니다. 반영 도중 죽으면 draining이 남아 다음 플러시가 재시도합니다. 최악의 경우 중복 계수(약간 더 셈)일 뿐, **유실은 없습니다**.

> 튜닝: `search.view-count.*` (flush-interval-ms, flush-chunk-size, flush-enabled). 다중 인스턴스는 `DistributedLockPort`로 "클러스터 전역에서 한 번만" 플러시하도록 직렬화합니다.

**관련 파일** (`search-service/src/main/kotlin/demo/search/`):
`application/service/BoardService.getBoard`, `adapter/out/redis/BoardViewCountRedisAdapter`, `application/service/FlushBoardViewCountsService`, `adapter/in/batch/BoardViewCountFlushScheduler`, `adapter/out/persistence/BoardPersistenceAdapter.addViewCountsBatch`.

---

# 2️⃣ 오래된 게시글 아카이브 배치 (ArchiveStaleBoardsService)

## 무엇이 문제인가?

365일 지난 게시글을 지우는데 대상이 **수백만 건**입니다. 순진하게 `SELECT * FROM board WHERE ...`로 전부 리스트에 담으면 → 💥 **OutOfMemory**. 한 트랜잭션으로 수백만 건을 지우면 락과 커넥션을 너무 오래 붙잡습니다.

## 핵심 아이디어: 공장 컨베이어 벨트

```
 [생산자]  DB를 조금씩 읽어(스트리밍)  →  [벨트(바운드 채널)]  →  [작업자 1..N] 청크 삭제
   키셋 페이지네이션                       가득 차면 생산자가 멈춤          동시 처리
```

## 4가지 핵심 장치

### ① 스트리밍 읽기 — 키셋 페이지네이션
전부 메모리에 안 올리고 **키셋 페이지 단위 `List`를 바운드 채널로** 흘려보냅니다(블로킹 스택이라 `Flow`가 아니라 `findStalePage(...): List<Board>`가 페이지를 반환하고, 그 페이지를 채널로 send). OFFSET 대신 **"마지막으로 읽은 id 다음"** 을 조건으로 다음 페이지를 읽습니다.
```
❌ OFFSET 100000 LIMIT 500   → 앞 10만 건을 매번 세고 버림(뒤로 갈수록 느림)
✅ WHERE created_at < :before AND id > :lastId ORDER BY id LIMIT 500   → 깊이 무관 일정 속도
```
`idx_board_created_at` 인덱스가 범위 필터를 받쳐 줍니다.

### ② 바운드 채널 = 큐 + 백프레셔
`Channel(capacity = concurrency)`. 벨트가 가득 차면 생산자의 `send`가 **suspend**되어 DB 읽기를 멈춥니다. **핵심**: 소비자가 밀리면 그 압력이 벨트를 거쳐 생산자, 나아가 DB 읽기까지 자동으로 전파됩니다 → 메모리가 일정하게 유지됩니다(벨트 몇 칸 + 페이지 하나).

### ③ 워커 N개 = 동시 처리
`List(concurrency) { launch { for (chunk in channel) processChunk(chunk) } }`. 코루틴이라 가볍고, 실제 동시성 상한은 DB 커넥션 풀이 정합니다.

### ④ 청크 커밋 + 내결함성
- 삭제 전 `Board.isStale()`로 **다시 확인**(SQL 사전 필터가 아니라 **도메인이 최종 권위**).
- 청크마다 `deleteByIds`로 짧게 커밋.
- 한 청크가 실패해도 `try/catch`로 건너뛰고 계속(`failedChunks++`, skip-and-continue). **전체가 실패**하면 `IllegalStateException`으로 신호(스케줄러가 성공으로 착각하지 않게). 상위 취소(`CancellationException`)는 삼키지 않고 재전파.

> 튜닝: `search.archiving.*` (enabled 기본 false, cron, retention-days, chunk-size, concurrency). 온디맨드 실행은 `POST /api/admin/boards/archive`.

**관련 파일** (`search-service/src/main/kotlin/demo/search/`):
`application/service/ArchiveStaleBoardsService`, `adapter/out/persistence/BoardBatchPersistenceAdapter`(findStalePage, deleteByIds — Kotlin JDSL/JPA), `domain/model/Board.isStale`, `adapter/in/batch/StaleBoardArchivingScheduler`.

---

# 3️⃣ 검색 색인 분리 (Kafka Transactional Outbox → search-indexer)

## 무엇이 문제인가?

게시글은 **PostgreSQL(정본)** 에, 검색은 **Elasticsearch** 에 있습니다. 글을 쓰면 두 곳을 다 맞춰야 합니다. 예전엔 이렇게 했습니다:

```
❌ 순진한 방법:
   1. DB에 게시글 저장
   2. 곧바로 ES에 색인
   → 1번은 성공했는데 2번 직전에 서버가 죽으면?  💥 검색엔진에 영영 안 올라감(유실)
   → ES가 느리면 글쓰기 응답도 같이 느려짐
```

두 저장소에 각각 쓰는 것을 **이중 쓰기(dual write)** 라 하고, 중간에 죽으면 둘이 어긋납니다. 이건 분산 시스템의 고전적 함정입니다.

## 핵심 아이디어: 우체통에 사본을 같이 넣어둔다

편지를 **금고(DB)** 에 넣는 **바로 그 순간**, 같은 손동작으로 **발송함(outbox 테이블)** 에도 사본을 넣습니다. 둘은 한 번의 행동(같은 트랜잭션)이라 **절대 따로 놀 수 없습니다** — 금고에 들어갔으면 발송함에도 반드시 있습니다. 그리고 **집배원(릴레이)** 이 주기적으로 발송함을 비워 **우편(Kafka)** 으로 보냅니다. 받는 사람(search-indexer)이 그걸 받아 검색엔진에 정리합니다.

## 전체 흐름도

```
search-service                                                  search-indexer
─────────────                                                  ──────────────
[POST /api/boards]
      │
      ▼  하나의 트랜잭션 (원자적 — 둘 다 되거나 둘 다 롤백)
  ┌──────────────────────────────────────────────┐
  │  board 테이블 INSERT                           │
  │  board_outbox 테이블 INSERT (이벤트 사본)       │
  └──────────────────────────────────────────────┘
      │
      ▼  집배원: OutboxRelayScheduler (~200ms 주기 폴링, fixedDelay)
   발송함(board_outbox)에서 "아직 안 보낸" 행을 id 순으로 꺼내
   Kafka로 발행 → 성공한 것만 "보냄" 표시(published_at)
      │
      ▼  Kafka topic "board-changed"   (key = boardId)
      ═══════════════════════════════════════════════════════▶
                                                          │
                                                          ▼  @KafkaListener
                                            CREATED/UPDATED → ES upsert
                                            DELETED         → ES delete
```

## 왜 이렇게까지?

### ① 원자성 = 유실 제거
"게시글 저장"과 "이벤트 기록"이 **같은 DB 트랜잭션**입니다. 그래서 "저장은 됐는데 이벤트가 없음"이 **원천적으로 불가능**합니다. 발행이 잠깐 늦어질 수는 있어도(집배원이 아직 안 옴), 사라지진 않습니다.
> 이 프로젝트는 평소 트랜잭션을 거의 안 씁니다(단일 문장 오토커밋). **딱 이 쓰기 경로 하나**만 트랜잭션을 쓰며, 그 경계조차 `TransactionRunnerPort`로 감싸 서비스는 Spring 트랜잭션 기술을 모릅니다.

### ② 순서 보존 = 앞 편지를 앞질러 보내지 않기
집배원은 발송함을 **id 순(먼저 넣은 것 먼저)** 으로 보내다가, **한 통이라도 실패하면 거기서 멈춥니다**. 뒤 편지를 먼저 보내버리면 순서가 꼬이니까요. 다음 순회 때 실패한 지점부터 다시 시작합니다. Kafka도 `key = boardId`라 **같은 글의 이벤트는 같은 줄(파티션)** 에 서서 순서가 지켜집니다.

### ③ 최소 한 번 전달 + 멱등 = 중복은 괜찮게 설계
발행은 됐는데 "보냄" 표시 직전에 죽으면? 다음 순회가 **다시 보냅니다**(유실 대신 중복 선택 = at-least-once). 받는 쪽은 **글 id를 문서 id로 덮어쓰기(upsert)/삭제**라 같은 이벤트를 두 번 받아도 결과가 똑같습니다(**멱등**). 그래서 중복을 걱정하지 않아도 됩니다.

### ④ 최종 일관성 = 잠깐 다를 수 있지만 결국 같아진다
글을 쓰고 아주 잠깐(집배원 주기 + ES refresh ~1s)은 검색에 안 나올 수 있습니다. 하지만 **결국 반드시** 반영됩니다. 이걸 최종 일관성(eventual consistency)이라 하고, 검색 같은 기능엔 충분한 보장입니다.

> 만약 이벤트가 유실되거나 인덱스를 새로 만들면? `POST /api/boards/search/reindex`가 DB를 처음부터 훑어 전부 다시 색인합니다(안전망).

**관련 파일**:
- search-service: `db/migration/V1__init.sql`(board_outbox), `adapter/out/persistence/OutboxPersistenceAdapter`, `application/service/RelayOutboxService`, `adapter/in/batch/OutboxRelayScheduler`, `adapter/out/messaging/KafkaEventPublisherAdapter`, `adapter/out/persistence/SpringTransactionRunner`
- event-contract: `demo.search.events/BoardChangedEvent`
- search-indexer: `adapter/in/messaging/BoardChangedListener`, `application/service/BoardIndexService`, `adapter/out/search/ElasticsearchBoardIndexAdapter`

---

# 4️⃣ 용어 사전

| 용어 | 뜻 |
|---|---|
| **Write-Back** | 빠른 저장소(Redis)에 모았다가 느린 저장소(DB)에 나중에 몰아 반영 |
| **HINCRBY** | Redis Hash 필드를 원자적으로 증가 |
| **Atomic(원자적)** | 중간에 끼어들 수 없는 하나의 동작 — 되거나 안 되거나, 반쪽은 없음 |
| **RENAME 스냅샷** | Redis 키를 통째로 옮겨, 반영 대상을 고정하고 새 유입과 분리 |
| **키셋(seek) 페이지네이션** | OFFSET 대신 "마지막 id 다음"으로 다음 페이지 — 깊이 무관 일정 속도 |
| **스트리밍(streaming)** | 전부 메모리에 올리지 않고 조금씩 흘려보내기(여기선 `Flow`가 아니라 키셋 페이지 `List`를 채널로) |
| **Channel / bounded / 백프레셔** | 코루틴 큐 / 용량 제한 / 소비가 밀리면 생산까지 눌림 |
| **청크 커밋** | 한 번에 다 말고 덩어리로 나눠 짧게 커밋 |
| **Transactional Outbox** | 업무 데이터와 **같은 트랜잭션**으로 이벤트를 테이블에 남기고, 릴레이가 브로커로 발행 |
| **Kafka / 토픽 / 파티션** | 이벤트 브로커 / 이벤트 종류별 통로 / 순서가 지켜지는 하위 줄(같은 key는 같은 파티션) |
| **at-least-once** | 최소 한 번 전달 — 유실 대신 중복을 허용 |
| **멱등(idempotent)** | 같은 요청을 여러 번 해도 결과가 같음(upsert/삭제) |
| **최종 일관성** | 잠깐은 다를 수 있어도 결국 같아지는 보장 |

---

# 5️⃣ 세 시스템 핵심 비교

| | ① 조회수 | ② 아카이브 | ③ 검색 색인 분리 |
|---|---|---|---|
| **다루는 문제** | 쓰기 폭주 흡수 | 대량 삭제를 메모리 안전하게 | 두 저장소 정합성(이중쓰기) |
| **데이터 흐름** | Redis 버퍼 → 주기 배치로 DB 반영 | DB 스트림 → 벨트 → 워커 삭제 | DB+아웃박스(원자) → Kafka → ES |
| **동시성/분산** | 단일 순차 플러시(분산 락으로 클러스터 직렬화) | 생산자 1 + 워커 N 팬아웃 | 서비스 분리, 파티션 순서 보존 |
| **안전장치** | commit-then-delete(유실 방지) | 청크 커밋 + skip-and-continue | 원자적 outbox + at-least-once + 멱등 |
| **실패해도** | 다음 플러시가 재시도 | 실패 청크만 건너뜀 | 다음 릴레이가 재발행 / reindex 안전망 |
| **트리거** | @Scheduled(30초) | @Scheduled(cron, opt-in) | @Scheduled 릴레이(opt-in) + @KafkaListener |

> 한 줄 요약: ①은 **버퍼로 흡수**, ②는 **스트림으로 분해**, ③은 **원자적 이벤트로 잇기**. 세 가지 모두 "실패를 가정하고, 유실 대신 재시도"라는 같은 태도로 설계됐습니다.

---

# 🚀 이 조각들, 로컬에서 어떻게 다 띄우나 (k8s 한 눈에)

지금까지 이야기한 게 실제로 돌려면 **여러 프로그램이 동시에** 떠 있어야 합니다: 게시판 앱, 검색 색인 앱, 그리고 그들이 쓰는 DB·Redis·Elasticsearch·Kafka. 예전엔 docker-compose로 한꺼번에 띄웠지만, 지금은 **로컬 쿠버네티스(k8s)** 로 옮겼습니다. 실제 배포 환경과 같은 방식으로 연습하려는 것입니다.

## 아파트 한 동에 세대를 들이는 것과 같다

```
[ 내 Mac ]
   └─ Colima (컨테이너 런타임 = 아파트 부지)
        └─ kind (쿠버네티스 클러스터 = 아파트 한 동)
             ├─ search-service   (게시판 앱)      ← localhost:8080 로 노출
             ├─ search-indexer  (색인 앱)
             ├─ postgres · redis · elasticsearch · kafka   (데이터스토어 = 각 세대)
             └─ (선택) alloy                                (관측성 = 관리사무소, Grafana Cloud로 전달)
```

- **파드(Pod)** = 각 프로그램이 사는 집 한 채. 위 조각 하나하나가 파드입니다.
- **서비스(Service)** = 집 주소(DNS). 앱은 `kafka:9092`, `postgres:5432`처럼 **이름으로** 서로를 찾습니다(IP를 몰라도 됨).
- **Helm** = 이 모든 세대의 입주 계획서(차트). 명령 하나로 전부 배치합니다.

## 명령 하나로

```bash
./deploy/up.sh          # 코어 6개(앱 2 + DB/Redis/ES/Kafka)만 — 가볍게
./deploy/up.sh --obs    # 관측성 Alloy 1개까지 = 7개 (Alloy가 Grafana Cloud로 전달, 자격증명 env 필요)
```

`up.sh`가 순서대로 해줍니다: **Colima 켜기 → kind 클러스터 만들기 → 앱 이미지 빌드해 클러스터에 넣기 → Helm으로 배포 → 다 뜰 때까지 대기.** 끝나면 `localhost:8080`이 게시판입니다. `--obs`면 관측성은 **Grafana Cloud**에서 조회합니다(로컬 Grafana 없음). 정리는 `./deploy/down.sh`.

## 왜 굳이 k8s로?

docker-compose도 "여러 개를 같이 띄우기"는 하지만, 쿠버네티스는 한 발 더 나아가 **떨어지면 다시 세우고(자가 치유), 준비될 때까지 트래픽을 안 보내고(readiness), 설정을 Secret으로 분리**합니다 — 실제 서비스가 굴러가는 방식 그대로입니다. 그래서 앞의 세 시스템(조회수·아카이브·아웃박스)이 "실패를 가정하고 재시도"하도록 만든 것처럼, **배포도 같은 태도**로 연습하는 셈입니다.

> 실제 명령·헬스 확인·문제 진단(로그 보기 등)은 [`deploy/README.md`](./deploy/README.md)에 정리돼 있습니다.
