# 로컬 k8s 배포 (Colima + kind + Helm)

이 디렉터리는 **board-platform**을 Mac 로컬 쿠버네티스에서 실행하기 위한 것입니다. 클라우드(EKS 등)는 쓰지 않습니다.

```
deploy/
├── kind/kind-config.yaml          단일 노드 kind 클러스터(호스트 8080/8081 매핑)
├── helm/board-platform/           Helm 차트 (앱 2 + 데이터스토어 4)
├── build-and-load.sh              이미지 3종 빌드 + kind 주입
└── README.md                      이 문서(런북)
```

구성 요소: `board-service`, `search-indexer` + `PostgreSQL` · `Redis` · `Elasticsearch(Nori)` · `Kafka(KRaft)`.
관측성(LGTM)은 기본 제외 — 앱의 OTLP export는 꺼진 채 뜹니다(`observability.enabled=false`).

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
kubectl cluster-info --context kind-board-platform
```

## 3) 이미지 빌드 + 클러스터 주입

kind는 로컬 이미지를 pull하지 못하므로 **빌드 후 load**해야 합니다(스크립트가 3종을 한 번에 처리).

```bash
./deploy/build-and-load.sh
```
> 내부적으로 `board-service:local`, `search-indexer:local`, `reactive-elasticsearch-nori:9.2.2`를 빌드하고 `kind load`합니다.
> 앱 이미지는 멀티스테이지 Gradle 빌드라 최초엔 의존성 다운로드로 시간이 걸립니다.

## 4) Helm 배포

```bash
helm upgrade --install board-platform deploy/helm/board-platform
kubectl get pods -w
```
기동 순서: postgres/redis/es/kafka가 Ready → `board-service`(initContainer가 postgres 대기 후) → `search-indexer`. 전부 `Running`/`READY 1/1`이 될 때까지 1~2분 걸릴 수 있습니다(ES가 가장 느림).

## 5) 동작 확인 (end-to-end)

board-service는 kind 포트 매핑으로 **호스트 `localhost:8080`** 에서 열립니다.

```bash
# 헬스
curl -s localhost:8080/actuator/health

# 가입 → 로그인(JWT)
curl -s -XPOST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'
TOKEN=$(curl -s -XPOST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}' | sed -E 's/.*"result":"?([^",}]+).*/\1/')

# 게시글 생성 → (아웃박스 → Kafka → search-indexer → ES) → 검색
curl -s -XPOST localhost:8080/api/boards -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"카카오메일 공지","content":"이벤트 기반 색인 파이프라인 검증입니다."}'
sleep 3   # 릴레이 폴링 + ES refresh 대기
curl -s 'localhost:8080/api/boards/search?keyword=메일'
```

색인 반영이 안 보이면 소비자 로그를 확인:
```bash
kubectl logs -l app=search-indexer -f
kubectl logs -l app=board-service -f      # 릴레이 발행 로그
```

## 유용한 명령

```bash
kubectl get pods,svc                                   # 전체 상태
kubectl describe pod -l app=board-service              # 스케줄/프로브 이슈 진단
kubectl port-forward svc/search-indexer 8081:8081      # 컨슈머 actuator (또는 localhost:8081)
helm status board-platform
helm get values board-platform
```

## 설정 바꾸기 (values)

```bash
# 예: 관측성 켜서 클러스터 내 Alloy로 push
helm upgrade board-platform deploy/helm/board-platform \
  --set observability.enabled=true --set observability.otlpEndpointBase=http://alloy:4318

# 예: 특정 데이터스토어를 외부 것으로 대체(차트에서 제외)
helm upgrade board-platform deploy/helm/board-platform --set kafka.enabled=false
```
주요 값: `boardService.image`, `searchIndexer.image`, `*.resources`, `postgres.password`, `security.jwtSecret`, `security.adminPassword`, `observability.enabled`. 전체는 `helm/board-platform/values.yaml` 참고.

## 정리(teardown)

```bash
helm uninstall board-platform
kind delete cluster --name board-platform
colima stop
```

---

### 참고/한계
- 데이터스토어 볼륨은 `emptyDir`라 **파드 재생성 시 데이터가 초기화**됩니다(로컬 개발 전용). 영속이 필요하면 PVC로 교체하세요.
- 코드 변경 반영: 이미지를 다시 빌드·load한 뒤 `kubectl rollout restart deploy/board-service`(또는 search-indexer). `imagePullPolicy: IfNotPresent`라 태그가 같으면 새로 pull하지 않으므로 **load 후 rollout restart**가 필요합니다.
- board-service `bootRun`이 기동 시 `db/schema.sql`을 실행해 스키마를 만듭니다(별도 마이그레이션 잡 불필요).
- 이미지 태그가 `:local`로 고정이라, 갱신 시 `--set boardService.image=board-service:local2`처럼 태그를 바꾸면 rollout이 확실합니다.
