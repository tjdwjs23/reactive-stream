#!/usr/bin/env bash
# 이미지 3종(앱 2종 + ES/Nori)을 빌드해 kind 클러스터에 주입합니다(로컬 이미지라 pull 불가 → kind load 필수).
#   ./deploy/build-and-load.sh
# 환경변수 CLUSTER로 클러스터 이름 변경 가능(기본 search-platform).
set -euo pipefail

CLUSTER="${CLUSTER:-search-platform}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> [1/4] building search-service:local (context = repo root)"
docker build -f search-service/Dockerfile -t search-service:local .

echo "==> [2/4] building search-indexer:local"
docker build -f search-indexer/Dockerfile -t search-indexer:local .

echo "==> [3/4] building search-elasticsearch-nori:9.2.2 (Nori 플러그인 포함 ES)"
docker build -t search-elasticsearch-nori:9.2.2 docker/elasticsearch

echo "==> [4/4] loading images into kind cluster '${CLUSTER}'"
kind load docker-image \
  search-service:local \
  search-indexer:local \
  search-elasticsearch-nori:9.2.2 \
  --name "${CLUSTER}"

echo "done. 이제: helm upgrade --install search-platform deploy/helm/search-platform"
