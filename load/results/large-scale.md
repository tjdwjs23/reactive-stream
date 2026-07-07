# 부하 테스트 결과 — 대용량 키셋 페이지네이션 "깊이 무관성"

## 무엇을 증명하려는가

목록 조회의 두 방식은 **깊은 페이지(뒤로 갈수록)** 에서 성능이 갈린다.

- **OFFSET 방식** (`LIMIT n OFFSET k`): DB가 건너뛸 `k`개 행을 **매번 스캔하고 버린다**. 깊이 `k`에
  비례해 지연이 선형으로 증가한다 → 뒤 페이지일수록 느려진다(대용량에서 치명적).
- **키셋 방식** (`WHERE id < :cursor ORDER BY id DESC LIMIT n`): PK 인덱스를 커서 위치로 **시크**한 뒤
  `n`개만 읽는다. **몇 페이지째냐와 무관하게** 비용이 일정하다.

이 프로젝트의 `getBoards`는 키셋 방식이다(`BoardService.getBoards` → `BoardRepositoryPort.findPage`).
아래 실측은 **커서 깊이를 얕은/중간/깊은 지점으로 뿌려도 p95가 평탄함**을 보여, 그 설계 이점을 정량화한다.

## 테스트 개요

- **대상**: `GET /api/boards?cursor=&size=` (키셋 페이지네이션, R2DBC 논블로킹 단일 SELECT)
- **시나리오**: `load/scenarios/pagination.js` — 커서를 전체 id 범위에 걸쳐 3개 깊이 버킷으로 균등 분산
  후 depth 태그별 지연을 비교
- **데이터 규모**: `board` 테이블 **151,174행** (id 1 ~ 151,174)
- **깊이 버킷** (frac = OFFSET 방식이 도달하려면 건너뛰었어야 할 비율):
  - `shallow` frac 0.0 → **OFFSET ~0 등가** (첫 페이지)
  - `mid` frac 0.5 → **OFFSET ~75,600 등가**
  - `deep` frac 0.95 → **OFFSET ~143,600 등가** (사실상 마지막 페이지)
- **부하**: k6 `ramping-arrival-rate`, 피크 **3,000 RPS**, 워밍업10s+램프10s+지속40s+램프다운5s
- **환경**: 로컬 단일 머신(macOS) — 앱 + PostgreSQL + Redis + ES + k6가 **같은 CPU 공유**
- **측정일**: 2026-07-06 (2회 반복, 값 동일 수준 — 아래는 대표 실행)

## 결과 — 깊이별 지연 (핵심)

| 커서 깊이 | OFFSET 등가 | med | **p95** | p99 | max | 판정 |
|---|---:|---:|---:|---:|---:|:--:|
| `shallow` (첫 페이지) | ~0 | 2.02ms | **3.35ms** | 9.54ms | 23.0ms | ✅ |
| `mid` (중간) | ~75,600 | 2.01ms | **3.33ms** | 9.60ms | 26.4ms | ✅ |
| `deep` (마지막 근처) | ~143,600 | 2.01ms | **3.34ms** | 9.72ms | 29.0ms | ✅ |

- **총 요청 152,250건, 에러율 0%, 드롭(dropped_iterations) 0** — 3,000 RPS를 완전히 소화.
- **p95가 세 깊이에서 3.33~3.35ms로 사실상 동일** (편차 0.02ms). p99도 9.5~9.7ms로 평탄.

> OFFSET 방식이었다면 `deep`(≈14.3만 행 스킵)은 `shallow`보다 **수십~수백 배** 느려졌어야 한다.
> 실측은 깊이가 최댓값(마지막 페이지)이어도 첫 페이지와 지연이 구분되지 않는다 → **깊이 무관성 확인**.

## 이 결과가 증명하는 설계 강점

1. **키셋 페이지네이션의 깊이 무관 O(1) 시크** — 14.3만 행 뒤의 페이지도 첫 페이지와 동일한 p95(3.3ms).
   대용량 목록에서 "뒤 페이지가 느려지는" OFFSET의 고질병이 구조적으로 없다.
2. **논블로킹 스택의 고동시성** — 3,000 RPS·15만 요청을 에러 0%·드롭 0으로 처리. 요청당 스레드를
   쓰지 않는 WebFlux+코루틴+R2DBC라 커넥션 소수로 소화한다(믹스 스윕 결과의 커넥션 10개 근거와 동일 맥락).
