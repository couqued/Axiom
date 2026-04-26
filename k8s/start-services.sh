#!/bin/bash
# K8s 서비스 기동 스크립트 (장 시작 전 배치를 위해 기동)
# 실행 방법: bash k8s/start-services.sh

set -e

echo "=== [1/3] 인프라 기동 (Zookeeper, PostgreSQL) ==="
kubectl scale statefulset/zookeeper statefulset/postgres -n axiom --replicas=1
echo "Zookeeper, PostgreSQL ready 대기 중..."
kubectl wait --for=condition=ready pod -l app=zookeeper -n axiom --timeout=120s
kubectl wait --for=condition=ready pod -l app=postgres  -n axiom --timeout=120s

echo "=== [2/3] Kafka 기동 ==="
kubectl scale statefulset/kafka -n axiom --replicas=1
echo "Kafka ready 대기 중..."
kubectl wait --for=condition=ready pod -l app=kafka -n axiom --timeout=120s

echo "=== [3/3] 백엔드, 프론트엔드, ML, Cloudflare 기동 ==="
kubectl scale deployment/market-service deployment/order-service deployment/portfolio-service \
  deployment/strategy-service deployment/api-gateway deployment/frontend \
  deployment/ml-service deployment/cloudflared \
  -n axiom --replicas=1

echo "모든 서비스가 순서대로 기동되었습니다."
