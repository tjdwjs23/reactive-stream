# 로컬 k8s 배포 (Colima + kind + Helm)

이 디렉터리는 **search-platform**을 Mac 로컬 쿠버네티스에서 실행하기 위한 것입니다. 클라우드(EKS 등)는 쓰지 않습니다.

```
deploy/
├── up.sh                          ⭐ 한 번에 전체 기동(colima→kind→build&load→helm→대기). COLIMA_CPU/COLIMA_MEM로 자원 조절
├── obs.sh                         관측성(Alloy→Grafana Cloud)만 켜고/끄기 — `obs.sh on|off` (up.sh 재실행 없이 helm으로 토글)
├── pf.sh                          search-service(Swagger)+Postgres를 한 번에 port-forward
├── down.sh                        정리(helm uninstall — 데이터/클러스터 유지, --all이면 kind 삭제+colima 정지)
├── build-and-load.sh              이미지 3종 빌드 + kind 주입
├── kind/kind-config.yaml          단일 노드 kind 클러스터(호스트 8080/8081 매핑)
├── helm/search-platform/           Helm 차트 (앱 2 + 데이터스토어 4 + 관측성 Alloy 1)
├── grafana-cloud/dashboards/       Grafana Cloud에 import할 대시보드(hexagonal-app.json)
└── README.md                      이 문서(런북)
```

구성 요소: `search-service`, `search-indexer` + `PostgreSQL` · `Redis` · `Elasticsearch(Nori)` · `Kafka(KRaft)`.
관측성은 **Alloy 1파드**(OTLP 수집기)만 클러스터에 두고 `observability.enabled`로 켜고 끕니다. Alloy는 metrics/logs/traces 3종을 **Grafana Cloud** OTLP 게이트웨이로 전달합니다(자체 호스팅 LGTM은 걷어냄). 참고로 레포 루트의 `docker-compose.yml`은 **여전히 존재**하며, kind 없이 데이터스토어 4종(PostgreSQL/Redis/Elasticsearch/Kafka)만 host 포트에 띄워 앱을 host에서 직접 `bootRun`/디버그하는 가벼운 경로용입니다.
기본은 off(로컬 리소스 절약): 앱 OTLP export가 꺼지고 Alloy 파드도 뜨지 않습니다.

### Grafana Cloud 자격증명
Grafana Cloud 포털 → 스택 → **OpenTelemetry** 타일에서 OTLP 엔드포인트·Instance ID·API Token을 발급합니다. `observability.enabled=true`로 켤 때 주입하세요(토큰은 절대 커밋 금지):
```bash
export GRAFANA_CLOUD_OTLP_ENDPOINT="https://otlp-gateway-prod-<region>-0.grafana.net/otlp"
export GRAFANA_CLOUD_INSTANCE_ID="<INSTANCE_ID>"
export GRAFANA_CLOUD_API_TOKEN="<API_TOKEN>"   # up.sh --obs / obs.sh on 이 env를 읽어 helm --set으로 주입(Secret에 저장)
```
### 대시보드 import (Grafana Cloud)
대시보드 정본: **`deploy/grafana-cloud/dashboards/hexagonal-app.json`** (예전 자체 호스팅 시절 대시보드를 그대로 옮겨둠).

1. macOS면 파일 내용을 클립보드로: `pbcopy < deploy/grafana-cloud/dashboards/hexagonal-app.json`
2. Grafana Cloud → **Dashboards → New → Import** → **"Import via dashboard JSON model"** 칸에 붙여넣기 → **Load**
3. Load 화면에서 **데이터소스 3개를 스택 것으로 매핑**(안 하면 패널이 "datasource not found"로 빈다):

   | 대시보드 안 uid | 매핑할 Grafana Cloud 데이터소스 | 패널 수 |
   |---|---|---|
   | `mimir` (Prometheus 타입) | 스택 **Metrics/Prometheus** DS (보통 `grafanacloud-<stack>-prom`) | 102 |
   | `loki` | 스택 **Logs/Loki** DS (`grafanacloud-<stack>-logs`) | 2 |
   | `tempo` | 스택 **Traces/Tempo** DS (`grafanacloud-<stack>-traces`) | 2 |

   > 최신 Grafana는 Load 시 위 3개를 자동 감지해 드롭다운을 띄웁니다. 각각 지정 후 **Import**.

