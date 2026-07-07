#!/usr/bin/env bash
# 관측성(LGTM: Alloy/Mimir/Loki/Tempo/Grafana)만 켜고/끄기.
# up.sh 전체 재실행(이미지 재빌드 포함)이 필요 없습니다 — 이미 떠 있는 클러스터에 helm upgrade로
# LGTM 5파드만 추가/제거하고, 앱의 OTLP export env도 함께 토글합니다(그래서 앱 파드는 잠깐 롤아웃됨).
#
#   ./deploy/obs.sh on    # LGTM 추가 + 앱 OTLP on
#   ./deploy/obs.sh off   # LGTM 제거 + 앱 OTLP off
#
# 주의: on이면 파드가 5개 늘어 메모리가 더 듭니다(colima 12G+ 권장). 최초 on은 LGTM 이미지 pull로 수 분.
set -euo pipefail

ACTION="${1:-}"
case "$ACTION" in
  on)  VAL=true ;;
  off) VAL=false ;;
  *)   echo "사용법: ./deploy/obs.sh on|off"; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE="board-platform"
kubectl config use-context "kind-${RELEASE}" >/dev/null 2>&1 || true

if ! helm status "$RELEASE" >/dev/null 2>&1; then
  echo "helm 릴리스 '${RELEASE}'가 없습니다 — 먼저 ./deploy/up.sh 로 배포하세요."; exit 1
fi

echo "==> 관측성 ${ACTION} (helm upgrade, 앱 이미지 재빌드 없음)"
# --reuse-values: 기존에 적용된 값은 그대로 두고 observability.enabled만 바꿉니다.
helm upgrade "$RELEASE" "${ROOT}/deploy/helm/board-platform" --reuse-values --set observability.enabled="${VAL}"

# OTLP env 변경으로 앱이 롤아웃됩니다.
kubectl rollout status deployment/board-service --timeout=180s 2>&1 | tail -1 || true
kubectl rollout status deployment/search-indexer --timeout=180s 2>&1 | tail -1 || true

if [ "$VAL" = "true" ]; then
  echo "==> LGTM 기동 대기 (최초엔 이미지 pull로 수 분 걸릴 수 있음)"
  kubectl rollout status deployment/grafana --timeout=300s 2>&1 | tail -1 || true
  cat <<EOF

관측성 ON. Grafana 접속:
  ./deploy/pf.sh                       # Grafana(3000) 등 port-forward (colima localhost 직결이 불안정할 때 권장)
  → http://localhost:3000  (admin/admin)  "Hexagonal Board API" 대시보드
EOF
else
  echo "관측성 OFF — LGTM 파드 제거됨(앱 OTLP export도 꺼짐)."
fi
