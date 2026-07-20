# 부하 테스트 (k6)

이 프로젝트의 부하 테스트는 **Gradle 테스트 코드가 아니라 독립적인 k6 스크립트**입니다.
`./gradlew test`(Konsist 아키텍처 + Kover 92% 커버리지 게이트)와 완전히 분리되어 있고,
이미 떠 있는 서버에 HTTP로 부하를 주는 **블랙박스** 방식이라 앱 코드에 어떤 결합도 만들지 않습니다.

## 왜 k6인가

- 대상 서버와 분리된 단독 바이너리 → `k6 run <script>.js` 한 방
- Go 엔진이라 단일 노드에서 고동시성(수천 VU) 부하가 가볍다
- `thresholds`로 p95 지연·에러율 합격 기준을 스크립트에 박아두면 = 그대로 CI 게이트
- 관측성 Alloy를 함께 띄우면(`./deploy/up.sh --obs`, Grafana Cloud 자격증명 필요) 부하 중 지표를 Grafana Cloud에서 볼 수 있다(아래 참고)

## 디렉토리 구성 (최소 세트)

```
load/
├── lib/
│   ├── config.js      # BASE_URL, 로드/admin 자격, 공용 threshold/authHeaders  (mixed·smoke가 import)
│   └── helpers.js     # 랜덤 한글 게시글 payload, 생성 API, 시드 유틸  (mixed·smoke가 import)
├── scenarios/
│   ├── smoke.js       # 최소 경로 점검(부하 X) — 본 부하 전 항상 먼저
│   ├── mixed.js       # ★ 메인: 읽기7:쓰기2:검색1 종합 트래픽 믹스
│   ├── pagination.js  # ★ 키셋 페이지네이션 깊이무관성(shallow/mid/deep) 증명
│   └── signals.js     # 관측성 데모(정상+에러+포화 스파이크)
├── run-load.sh        # ★ 로컬 k8s 고부하용 — k6를 클러스터 안 Job으로 실행(호스트 브리지 우회)
├── results/
│   ├── mixed-sweep.md            # 종합 믹스 부하 스윕 결과(포트폴리오 자산)
│   ├── large-scale.md            # 대용량 키셋 깊이무관성 실측 결과
│   └── k8s-load-2026-07-07.md    # k8s(Helm) 실측 + in-cluster 측정·환경 병목 규명
└── README.md
```

> `mixed.js`·`pagination.js`는 단독 실행이 아니라 `lib/config.js`·`lib/helpers.js`를 import합니다.

> **로컬 k8s(kind)에서 고부하를 줄 거면 아래 [로컬 k8s 고부하] 섹션의 `run-load.sh`를 쓰세요.**
> 호스트에서 `k6 run ... localhost:8080`으로 직접 때리면 ~1,000 동시 커넥션에서 colima의 호스트↔VM
> 포트매핑(docker-proxy)이 무너져 앱·kube-API가 함께 죽습니다(측정 불가). 저부하/스모크는 호스트 실행으로 충분.

---

# 로컬 k8s 고부하 — `run-load.sh` (권장 경로)

로컬 kind에서 **높은 RPS**로 부하를 줄 땐 호스트에서 `k6 run localhost:8080`을 쓰면 안 됩니다.
k6가 여는 수백~수천 동시 커넥션이 kind NodePort의 **호스트↔VM 포트매핑(docker-proxy)** 한계(~1,000
커넥션)에 걸려, 앱 NodePort와 kube-API 매핑이 **동시에 무너집니다**(요청이 즉시 실패하고 `kubectl`도 끊김).
이건 앱 문제가 아니라 로컬 VM 경계의 측정 아티팩트입니다(실 EKS엔 이 경계가 없음).

