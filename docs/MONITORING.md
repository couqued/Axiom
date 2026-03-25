# K8s Pod 상태 모니터링 — Slack 알림

## As-Is vs To-Be

### AS-IS (Docker Compose 기반)

| 대상 | 알림 방식 | 시작 | 종료 |
|------|---------|------|------|
| Spring Boot 5개 서비스 | 각 서비스 내 `ServiceLifecycleNotifier` | `ApplicationReadyEvent` | `ContextClosedEvent` (SIGTERM) |
| Frontend (Vite dev) | `vite.config.js` `slackLifecyclePlugin` | `httpServer.listening` | `SIGINT` / `SIGTERM` |
| **Kafka** | ❌ 알림 없음 | — | — |
| **PostgreSQL** | ❌ 알림 없음 | — | — |
| **Zookeeper** | ❌ 알림 없음 | — | — |

**한계:** 인프라 Pod(Kafka, DB, Zookeeper)의 상태를 모니터링할 수단이 없었음.

---

### TO-BE (Kubernetes 기반)

| 대상 | 알림 방식 |
|------|---------|
| **전체 9개 Pod** | `pod-watcher` Pod — K8s Watch API 구독 |

`pod-watcher`는 `axiom` 네임스페이스의 모든 Pod 이벤트를 실시간 감시합니다.
Spring Boot 서비스의 `ServiceLifecycleNotifier`는 로컬 개발 환경 호환성을 위해 유지됩니다.

---

## 알림 목록

| Pod | app 레이블 | Slack 표시명 | 시작 알림 | 종료 알림 |
|-----|-----------|------------|---------|---------|
| `zookeeper-0` | `app=zookeeper` | *Zookeeper* | 🟢 시작 | 🔴 종료 |
| `kafka-0` | `app=kafka` | *Kafka* | 🟢 시작 | 🔴 종료 |
| `postgres-0` | `app=postgres` | *PostgreSQL* | 🟢 시작 | 🔴 종료 |
| `market-service-xxx` | `app=market-service` | *Market Service* | 🟢 시작 | 🔴 종료 |
| `order-service-xxx` | `app=order-service` | *Order Service* | 🟢 시작 | 🔴 종료 |
| `portfolio-service-xxx` | `app=portfolio-service` | *Portfolio Service* | 🟢 시작 | 🔴 종료 |
| `strategy-service-xxx` | `app=strategy-service` | *Strategy Service* | 🟢 시작 | 🔴 종료 |
| `api-gateway-xxx` | `app=api-gateway` | *API Gateway* | 🟢 시작 | 🔴 종료 |
| `frontend-xxx` | `app=frontend` | *Frontend* | 🟢 시작 | 🔴 종료 |

> `pod-watcher` 자체는 감시 대상에서 제외됩니다 (`app == 'pod-watcher'` 스킵).

---

## 알림 발송 조건

### 시작 알림 🟢
- 이벤트 타입: `ADDED` 또는 `MODIFIED`
- 조건: Pod의 `conditions` 중 `type=Ready, status=True`로 **전환** (`False → True`)
- Ready 전환이 없는 이벤트(예: MODIFIED이지만 여전히 NotReady)는 알림 미발송

### 종료 알림 🔴
- 이벤트 타입: `DELETED`
- 조건: 마지막으로 알려진 상태가 Ready였던 Pod만 알림 발송
- Pending/CrashLoopBackOff 등 Ready 미달 상태에서 삭제된 경우 알림 미발송 (노이즈 방지)

### 상태 추적 방식

```python
ready_state: dict[str, bool] = {}  # pod_name → 마지막 Ready 상태

# MODIFIED 이벤트
prev = ready_state.get(pod_name, False)
curr = is_ready(pod)           # conditions[Ready].status == "True"
ready_state[pod_name] = curr

if not prev and curr:           # False → True 전환 시만 시작 알림
    slack(f'🟢 *{label}* 시작 ({now()})')

# DELETED 이벤트
was_ready = ready_state.pop(pod_name, False)
if was_ready:                   # Ready였던 Pod만 종료 알림
    slack(f'🔴 *{label}* 종료 ({now()})')
```

---

## pod-watcher 아키텍처

### 파일 구조

