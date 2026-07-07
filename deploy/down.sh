#!/usr/bin/env bash
# board-platform 정리. 기본은 helm 릴리스 삭제 + kind 클러스터 삭제.
#   ./deploy/down.sh          # helm uninstall + kind 클러스터 삭제
#   ./deploy/down.sh --all    # 위 + colima 정지
set -euo pipefail

CLUSTER="board-platform"

echo "==> helm 릴리스 삭제"
helm uninstall board-platform >/dev/null 2>&1 || echo "    (릴리스 없음 — skip)"

echo "==> kind 클러스터 삭제"
kind delete cluster --name "${CLUSTER}" 2>/dev/null || echo "    (클러스터 없음 — skip)"

if [[ "${1:-}" == "--all" ]]; then
  echo "==> colima 정지"
  colima stop || true
fi

echo "정리 완료."