**해결: k6를 클러스터 안 Job으로 띄워 ClusterIP(`search-service:8080`)를 직접 호출** — 호스트↔VM 경계를
아예 안 건넙니다. `run-load.sh`가 이 과정(스크립트 ConfigMap 생성 → k6 Job 실행 → 로그 스트리밍)을 자동화합니다.

## 준비 — 코어 넉넉히 클러스터 기동

부하 생성기(k6)도 같은 VM에서 도니 colima에 코어를 넉넉히 줍니다(맥 물리 코어보다 작게).

```bash
COLIMA_CPU=10 COLIMA_MEM=24 ./deploy/up.sh          # 예: 14코어 맥에서 10코어 할당
# 이미 떠 있으면: colima stop && colima start --cpu 10 --memory 24  (postgres PVC 데이터는 유지)
```

## 실행

```bash
PEAK_RATE=3000 ./load/run-load.sh mixed                       # 종합 믹스 3000 RPS
PEAK_RATE=2000 ID_MAX=148000 ./load/run-load.sh pagination     # 키셋 깊이무관성
PEAK_RATE=4000 SUSTAIN_DUR=1m K6_MEM=4Gi ./load/run-load.sh mixed   # 고RPS는 k6 메모리↑(OOM 방지)
./load/run-load.sh smoke                                        # 경로 점검
```

- 1번째 인자 = 시나리오(`mixed`|`pagination`|`smoke`|`signals`, 기본 `mixed`).
- 호스트에 설정한 env(`PEAK_RATE`, `READ_PCT`, `HOT_COUNT`, `ID_MAX`, `WARMUP_DUR`/`RAMP_DUR`/`SUSTAIN_DUR`/
  `RAMPDOWN_DUR` 등)를 그대로 Job에 전달합니다(아래 [주요 환경변수] 표와 동일).
- `BASE_URL`은 자동으로 `http://search-service:8080`(ClusterIP)로 고정됩니다.
- k6 Job 자원: `K6_CPU`(기본 3), `K6_MEM`(기본 3Gi) — VU 수천의 고RPS에선 k6 자신이 OOM날 수 있어 `K6_MEM=4Gi` 등으로 올리세요.
- 결과는 k6 요약이 로그로 스트리밍됩니다. 도중에 Ctrl+C해도 Job은 계속 돌며, `kubectl logs job/k6-load`로 다시 볼 수 있습니다.
- 정리: `kubectl delete job k6-load; kubectl delete configmap k6-scripts`.

> ⚠️ **램프를 너무 짧게 주지 마세요.** `RAMP_DUR`을 몇 초로 두고 높은 `PEAK_RATE`를 주면 스파이크 충격으로
> 실제보다 일찍 붕괴한 것처럼 보입니다(warm-up·커넥션 확보 시간 부족). knee 측정은 `RAMP_DUR`을 10s+로.

**실측 참고**(colima 10코어, in-cluster, replicas=1): mixed 2000·3000 RPS 정상(p95 4~9ms), 4000 붕괴 →
knee ≈ 3,000~4,000 RPS. 호스트 NodePort 경로(knee 1,500)의 약 2배. 자세한 건
[`results/k8s-load-2026-07-07.md`](results/k8s-load-2026-07-07.md).

---

# 혼자 부하 테스트하는 법 (호스트에서 직접 — 저부하/개발용)

> 아래는 **호스트에서 `k6 run`** 하는 방식입니다. 저부하·스모크·개발엔 간편하지만, 로컬 k8s에서 고부하를
> 줄 땐 위 `run-load.sh`를 쓰세요(호스트↔VM 브리지 한계). BASE_URL을 실 서버로 바꾸면 그대로 원격 부하도 됩니다.

## 0. 한 번만 — 설치

```bash
brew install k6
```

## 1. 매번 — 대상 띄우기

부하는 "이미 떠 있는 서버"에 줍니다(BASE_URL 기본 `http://localhost:8080`). 서버를 먼저 올립니다.