3. **좁은 트랜잭션 경계** — 목록은 트랜잭션 없는 단일 SELECT(오토커밋). 읽기 경로에 불필요한 트랜잭션
   오버헤드가 없다(`BoardService.getBoards` 주석 참고).

## 재현 방법

전제: 인프라 + 앱이 떠 있어야 한다(로컬 k8s는 `./deploy/up.sh`로 전체 기동, `board-service`는 `localhost:8080`). 빠른 개발 실행은 데이터스토어를 `kubectl port-forward`로 당긴 뒤 `./gradlew :board-service:bootRun`. 그리고 `board`에 대용량 데이터가 시드돼 있어야 한다.

```bash
# 대용량 데이터가 없다면(권장 10만+): mixed.js를 높은 쓰기 비중으로 잠깐 돌려 채우거나 별도 시드.
# 커서 상한(idMax)은 스크립트가 목록 API로 자동 탐지한다(ID_MAX로 직접 지정도 가능).
PEAK_RATE=3000 k6 run \
  --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" \
  --summary-export load/results/raw/pagination-3000.json \
  load/scenarios/pagination.js

# 깊이 버킷별 p95 뽑기
for d in shallow mid deep; do
  jq -r --arg d "$d" '.metrics["http_req_duration{depth:"+$d+"}"] | "\($d): p95=\(."p(95)")ms"' \
    load/results/raw/pagination-3000.json
done
```

`DEEP_FRAC`로 deep 버킷의 깊이를, `PAGE_SIZE`로 페이지 크기를, `ID_MIN`/`ID_MAX`로 커서 범위를 조절한다.

## 한계와 해석 (정직하게)

- **로컬 단일 머신 보정**: k6·앱·PostgreSQL이 같은 CPU를 나눠 쓴다. 절대 수치(3.3ms)는 분리 배포에서
  달라질 수 있으나, **이 테스트의 핵심 주장은 절대값이 아니라 "깊이 간 상대 차이가 없다"** 는 것이고
  그 결론은 환경과 무관하게 성립한다.
- **OFFSET과의 직접 A/B는 미포함**: 이 API에는 OFFSET 엔드포인트가 없어(키셋만 구현) 나란히 비교하지
  않았다. 대신 "키셋의 깊이 평탄함"을 단독으로 증명한다. OFFSET의 선형 열화는 잘 알려진 성질이다.

---

## (참고) 배치 아카이브 삭제 처리량

오래된 게시글 대량 삭제(`ArchiveStaleBoardsService`)는 **바운드 Channel 백프레셔 + 청크별 개별 커밋 +
부분 실패 skip-continue** 구조다(서비스 코드·`ArchiveStaleBoardsServiceTest` 참조). 처리량은 이 리포의
평가 기록 기준 **약 10.4만 건 삭제 ≈ 1.26s (~8.3만 rows/s), 실패 0** 로 측정된 바 있다(단일 머신).

> ⚠️ **출처 주의**: 위 배치 수치는 *이전 실행(프로젝트 평가 노트)* 기록이며, 이번 세션에서 재실측한 값이
> **아니다**. 위 **키셋 깊이무관성 표는 2026-07-06 이번 세션에서 151,174행에 대해 직접 실측한 값**이다.

**재실측 방법**: 온디맨드 아카이브 트리거 `POST /api/admin/boards/archive`(ROLE_ADMIN)가 있다. 오래된
데이터를 시드한 뒤(예: `createdAt`이 과거인 행), 아래처럼 호출하고 응답의 `result.deleted`와 소요 시간을
집계한다(주의: **실제로 삭제되므로** 시험용 데이터에만 사용).

```bash
TOKEN=... # admin 로그인 토큰
time curl -s -X POST http://localhost:8080/api/admin/boards/archive \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"retentionDays":30,"chunkSize":1000,"concurrency":8}'
# → {"result":{"scanned":..., "deleted":..., "failedChunks":0}} + 벽시계 소요 시간
```

`retentionDays`는 필수(누락 시 400 — 실수로 대량 삭제 방지), `chunkSize`/`concurrency`는 생략 시
커맨드 기본값(500/4)을 쓴다.
