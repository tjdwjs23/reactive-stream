#!/usr/bin/env bash
# 로컬에서 자주 보는 것들을 한 번에 port-forward 합니다. Ctrl+C 하면 전부 정리(백그라운드 잔여 없음).
#   ./deploy/pf.sh
#
# 열어두는 대상: board-service(Swagger/API) + Grafana(대시보드·Loki 로그·Tempo 트레이스).
# Mimir/Loki/Tempo/Alloy는 개별로 열지 않습니다 — 전부 Grafana 안에서 조회합니다.
set -uo pipefail

pids=()
cleanup() {
  echo
  echo "port-forward 종료..."
  for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

pf() { # svc  localPort:targetPort  label  url
  kubectl port-forward "svc/$1" "$2" >/dev/null 2>&1 &
  pids+=($!)
  printf "  %-16s %s\n" "$3" "$4"
}

echo "port-forward 시작 (이 터미널을 열어두세요. Ctrl+C로 전부 종료):"
pf board-service 8080:8080 "board-service" "http://localhost:8080/swagger-ui.html"

# Grafana는 --obs(observability.enabled=true)로 띄웠을 때만 존재합니다.
if kubectl get svc grafana >/dev/null 2>&1; then
  pf grafana 3000:3000 "grafana" "http://localhost:3000  (admin/admin)"
else
  echo "  (grafana 없음 — 관측성을 보려면 ./deploy/up.sh --obs 로 재배포)"
fi

# 필요할 때만: search-indexer actuator (기본은 안 엶)
# pf search-indexer 8081:8081 "search-indexer" "http://localhost:8081/actuator/health"

echo
wait
