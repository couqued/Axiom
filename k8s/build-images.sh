#!/bin/bash
# 프로젝트 루트(axiom/)에서 실행
# 사용법: bash k8s/build-images.sh

set -e
cd "$(dirname "$0")/.."

echo "=== Axiom 도커 이미지 빌드 ==="

docker build -f Dockerfile.api-gateway      -t axiom/api-gateway:latest      . && echo "✓ api-gateway"
docker build -f Dockerfile.market-service   -t axiom/market-service:latest   . && echo "✓ market-service"
docker build -f Dockerfile.order-service    -t axiom/order-service:latest    . && echo "✓ order-service"
docker build -f Dockerfile.portfolio-service -t axiom/portfolio-service:latest . && echo "✓ portfolio-service"
docker build -f Dockerfile.strategy-service -t axiom/strategy-service:latest . && echo "✓ strategy-service"
docker build -f Dockerfile.frontend         -t axiom/frontend:latest         . && echo "✓ frontend"
docker build -f k8s/pod-watcher/Dockerfile  -t axiom/pod-watcher:latest       k8s/pod-watcher/ && echo "✓ pod-watcher"

echo ""
echo "=== 빌드 완료 ==="
docker images | grep axiom