- 상단 `$job` 드롭다운 = `service.name`(`search-service` / `search-indexer`)으로 필터.
- 메트릭 이름은 Grafana Cloud가 OTLP→Prometheus 접미사(`_total`/`_seconds_bucket`)를 붙여줘 기존 쿼리(`board_search_total`, `http_server_requests_seconds_bucket` 등)가 그대로 맞습니다. 단 **컨슈머 랙 패널**(`kafka_consumer_*`)은 배포 환경 실측 메트릭명에 따라 안 뜰 수 있습니다(알려진 한계).
- HikariCP 커넥션 풀 패널은 stale `r2dbc_pool_*` → `hikaricp_connections_*`로 수정돼 있습니다(현 스택은 JDBC+HikariCP).
- **exemplar**: Explore(Prometheus)에서 히스토그램(`http_server_requests_seconds_bucket`) 쿼리 → 우측 **Exemplars 토글 ON** → 그래프 위 점 hover → `trace_id`로 트레이스 점프.

---

## ⚡ 한 번에 실행 (권장)

`colima` · `kind` · `helm`을 설치했다면 스크립트 하나로 끝납니다(멱등 — 이미 뜬 건 재사용).

```bash
brew install colima kind helm      # 최초 1회

./deploy/up.sh                     # 코어(앱 + PostgreSQL/Redis/Elasticsearch/Kafka)
#   또는 (관측성 Alloy까지 — Grafana Cloud 자격증명 env를 함께 넘김)
GRAFANA_CLOUD_OTLP_ENDPOINT=... GRAFANA_CLOUD_INSTANCE_ID=... GRAFANA_CLOUD_API_TOKEN=... ./deploy/up.sh --obs

# 나중에 관측성만 추가/제거 (up.sh 재실행·이미지 재빌드 없이 helm으로 토글):
GRAFANA_CLOUD_OTLP_ENDPOINT=... GRAFANA_CLOUD_INSTANCE_ID=... GRAFANA_CLOUD_API_TOKEN=... ./deploy/obs.sh on   # Alloy 추가 + 앱 OTLP on
./deploy/obs.sh off                # 제거

# 접근(colima에선 직결 localhost가 불안정 → port-forward 권장):
./deploy/pf.sh                     # search-service(8080)+Postgres(5432) 한 번에, Ctrl+C로 종료
#   → http://localhost:8080/swagger-ui.html  (관측성은 Grafana Cloud에서 조회)
#   → DBeaver: localhost:5432 (db=search user=search pw=search1234); 5432 충돌 시 PG_LOCAL_PORT=5433 ./deploy/pf.sh

# 정리:
./deploy/down.sh                   # helm uninstall만 — DB 데이터·kind 클러스터 유지(다음 up.sh에서 복구)
./deploy/down.sh --all             # + kind 클러스터 삭제 + colima 정지 (⚠ DB 데이터도 삭제)
```

> **접근 방식**: 관측성은 **Grafana Cloud**에서 모두 조회합니다(로그·트레이스·메트릭 + 상관). 로컬 클러스터엔 Alloy(포워더)만 있고 Grafana는 없습니다. 그래서 로컬에서 일상적으로 열어둘 건 **search-service** 하나입니다. Alloy export가 정상인지는 `kubectl logs deploy/alloy`(401/403이면 자격증명 확인)로 봅니다. DB/Redis/ES/Kafka는 필요할 때 `kubectl exec`로 확인하세요.

`up.sh`가 실패하거나 각 단계를 직접 이해하고 싶으면 아래 수동 절차를 따라가세요.

---

## 0) 도구 설치 (최초 1회)

```bash
brew install colima kind helm
# docker CLI가 없다면: brew install docker
```

## 1) Colima 기동 (컨테이너 런타임)

ES + Kafka + 앱 2개를 감당하려면 넉넉한 리소스를 권장합니다.

```bash
colima start --cpu 4 --memory 8
docker context show     # → colima 인지 확인 (아니면: docker context use colima)
```

## 2) kind 클러스터 생성

```bash
kind create cluster --config deploy/kind/kind-config.yaml
kubectl cluster-info --context kind-search-platform
```

## 3) 이미지 빌드 + 클러스터 주입

kind는 로컬 이미지를 pull하지 못하므로 **빌드 후 load**해야 합니다(스크립트가 3종을 한 번에 처리).

```bash
./deploy/build-and-load.sh
```
> 내부적으로 `search-service:local`, `search-indexer:local`, `search-elasticsearch-nori:9.2.2`를 빌드하고 `kind load`합니다.
> 앱 이미지는 멀티스테이지 Gradle 빌드라 최초엔 의존성 다운로드로 시간이 걸립니다.

## 4) Helm 배포

```bash
helm upgrade --install search-platform deploy/helm/search-platform
kubectl get pods -w
```
기동 순서: postgres/redis/es/kafka가 Ready → `search-service`(initContainer가 postgres 대기 후) → `search-indexer`. 전부 `Running`/`READY 1/1`이 될 때까지 1~2분 걸릴 수 있습니다(ES가 가장 느림).