- **로컬 k8s(권장)**: `deploy/README.md`대로 Helm 배포 후 `search-service`가 `localhost:8080`에 열립니다(kind 포트 매핑).
- **빠른 개발 실행**: 인프라(PostgreSQL/Redis/Elasticsearch/Kafka)를 `kubectl port-forward`로 당긴 뒤 `./gradlew :search-service:bootRun`.

```bash
# 예: 로컬 k8s에 배포돼 있으면 그대로 localhost:8080 사용. 앱이 떴는지 확인:
curl -s localhost:8080/actuator/health
```

앱이 떴는지 확인:
```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health   # 200 이면 OK
```

## 2. 스모크 — 경로가 다 사는지 먼저 확인 (부하 아님)

```bash
k6 run load/scenarios/smoke.js
```
`checks ✓ 100%` 가 나오면 생성/조회/목록/검색 경로가 정상 → 본 부하로 진행.

## 3. 본 부하 — 종합 트래픽 믹스

가장 단순한 실행(기본 피크 1000 RPS):
```bash
k6 run load/scenarios/mixed.js
```

강도·비율을 바꿀 때는 **스크립트를 고치지 말고 환경변수**로 줍니다:
```bash
PEAK_RATE=2000 k6 run load/scenarios/mixed.js            # 피크 2000 RPS
PEAK_RATE=4000 HOT_COUNT=50 k6 run load/scenarios/mixed.js
READ_PCT=80 WRITE_PCT=15 k6 run load/scenarios/mixed.js  # 읽기80:쓰기15:검색5
BASE_URL=http://다른서버:8080 k6 run load/scenarios/mixed.js
```

### 주요 환경변수

| 변수 | 기본값 | 의미 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 대상 서버 |
| `LOAD_USERNAME`/`LOAD_PASSWORD` | `loadtester`/`loadtest-password` | 쓰기 부하용 사용자(setup에서 가입·로그인해 Bearer 토큰 확보) |
| `ADMIN_USERNAME`/`ADMIN_PASSWORD` | `admin`/(빈 값) | teardown 플러시용 관리자 자격(서버 `board.security.admin.*`와 일치해야 함). 빈 값이면 플러시 생략 |
| `PEAK_RATE` | `1000` | 피크 목표 RPS |
| `HOT_COUNT` | `30` | 인기 글 시드 수(읽기·검색 대상) |
| `READ_PCT`/`WRITE_PCT` | `70`/`20` | 읽기/쓰기 비중(%), 나머지는 검색 |
| `WARMUP_DUR`/`RAMP_DUR`/`SUSTAIN_DUR`/`RAMPDOWN_DUR` | `30s`/`1m`/`3m`/`30s` | 각 단계 지속 시간 |
| `PRE_VUS` | `PEAK_RATE`에 비례 | 미리 확보할 VU 수(높은 RPS일수록 크게) |

## 4. 결과 값을 어떻게 읽나

`k6 run`은 **끝나면 터미널에 요약을 항상 출력**합니다. 볼 지표는 이 4개면 충분합니다:

```
http_reqs..............: 142550  3302/s          ← 처리량(RPS)
http_req_failed........: 0.00%                    ← 에러율 (낮을수록 좋음)
http_req_duration......: ... p(95)=5.09ms p(99)=10.47ms   ← 지연(꼬리 지연이 핵심)
checks.................: 100.00%                  ← 기대 응답(201/200) 비율
dropped_iterations.....: 0                        ← 0이 아니면 목표 RPS를 못 따라간 것 = 포화 신호
```

- **p95/p99 지연**: 평균(avg)이 아니라 **p99(상위 1% 꼬리 지연)** 를 봅니다. 사용자 체감은 꼬리가 결정.
- **dropped_iterations**: `0`이면 목표 RPS를 완전히 소화. `0`보다 크면 그 부하를 못 버틴 것 → **포화점(knee)**.
- **thresholds**: `lib/config.js`의 기준(에러<1%, p95<300ms, check>99%)을 넘으면 k6가 **종료 코드 99**로
  실패 처리 → CI 게이트로 그대로 쓸 수 있음.

