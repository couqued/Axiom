#!/bin/bash
# K8s 서비스 종료 스크립트 (장이 끝난 후 리소스 절약)
# 실행 방법: bash k8s/stop-services.sh

set -e

echo "=== [1/2] 백엔드 및 프론트엔드 서비스 종료 ==="
kubectl scale deployment/market-service deployment/order-service deployment/portfolio-service deployment/strategy-service deployment/api-gateway deployment/frontend -n axiom --replicas=0

echo "=== [2/2] 인프라 종료 (Kafka → Zookeeper, PostgreSQL 순) ==="
kubectl scale statefulset/kafka -n axiom --replicas=0
kubectl scale statefulset/zookeeper statefulset/postgres -n axiom --replicas=0

echo "모든 서비스가 종료되었습니다. Docker Desktop을 종료해도 됩니다."