## 5) 동작 확인 (end-to-end)

search-service는 kind 포트 매핑으로 **호스트 `localhost:8080`** 에서 열립니다.

```bash
# 헬스
curl -s localhost:8080/actuator/health

# 가입 → 로그인(JWT)
curl -s -XPOST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'
TOKEN=$(curl -s -XPOST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

# 게시글 생성 → (아웃박스 → Kafka → search-indexer → ES) → 검색
curl -s -XPOST localhost:8080/api/boards -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"카카오메일 공지","content":"이벤트 기반 색인 파이프라인 검증입니다."}'
sleep 3   # 릴레이 폴링 + ES refresh 대기
curl -s 'localhost:8080/api/boards/search?keyword=메일'
```

색인 반영이 안 보이면 소비자 로그를 확인:
```bash
kubectl logs -l app=search-indexer -f
kubectl logs -l app=search-service -f      # 릴레이 발행 로그
```

## 유용한 명령

```bash
kubectl get pods,svc                                   # 전체 상태
kubectl describe pod -l app=search-service              # 스케줄/프로브 이슈 진단
kubectl port-forward svc/search-indexer 8081:8081      # 컨슈머 actuator (또는 localhost:8081)
helm status search-platform
helm get values search-platform
```

## 설정 바꾸기 (values)

```bash
# 예: 관측성 Alloy(포워더)를 클러스터에 배포 + 앱이 클러스터 내 Alloy로 push → Alloy가 Grafana Cloud로 전달
#   Alloy 1파드만 늘어 메모리 증분이 거의 없습니다. 자격증명을 함께 주입하세요(토큰 커밋 금지).
helm upgrade search-platform deploy/helm/search-platform \
  --set observability.enabled=true \
  --set observability.grafanaCloud.otlpEndpoint="https://otlp-gateway-prod-<region>-0.grafana.net/otlp" \
  --set observability.grafanaCloud.instanceId="<INSTANCE_ID>" \
  --set observability.grafanaCloud.apiToken="<API_TOKEN>"

# 예: 특정 데이터스토어를 외부 것으로 대체(차트에서 제외)
helm upgrade search-platform deploy/helm/search-platform --set kafka.enabled=false
```
주요 값: `searchService.image`, `searchIndexer.image`, `*.resources`, `postgres.password`, `security.jwtSecret`, `security.adminPassword`, `observability.enabled`, `observability.grafanaCloud.{otlpEndpoint,instanceId,apiToken}`. 전체는 `helm/search-platform/values.yaml` 참고.

## 정리(teardown)

```bash
# 기본: 앱만 내리고 DB 데이터(postgres PVC)와 kind 클러스터는 유지 → 다음 up.sh에서 데이터 복구
./deploy/down.sh

# 완전 삭제: 데이터까지 초기화
./deploy/down.sh --all         # = helm uninstall + kind delete cluster + colima stop
```

> **DB 데이터가 언제 살아남나**: postgres PVC(`postgres-data`)는 `helm.sh/resource-policy: keep`라
> `down.sh`(기본, helm uninstall)로는 지워지지 않고, 파드 재시작·`rollout restart`·colima 재시작에도 유지됩니다.
> PVC 데이터는 kind 노드(도커 컨테이너) 안에 있으므로 **`down.sh --all`의 `kind delete cluster`로만 소멸**합니다.

---

### 참고/한계
- PostgreSQL은 PVC(`postgres.persistence.enabled=true`, 기본 on)로 **데이터가 유지**됩니다(파드 재생성·`down.sh` 기본에도 보존, `down.sh --all`에서만 소멸). Redis/Elasticsearch/Kafka는 `emptyDir`라 파드 재생성 시 초기화됩니다(검색 인덱스는 `POST /api/boards/search/reindex`로 재구축, 조회수 버퍼는 휘발성).
- 코드 변경 반영: 이미지를 다시 빌드·load한 뒤 `kubectl rollout restart deploy/search-service`(또는 search-indexer). `imagePullPolicy: IfNotPresent`라 태그가 같으면 새로 pull하지 않으므로 **load 후 rollout restart**가 필요합니다.
- 스키마는 search-service 기동 시 **Flyway**가 `db/migration/V*.sql`을 적용해 만듭니다(`flyway_schema_history`로 이력 추적, 파드/부트런 어디서 뜨든 자동, 별도 마이그레이션 잡 불필요). JPA는 `ddl-auto=validate`라 매핑 불일치만 감지합니다.
- 이미지 태그가 `:local`로 고정이라, 갱신 시 `--set searchService.image=search-service:local2`처럼 태그를 바꾸면 rollout이 확실합니다.