## 5. 포트폴리오용 "결과표" 만들기 — 부하 스윕

한 번 실행이 아니라 **부하를 계단식으로 올려 어디서 꺾이는지(knee)** 를 표로 뽑는 게 성과로 가장 강합니다.
`SUSTAIN_DUR`을 짧게 두고 `PEAK_RATE`만 바꿔 반복 실행 → 각 실행의 요약을 JSON으로 저장합니다.

```bash
mkdir -p load/results/raw
export WARMUP_DUR=5s RAMP_DUR=5s SUSTAIN_DUR=30s RAMPDOWN_DUR=3s HOT_COUNT=50

for R in 500 1000 2000 4000 8000; do
  PEAK_RATE=$R k6 run \
    --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" \
    --summary-export "load/results/raw/level-$R.json" \
    load/scenarios/mixed.js
done
```

각 `level-*.json`에서 표에 넣을 값 뽑기(jq):

```bash
for R in 500 1000 2000 4000 8000; do
  jq -r --arg R "$R" '.metrics as $m |
    "\($R) RPS | 완료 \($m.http_reqs.count) | 드롭 \(($m.dropped_iterations.count)//0) | " +
    "에러 \((($m.http_req_failed.value)//0)*100)% | " +
    "p95 \($m.http_req_duration."p(95)")ms | p99 \($m.http_req_duration."p(99)")ms"' \
    load/results/raw/level-$R.json
done
```

정리된 예시 결과와 해석은 [`results/mixed-sweep.md`](results/mixed-sweep.md) 참고.

## 6. 부하 중 앱 내부 상태 보기 (선택) — Grafana Cloud

부하가 도는 동안 **앱 관점 지표**(HikariCP 커넥션 풀, HTTP 지연, JVM 스레드)를 실시간으로 봅니다.
앱이 메트릭을 OTLP로 Alloy → Grafana Cloud에 push하므로(스크레이프 아님), **Grafana Cloud**의
"Hexagonal Search API" 대시보드(`deploy/grafana-cloud/dashboards/hexagonal-app.json` import)나 Explore에서 확인합니다.

빠르게 커맨드라인으로 몇 개만 보고 싶으면:
```bash
curl -s localhost:8080/actuator/metrics/hikaricp.connections.active | jq '.measurements'   # 사용 중 커넥션
curl -s localhost:8080/actuator/metrics/jvm.threads.live    | jq '.measurements'   # 살아있는 스레드
```

> k6 자체 지표(요청 지연/RPS)를 Grafana 그래프로도 보고 싶으면 Mimir의 remote-write 엔드포인트로 보냅니다:
> `K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9009/api/v1/push k6 run -o experimental-prometheus-rw ...`
> → Grafana 공식 k6 대시보드(ID **19665**) import.
> (그냥 터미널 요약만으로도 충분하니 필수는 아님.)

## 정합성 점검 팁

- **조회수(Redis)**: `mixed.js`는 teardown에서 `POST /api/admin/view-counts/flush`를 쳐서 누적 델타를
  DB로 write-back합니다. 부하 후 게시글 `viewCount`가 실제 조회 횟수와 맞는지 확인하세요.
- **검색 색인(ES)**: 색인은 아웃박스→Kafka→search-indexer 경로의 **비동기 최종 일관성**이라, 릴레이/컨슈머
  지연이나 DLQ 격리로 색인이 원본보다 뒤처질 수 있습니다. 부하 후
  `POST /api/boards/search/reindex`(admin 토큰)로 DB→ES 전체 재색인하면 정합성을 회복합니다.
- ES refresh 간격(~1s) 탓에 **색인 직후 검색엔 지연**이 있습니다(smoke가 검색 전 대기하는 이유).
