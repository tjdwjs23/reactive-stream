#!/usr/bin/env bash
# board-platform 로컬 k8s 전체 부트스트랩: colima → kind → 이미지 빌드/주입 → helm → 롤아웃 대기 → 접속 안내.
# 멱등: colima/클러스터가 이미 있으면 재사용합니다.
#
# 사용법:
#   ./deploy/up.sh            # 코어만(앱 + PostgreSQL/Redis/Elasticsearch/Kafka)
#   ./deploy/up.sh --obs      # 관측성(LGTM: Alloy/Mimir/Loki/Tempo/Grafana)까지 함께
set -euo pipefail

CLUSTER="board-platform"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OBS=false
if [[ "${1:-}" == "--obs" || "${1:-}" == "--observability" ]]; then OBS=true; fi

# colima 자원. 환경변수로 오버라이드 가능(부하테스트 시 크게):
#   COLIMA_CPU=10 COLIMA_MEM=24 ./deploy/up.sh
# 미지정 시 기본(obs면 메모리↑). 맥 물리 코어보다 작게 두고, k6를 호스트에서 돌린다면 코어를 남겨두세요.
COLIMA_CPU="${COLIMA_CPU:-4}"
if $OBS; then COLIMA_MEM="${COLIMA_MEM:-12}"; else COLIMA_MEM="${COLIMA_MEM:-8}"; fi

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
HELM_OBS=""
if $OBS; then HELM_OBS="--set observability.enabled=true"; fi
# shellcheck disable=SC2086
helm upgrade --install board-platform deploy/helm/board-platform ${HELM_OBS}

echo "==> [5/6] 롤아웃 대기 (Elasticsearch 기동으로 최대 수 분 걸릴 수 있음)"
kubectl rollout status deployment/board-service --timeout=600s
kubectl rollout status deployment/search-indexer --timeout=600s

echo "==> [6/6] 완료 — 현재 상태:"
kubectl get pods

cat <<EOF

접속 — colima 네트워크 특성상 직결 localhost가 불안정하므로 port-forward를 권장:
  ./deploy/pf.sh          # board-service(8080, Swagger) $($OBS && echo "+ Grafana(3000)") 를 한 번에 열고 URL 출력 (Ctrl+C로 종료)
  그 뒤 http://localhost:8080/swagger-ui.html $($OBS && echo ", http://localhost:3000 (admin/admin)")

end-to-end 확인 스크립트/컬은 deploy/README.md 참고.
정리: ./deploy/down.sh   (colima까지 끄려면 ./deploy/down.sh --all)
EOF
