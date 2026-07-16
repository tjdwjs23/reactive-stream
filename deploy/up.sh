#!/usr/bin/env bash
# search-platform 로컬 k8s 전체 부트스트랩: colima → kind → 이미지 빌드/주입 → helm → 롤아웃 대기 → 접속 안내.
# 멱등: colima/클러스터가 이미 있으면 재사용합니다.
#
# 사용법:
#   ./deploy/up.sh            # 코어만(앱 + PostgreSQL/Redis/Elasticsearch/Kafka)
#   ./deploy/up.sh --obs      # + 관측성 Alloy 1파드(metrics/logs/traces를 Grafana Cloud로 전달)
#
# --obs로 켤 땐 Grafana Cloud 자격증명을 환경변수로 넘기세요(없으면 Alloy가 인증 실패):
#   GRAFANA_CLOUD_OTLP_ENDPOINT=... GRAFANA_CLOUD_INSTANCE_ID=... GRAFANA_CLOUD_API_TOKEN=... ./deploy/up.sh --obs
set -euo pipefail

CLUSTER="search-platform"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OBS=false
if [[ "${1:-}" == "--obs" || "${1:-}" == "--observability" ]]; then OBS=true; fi

# colima 자원. 환경변수로 오버라이드 가능(부하테스트 시 크게):
#   COLIMA_CPU=10 COLIMA_MEM=24 ./deploy/up.sh
# 맥 물리 코어보다 작게 두고, k6를 호스트에서 돌린다면 코어를 남겨두세요.
# (obs는 이제 Alloy 1파드만 추가 — 자체 호스팅 LGTM을 걷어내 메모리 증분이 거의 없습니다.)
COLIMA_CPU="${COLIMA_CPU:-4}"
COLIMA_MEM="${COLIMA_MEM:-8}"

echo "==> [1/6] Colima (cpu=${COLIMA_CPU}, mem=${COLIMA_MEM}G)"
if colima status >/dev/null 2>&1; then
  echo "    이미 실행 중 — 리소스를 바꾸려면: colima stop && colima start --cpu ${COLIMA_CPU} --memory ${COLIMA_MEM}"
else
  colima start --cpu "${COLIMA_CPU}" --memory "${COLIMA_MEM}"
fi
# kind가 colima의 docker 데몬을 쓰도록 컨텍스트를 맞춥니다.
docker context use colima >/dev/null 2>&1 || true

echo "==> [2/6] kind 클러스터 '${CLUSTER}'"
if kind get clusters 2>/dev/null | grep -qx "${CLUSTER}"; then
  echo "    이미 존재 — 재사용"
else
  kind create cluster --config deploy/kind/kind-config.yaml
fi
kubectl config use-context "kind-${CLUSTER}" >/dev/null

echo "==> [3/6] 이미지 빌드 + kind 주입 (최초엔 Gradle 의존성 다운로드로 수 분)"
CLUSTER="${CLUSTER}" ./deploy/build-and-load.sh

echo "==> [4/6] Helm 배포 (observability=${OBS})"
HELM_OBS=()
if $OBS; then
  HELM_OBS+=(--set observability.enabled=true)
  # 환경변수로 넘어온 Grafana Cloud 자격증명이 있으면 함께 주입(없으면 values 기본 빈 값 → Alloy 인증 실패).
  [ -n "${GRAFANA_CLOUD_OTLP_ENDPOINT:-}" ] && HELM_OBS+=(--set "observability.grafanaCloud.otlpEndpoint=${GRAFANA_CLOUD_OTLP_ENDPOINT}")
  [ -n "${GRAFANA_CLOUD_INSTANCE_ID:-}" ]   && HELM_OBS+=(--set "observability.grafanaCloud.instanceId=${GRAFANA_CLOUD_INSTANCE_ID}")
  [ -n "${GRAFANA_CLOUD_API_TOKEN:-}" ]     && HELM_OBS+=(--set "observability.grafanaCloud.apiToken=${GRAFANA_CLOUD_API_TOKEN}")
fi
helm upgrade --install search-platform deploy/helm/search-platform "${HELM_OBS[@]}"

echo "==> [5/6] 롤아웃 대기 (Elasticsearch 기동으로 최대 수 분 걸릴 수 있음)"
kubectl rollout status deployment/search-service --timeout=600s
kubectl rollout status deployment/search-indexer --timeout=600s

echo "==> [6/6] 완료 — 현재 상태:"
kubectl get pods

cat <<EOF

접속 — colima 네트워크 특성상 직결 localhost가 불안정하므로 port-forward를 권장:
  ./deploy/pf.sh          # search-service(8080, Swagger)를 한 번에 열고 URL 출력 (Ctrl+C로 종료)
  그 뒤 http://localhost:8080/swagger-ui.html$($OBS && echo "
  관측성: Alloy가 Grafana Cloud로 전달 — Grafana Cloud에서 조회(kubectl logs deploy/alloy로 export 확인)")

end-to-end 확인 스크립트/컬은 deploy/README.md 참고.
정리: ./deploy/down.sh   (colima까지 끄려면 ./deploy/down.sh --all)
EOF
