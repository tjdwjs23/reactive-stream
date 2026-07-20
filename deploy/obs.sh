#!/usr/bin/env bash
# 관측성(Alloy → Grafana Cloud)만 켜고/끄기.
# up.sh 전체 재실행(이미지 재빌드 포함)이 필요 없습니다 — 이미 떠 있는 클러스터에 helm upgrade로
# Alloy 1파드만 추가/제거하고, 앱의 OTLP export env도 함께 토글합니다(그래서 앱 파드는 잠깐 롤아웃됨).
# 자체 호스팅 LGTM은 걷어냈고, Alloy가 3종 신호(metrics/logs/traces)를 Grafana Cloud로 전달합니다.
#
#   # 최초 on: Grafana Cloud 자격증명을 환경변수로 넘겨야 합니다(포털 OpenTelemetry 타일에서 발급).
#   GRAFANA_CLOUD_OTLP_ENDPOINT="https://otlp-gateway-prod-<region>-0.grafana.net/otlp" \
#   GRAFANA_CLOUD_INSTANCE_ID="<INSTANCE_ID>" \
#   GRAFANA_CLOUD_API_TOKEN="<API_TOKEN>" \
#   ./deploy/obs.sh on           # Alloy 추가 + 앱 OTLP on
#
#   ./deploy/obs.sh on           # (이미 주입돼 있으면 재실행 시 --reuse-values로 자격증명 유지)
#   ./deploy/obs.sh off          # Alloy 제거 + 앱 OTLP off
set -euo pipefail

ACTION="${1:-}"
case "$ACTION" in
  on)  VAL=true ;;
  off) VAL=false ;;
  *)   echo "사용법: ./deploy/obs.sh on|off"; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE="search-platform"
kubectl config use-context "kind-${RELEASE}" >/dev/null 2>&1 || true

if ! helm status "$RELEASE" >/dev/null 2>&1; then
  echo "helm 릴리스 '${RELEASE}'가 없습니다 — 먼저 ./deploy/up.sh 로 배포하세요."; exit 1
fi

# on일 때 자격증명을 환경변수로 받으면 --set으로 주입합니다(빈 값이면 --reuse-values의 기존 값 유지).
EXTRA_SET=()
if [ "$VAL" = "true" ]; then
  [ -n "${GRAFANA_CLOUD_OTLP_ENDPOINT:-}" ] && EXTRA_SET+=(--set "observability.grafanaCloud.otlpEndpoint=${GRAFANA_CLOUD_OTLP_ENDPOINT}")
  [ -n "${GRAFANA_CLOUD_INSTANCE_ID:-}" ]   && EXTRA_SET+=(--set "observability.grafanaCloud.instanceId=${GRAFANA_CLOUD_INSTANCE_ID}")
  [ -n "${GRAFANA_CLOUD_API_TOKEN:-}" ]     && EXTRA_SET+=(--set "observability.grafanaCloud.apiToken=${GRAFANA_CLOUD_API_TOKEN}")
fi

echo "==> 관측성 ${ACTION} (helm upgrade, 앱 이미지 재빌드 없음)"
# --reuse-values: 기존에 적용된 값은 그대로 두고 observability.enabled(+ 넘긴 자격증명)만 바꿉니다.
helm upgrade "$RELEASE" "${ROOT}/deploy/helm/search-platform" --reuse-values \
  --set observability.enabled="${VAL}" "${EXTRA_SET[@]}"

# OTLP env 변경으로 앱이 롤아웃됩니다.
kubectl rollout status deployment/search-service --timeout=180s 2>&1 | tail -1 || true
kubectl rollout status deployment/search-indexer --timeout=180s 2>&1 | tail -1 || true

if [ "$VAL" = "true" ]; then
  echo "==> Alloy 기동 대기"
  kubectl rollout status deployment/alloy --timeout=180s 2>&1 | tail -1 || true
  cat <<EOF

관측성 ON — Alloy가 metrics/logs/traces를 Grafana Cloud로 전달합니다.
  kubectl logs deploy/alloy            # export 정상 여부 확인(401/403이면 자격증명 확인)
  → Grafana Cloud의 Explore/대시보드에서 조회. 대시보드 import: deploy/grafana-cloud/dashboards/hexagonal-app.json
EOF
else
  echo "관측성 OFF — Alloy 파드 제거됨(앱 OTLP export도 꺼짐)."
fi