```
k8s/pod-watcher/
├── watcher.py        ← 메인 스크립트 (Watch API 구독 + 상태 추적 + Slack 발송)
├── requirements.txt  ← kubernetes==29.0.0, requests==2.31.0
├── Dockerfile        ← python:3.12-slim 기반, 의존성 설치
├── rbac.yaml         ← ServiceAccount + Role + RoleBinding
└── deployment.yaml   ← Deployment (replicas=1)
```

### 동작 흐름

```
pod-watcher Pod 기동
  │
  ├─ signal.signal(SIGTERM, _shutdown)  ← 종료 신호 처리 (exit code 0)
  ├─ config.load_incluster_config()     ← K8s 클러스터 내부 인증
  └─ watch.Watch().stream(list_namespaced_pod, ns="axiom")
        │
        │  ← K8s가 초기 목록(ADDED) 스트림 전송 후 실시간 변경 이벤트 구독
        │
        ├─ ADDED/MODIFIED 이벤트
        │    └─ is_ready() 체크 → 전환 감지 → Slack 전송
        │
        └─ DELETED 이벤트
             └─ was_ready() 체크 → Slack 전송
```

### Slack Webhook 주입

`axiom-secrets` K8s Secret에서 환경변수로 주입받습니다. 별도 설정 없이 Spring Boot 서비스와 동일한 Slack 채널로 알림이 발송됩니다.

```yaml
env:
  - name: SLACK_WEBHOOK_URL
    valueFrom:
      secretKeyRef:
        name: axiom-secrets
        key: SLACK_WEBHOOK_URL
```

### SIGTERM 처리

K8s Rolling Update 시 구 Pod에 SIGTERM이 전달됩니다. 핸들러 없이 종료되면 exit code 143 → K8s가 `Error` 상태로 표시됩니다. `_shutdown()`에서 `sys.exit(0)`으로 정상 종료(exit code 0) → `Completed`로 표시됩니다.

```python
def _shutdown(signum, frame):
    print('[pod-watcher] 종료 신호 수신, 정상 종료', flush=True)
    sys.exit(0)

signal.signal(signal.SIGTERM, _shutdown)
signal.signal(signal.SIGINT,  _shutdown)
```

---

## RBAC (역할 기반 접근 제어)

K8s API 서버에 접근하려면 Pod가 적절한 권한을 가진 ServiceAccount로 실행되어야 합니다.

### 구성 요소 (`rbac.yaml`)

```
ServiceAccount (pod-watcher)
    │
    └─ RoleBinding (pod-watcher-binding)
           │
           └─ Role (pod-reader)
                  │
                  └─ rules:
                       apiGroups: [""]
                       resources: ["pods"]
                       verbs: ["get", "list", "watch"]
```

| 리소스 | 허용 동작 | 이유 |
|--------|---------|------|
| `pods` | `get` | 개별 Pod 정보 조회 |
| `pods` | `list` | 초기 전체 목록 조회 (Watch 시작 전 초기화) |
| `pods` | `watch` | 실시간 이벤트 스트림 구독 |

**최소 권한 원칙 적용:** Pod 읽기 전용 권한만 부여. `ClusterRole` 대신 `Role`을 사용해 `axiom` 네임스페이스 내부로 범위를 제한합니다.

### 인증 흐름

```
pod-watcher Pod 기동
  └─ ServiceAccount: pod-watcher
       └─ config.load_incluster_config()
            ├─ /var/run/secrets/kubernetes.io/serviceaccount/token  (JWT 토큰)
            └─ /var/run/secrets/kubernetes.io/serviceaccount/ca.crt (클러스터 CA)
                 └─ K8s API 서버 인증 → pods watch 허용
```

K8s가 ServiceAccount 토큰을 자동으로 Pod에 마운트하므로 별도 자격증명 관리가 불필요합니다.

---

## 운영 명령어

```bash
# 로그 실시간 확인
kubectl logs -n axiom -l app=pod-watcher -f

# pod-watcher 재시작 (코드 변경 후 이미지 재빌드 시)
docker build -f k8s/pod-watcher/Dockerfile -t axiom/pod-watcher:latest k8s/pod-watcher/
kubectl rollout restart deployment/pod-watcher -n axiom

# 롤아웃 상태 확인
kubectl rollout status deployment/pod-watcher -n axiom

# RBAC 확인
kubectl get serviceaccount pod-watcher -n axiom
kubectl get rolebinding pod-watcher-binding -n axiom
```
