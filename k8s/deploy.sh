#!/bin/bash
# K8s 전체 배포 스크립트
# 사용법: bash k8s/deploy.sh
#
# 사전 조건:
#   1. Docker Desktop Kubernetes 활성화
#   2. k8s/secrets.yaml 준비 (secrets.yaml.template 참고)
#   3. 이미지 빌드 완료 (bash k8s/build-images.sh)

set -e
SCRIPT_DIR="$(dirname "$0")"

echo "=== [1/6] Namespace ==="
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"

echo "=== [2/6] Secrets ==="
if [ ! -f "$SCRIPT_DIR/secrets.yaml" ]; then
  echo "ERROR: k8s/secrets.yaml 파일이 없습니다."
  echo "  cp k8s/secrets.yaml.template k8s/secrets.yaml 후 실제 값을 입력하세요."
  exit 1
fi
kubectl apply -f "$SCRIPT_DIR/secrets.yaml"

echo "=== [3/6] ConfigMap ==="
kubectl apply -f "$SCRIPT_DIR/configmap.yaml"

echo "=== [4/6] 인프라 (Zookeeper → Kafka → PostgreSQL) ==="
kubectl apply -f "$SCRIPT_DIR/infra/kafka.yaml"
kubectl apply -f "$SCRIPT_DIR/infra/postgres.yaml"

echo "PostgreSQL 준비 대기 (최대 60초)..."
kubectl wait --for=condition=ready pod -l app=postgres -n axiom --timeout=60s

echo "=== [5/6] 백엔드 서비스 ==="
kubectl apply -f "$SCRIPT_DIR/market-service.yaml"
kubectl apply -f "$SCRIPT_DIR/order-service.yaml"
kubectl apply -f "$SCRIPT_DIR/portfolio-service.yaml"
kubectl apply -f "$SCRIPT_DIR/strategy-service.yaml"
kubectl apply -f "$SCRIPT_DIR/api-gateway.yaml"

echo "=== [6/7] 프론트엔드 ==="
kubectl apply -f "$SCRIPT_DIR/frontend.yaml"

echo "=== [7/7] Pod Watcher ==="
kubectl apply -f "$SCRIPT_DIR/pod-watcher/rbac.yaml"
kubectl apply -f "$SCRIPT_DIR/pod-watcher/deployment.yaml"

echo ""
echo "=== 배포 완료 ==="
echo "Pod 상태 확인: kubectl get pods -n axiom"
echo "접속:"
echo "  프론트엔드:  http://localhost"
echo "  API Gateway: http://localhost:8080"
