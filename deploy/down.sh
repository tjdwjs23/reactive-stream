#!/usr/bin/env bash
# board-platform 정리.
#   ./deploy/down.sh          # helm uninstall만 — postgres PVC(DB 데이터)와 kind 클러스터는 유지.
#                             #   다음 ./deploy/up.sh에서 기존 데이터에 그대로 재연결됩니다.
#   ./deploy/down.sh --all    # 위 + kind 클러스터 삭제 + colima 정지 (⚠ DB 데이터도 함께 소멸).
set -euo pipefail

CLUSTER="board-platform"
HERE="$(cd "$(dirname "$0")" && pwd)"

ALL=false
if [[ "${1:-}" == "--all" ]]; then ALL=true; fi

echo "==> 백그라운드 port-forward 정리"
"${HERE}/pf.sh" stop || true

echo "==> helm 릴리스 삭제 (postgres PVC는 helm.sh/resource-policy: keep 로 보존)"
helm uninstall board-platform >/dev/null 2>&1 || echo "    (릴리스 없음 — skip)"

if $ALL; then
  echo "==> kind 클러스터 삭제 (⚠ PVC/DB 데이터 함께 소멸)"
  kind delete cluster --name "${CLUSTER}" 2>/dev/null || echo "    (클러스터 없음 — skip)"
  echo "==> colima 정지"
  colima stop || true
  echo "정리 완료 — 전체 삭제(데이터 초기화됨)."
else
  echo "정리 완료 — DB 데이터(postgres PVC)와 kind 클러스터는 유지됩니다."
  echo "  다시 시작:  ./deploy/up.sh        (postgres가 기존 PVC에 재연결 → 데이터 복구)"
  echo "  완전 삭제:  ./deploy/down.sh --all (⚠ 데이터까지 삭제)"
fi
