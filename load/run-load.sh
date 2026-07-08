#!/usr/bin/env bash
# 로컬 k8s(kind) 부하테스트를 "클러스터 내부"에서 실행하는 러너.
#
# 왜 in-cluster인가: k6를 호스트에서 NodePort(localhost:8080)로 때리면 ~1,000 동시 커넥션에서
# colima의 호스트↔VM 포트매핑(docker-proxy)이 무너져 앱·kube-API가 동시에 죽습니다(측정 불가).
# k6를 클러스터 안 Job으로 돌려 ClusterIP(search-service:8080)를 직접 호출하면 이 경계를 우회합니다.
#
# 사용법:
#   ./load/run-load.sh <scenario> [k6 옵션 env...]
#   scenario: mixed | pagination | smoke | signals   (기본 mixed)
#
# 예)
#   PEAK_RATE=3000 ./load/run-load.sh mixed
#   PEAK_RATE=2000 ID_MAX=148000 ./load/run-load.sh pagination
#   PEAK_RATE=4000 SUSTAIN_DUR=1m K6_MEM=4Gi ./load/run-load.sh mixed
#
# 사전 준비: colima에 코어 넉넉히(예: COLIMA_CPU=10 COLIMA_MEM=24 ./deploy/up.sh) + 앱 배포됨.
set -euo pipefail

SCENARIO="${1:-mixed}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOAD="${ROOT}/load"
NS="${NS:-default}"
JOB="k6-load"

case "$SCENARIO" in
  mixed|pagination|smoke|signals) ;;
  *) echo "알 수 없는 시나리오: $SCENARIO (mixed|pagination|smoke|signals)"; exit 1 ;;
esac

command -v kubectl >/dev/null || { echo "kubectl 없음"; exit 1; }
kubectl get svc search-service -n "$NS" >/dev/null 2>&1 || { echo "search-service 서비스 없음 — 먼저 배포하세요(./deploy/up.sh)"; exit 1; }

echo "==> k6 스크립트 ConfigMap 갱신"
kubectl delete configmap k6-scripts -n "$NS" >/dev/null 2>&1 || true
kubectl create configmap k6-scripts -n "$NS" \
  --from-file=mixed.js="${LOAD}/scenarios/mixed.js" \
  --from-file=pagination.js="${LOAD}/scenarios/pagination.js" \
  --from-file=smoke.js="${LOAD}/scenarios/smoke.js" \
  --from-file=signals.js="${LOAD}/scenarios/signals.js" \
  --from-file=config.js="${LOAD}/lib/config.js" \
  --from-file=helpers.js="${LOAD}/lib/helpers.js" >/dev/null

# 호스트에 설정된 k6 튜닝 env를 Job으로 전달(설정된 것만).
ENV_YAML=""
# 값이 설정된 env만 Job에 추가. (set -e 하에서도 항상 0을 반환하도록 if/fi 사용)
add_env() {
  if [ -n "${2:-}" ]; then
    ENV_YAML="${ENV_YAML}
            - { name: ${1}, value: \"${2}\" }"
  fi
}
add_env BASE_URL "http://search-service:8080"          # ClusterIP 직접(호스트 브리지 우회)
add_env ADMIN_PASSWORD "${ADMIN_PASSWORD:-admin1234}"
add_env PEAK_RATE "${PEAK_RATE:-}"
add_env READ_PCT "${READ_PCT:-}";  add_env WRITE_PCT "${WRITE_PCT:-}";  add_env HOT_COUNT "${HOT_COUNT:-}"
add_env ID_MAX "${ID_MAX:-}";      add_env ID_MIN "${ID_MIN:-}";        add_env PAGE_SIZE "${PAGE_SIZE:-}"
add_env DEEP_FRAC "${DEEP_FRAC:-}"
add_env WARMUP_DUR "${WARMUP_DUR:-}"; add_env RAMP_DUR "${RAMP_DUR:-}"
add_env SUSTAIN_DUR "${SUSTAIN_DUR:-}"; add_env RAMPDOWN_DUR "${RAMPDOWN_DUR:-}"

K6_CPU="${K6_CPU:-3}"
K6_MEM="${K6_MEM:-3Gi}"   # 고RPS(VU 수천)에선 k6 자신이 OOM날 수 있어 넉넉히

echo "==> k6 Job 실행: scenario=${SCENARIO} PEAK_RATE=${PEAK_RATE:-<script default>} (k6 cpu=${K6_CPU} mem=${K6_MEM})"
kubectl delete job "$JOB" -n "$NS" >/dev/null 2>&1 || true
sleep 2
cat <<EOF | kubectl apply -n "$NS" -f - >/dev/null
apiVersion: batch/v1
kind: Job
metadata:
  name: ${JOB}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          imagePullPolicy: IfNotPresent
          command: ["k6","run","--summary-trend-stats","avg,p(95),p(99),max","/scripts/scenarios/${SCENARIO}.js"]
          env:${ENV_YAML}
          resources:
            requests: { cpu: "1", memory: "512Mi" }
            limits: { cpu: "${K6_CPU}", memory: "${K6_MEM}" }
          volumeMounts:
            - { name: scripts, mountPath: /scripts }
      volumes:
        - name: scripts
          configMap:
            name: k6-scripts
            items:
              - { key: mixed.js,      path: scenarios/mixed.js }
              - { key: pagination.js, path: scenarios/pagination.js }
              - { key: smoke.js,      path: scenarios/smoke.js }
              - { key: signals.js,    path: scenarios/signals.js }
              - { key: config.js,     path: lib/config.js }
              - { key: helpers.js,    path: lib/helpers.js }
EOF

echo "==> 로그 스트리밍 (Ctrl+C해도 Job은 계속 돎; 결과는 아래에)"
# 파드가 뜰 때까지 잠깐 대기 후 로그 팔로우
kubectl wait --for=condition=ready pod -l job-name="$JOB" -n "$NS" --timeout=120s >/dev/null 2>&1 || true
kubectl logs -f job/"$JOB" -n "$NS" 2>/dev/null || true

echo ""
echo "==> Job 상태: $(kubectl get job "$JOB" -n "$NS" -o jsonpath='{.status.conditions[0].type}' 2>/dev/null)"
echo "    (정리: kubectl delete job ${JOB}; kubectl delete configmap k6-scripts)"
