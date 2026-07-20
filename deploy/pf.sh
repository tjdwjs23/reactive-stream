#!/usr/bin/env bash
# 로컬에서 자주 보는 것들을 한 번에 port-forward 합니다.
#
# 포그라운드(터미널 점유, Ctrl+C로 종료):
#   ./deploy/pf.sh
#
# 백그라운드(터미널을 닫아도 유지, 별도 stop으로 종료):
#   ./deploy/pf.sh start     # nohup으로 띄우고 PID 저장 후 바로 반환
#   ./deploy/pf.sh status    # 실행 여부 확인
#   ./deploy/pf.sh stop      # 백그라운드 인스턴스 종료(모든 port-forward 정리)
#
# 열어두는 대상: search-service(Swagger/API) + Postgres(DBeaver 등).
# 관측성은 Grafana Cloud로 전송하므로 로컬에 Grafana가 없습니다 — 대시보드/로그/트레이스는 Grafana Cloud에서 조회합니다.
#
# Postgres 로컬 포트는 PG_LOCAL_PORT로 바꿀 수 있습니다(로컬에 이미 Postgres가 5432를 쓸 때):
#   PG_LOCAL_PORT=5433 ./deploy/pf.sh          → DBeaver는 localhost:5433
#   PG_LOCAL_PORT=5433 ./deploy/pf.sh start
set -uo pipefail

PG_LOCAL_PORT="${PG_LOCAL_PORT:-5432}"
PIDFILE="${PF_PIDFILE:-${TMPDIR:-/tmp}/search-platform-pf.pid}"
LOGFILE="${PF_LOGFILE:-${TMPDIR:-/tmp}/search-platform-pf.log}"

pids=()
cleanup() {
  echo
  echo "port-forward 종료..."
  for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done
}

pf() { # svc  localPort:targetPort  label  url
  kubectl port-forward "svc/$1" "$2" >/dev/null 2>&1 &
  pids+=($!)
  printf "  %-16s %s\n" "$3" "$4"
}

# 실제 port-forward들을 띄우고 대기합니다. 포그라운드·백그라운드가 공유하는 본체.
run_forwards() {
  trap cleanup EXIT INT TERM
  echo "port-forward 시작:"
  pf search-service 8080:8080 "search-service" "http://localhost:8080/swagger-ui.html"
  pf postgres "${PG_LOCAL_PORT}:5432" "postgres" "localhost:${PG_LOCAL_PORT}  (db=search user=search pw=search1234)"

  # 관측성은 Grafana Cloud로 전송합니다 — 로컬 Grafana가 없으므로 port-forward 대상이 아닙니다.
  # 필요할 때만: search-indexer actuator (기본은 안 엶)
  # pf search-indexer 8081:8081 "search-indexer" "http://localhost:8081/actuator/health"

  echo
  wait
}

running_pid() { # 살아있는 백그라운드 PID를 출력(없으면 아무것도 안 함)
  [[ -f "$PIDFILE" ]] || return 1
  local pid
  pid="$(cat "$PIDFILE" 2>/dev/null)"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && { echo "$pid"; return 0; }
  return 1
}

case "${1:-fg}" in
  start)
    if pid="$(running_pid)"; then
      echo "이미 백그라운드 실행 중 (pid=$pid). 종료하려면: ./deploy/pf.sh stop"
      exit 0
    fi
    # 자기 자신을 내부 실행 모드(__run)로 nohup 백그라운드 기동. 로그는 LOGFILE로.
    PG_LOCAL_PORT="$PG_LOCAL_PORT" nohup "$0" __run >"$LOGFILE" 2>&1 &
    echo $! >"$PIDFILE"
    sleep 1
    echo "백그라운드로 port-forward 시작 (pid=$(cat "$PIDFILE")):"
    echo "  search-service   http://localhost:8080/swagger-ui.html"
    echo "  postgres        localhost:${PG_LOCAL_PORT}  (db=search user=search pw=search1234)"
    echo
    echo "  로그:  tail -f $LOGFILE"
    echo "  종료:  ./deploy/pf.sh stop"
    ;;
  stop)
    if pid="$(running_pid)"; then
      kill "$pid" 2>/dev/null || true   # EXIT 트랩이 kubectl 자식들을 정리
      rm -f "$PIDFILE"
      echo "백그라운드 port-forward 종료 (pid=$pid)."
    else
      echo "실행 중인 백그라운드 port-forward가 없습니다."
      rm -f "$PIDFILE"
    fi
    ;;
  status)
    if pid="$(running_pid)"; then
      echo "실행 중 (pid=$pid). 로그: $LOGFILE"
    else
      echo "실행 중 아님."
    fi
    ;;
  __run)
    # nohup으로 백그라운드 기동될 때만 진입 — 실제 본체 실행
    run_forwards
    ;;
  fg|"")
    echo "(포그라운드 모드 — Ctrl+C로 전부 종료. 백그라운드는 ./deploy/pf.sh start)"
    run_forwards
    ;;
  *)
    echo "사용법: $0 [start|stop|status]   (인자 없으면 포그라운드)"
    exit 1
    ;;
esac
