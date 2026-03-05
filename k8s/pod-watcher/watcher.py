import os
import signal
import sys
import time
from datetime import datetime, timezone, timedelta

import requests
from kubernetes import client, config, watch

WEBHOOK = os.environ['SLACK_WEBHOOK_URL']
NAMESPACE = 'axiom'

FRIENDLY = {
    'zookeeper':         'Zookeeper',
    'kafka':             'Kafka',
    'postgres':          'PostgreSQL',
    'market-service':    'Market Service',
    'order-service':     'Order Service',
    'portfolio-service': 'Portfolio Service',
    'strategy-service':  'Strategy Service',
    'api-gateway':       'API Gateway',
    'frontend':          'Frontend',
}


def slack(text):
    try:
        requests.post(WEBHOOK, json={'text': text}, timeout=5)
    except Exception:
        pass


def app_label(pod):
    return (pod.metadata.labels or {}).get('app', pod.metadata.name)


def is_ready(pod):
    conditions = pod.status.conditions or []
    return any(c.type == 'Ready' and c.status == 'True' for c in conditions)


KST = timezone(timedelta(hours=9))

def now():
    return datetime.now(KST).strftime('%H:%M:%S')


_running = True

def _shutdown(signum, frame):
    global _running
    print('[pod-watcher] 종료 신호 수신, 정상 종료', flush=True)
    _running = False
    sys.exit(0)


def main():
    signal.signal(signal.SIGTERM, _shutdown)
    signal.signal(signal.SIGINT, _shutdown)

    config.load_incluster_config()
    v1 = client.CoreV1Api()

    # pod_name → bool (마지막으로 알려진 Ready 상태)
    ready_state: dict[str, bool] = {}

    print('[pod-watcher] axiom 네임스페이스 감시 시작', flush=True)

    while _running:
        try:
            w = watch.Watch()
            for event in w.stream(v1.list_namespaced_pod, namespace=NAMESPACE):
                etype = event['type']   # ADDED / MODIFIED / DELETED
                pod   = event['object']
                name  = pod.metadata.name
                app   = app_label(pod)
                label = FRIENDLY.get(app, app)

                if app == 'pod-watcher':
                    continue

                if etype == 'DELETED':
                    was_ready = ready_state.pop(name, False)
                    if was_ready:
                        msg = f'🔴 *{label}* 종료  ({now()})'
                        print(msg, flush=True)
                        slack(msg)
                    continue

                curr = is_ready(pod)

                if etype == 'ADDED':
                    # 재연결 시 기존 Pod 상태를 조용히 초기화 (알림 없음)
                    ready_state[name] = curr
                    continue

                # MODIFIED: 실제 상태 변화만 알림
                prev = ready_state.get(name, False)
                ready_state[name] = curr

                if not prev and curr:
                    msg = f'🟢 *{label}* 시작  ({now()})'
                    print(msg, flush=True)
                    slack(msg)

        except Exception as e:
            print(f'[pod-watcher] Watch 스트림 종료: {e} — 5초 후 재연결', flush=True)
            time.sleep(5)


if __name__ == '__main__':
    main()
