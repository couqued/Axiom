# Changelog

모든 주요 변경사항을 기록합니다.
날짜 형식: YYYY-MM-DD

---

## [Unreleased]

---

## [0.6.0] - 2026-03-06

### Added

**UI/UX Phase 1~3 — 운영 모니터링 최적화**

- **대시보드**: 보유 종목 카드에 현재가·평가손익·수익률·트레일링 스탑 기준가·남은 금액/% · 타임컷 잔여 거래일 표시
- **매매내역**: 테이블 → 카드형 레이아웃으로 전환. 전략명·청산사유·시장상태 태그 추가
- **전략 탭**: 보유 포지션 상세 카드 (투자금·경과일·스탑/타임컷 정보). "투자 스킵 종목" 섹션 추가 (최근 7일, 날짜별 그룹)
- **탭 축소**: 5개 → 3개 (대시보드 / 매매내역 / 전략). StockSearch·OrderForm 제거

**order-service**

- `trade_orders` 테이블에 `strategy_name`, `market_state`, `close_reason` 컬럼 추가
- `skipped_signals` 테이블 신규 생성 — 투자 스킵 종목 이력 DB 영구 저장 (일자별 upsert)
- `POST /api/orders/skipped`, `GET /api/orders/skipped?days=N` 엔드포인트 추가

**strategy-service**

- `GET /api/strategy/admin/trailing-stop-status` 엔드포인트 추가 — 보유 종목별 고점·스탑가 반환
- `GET /api/strategy/admin/time-cut-status` 엔드포인트 추가 — 보유 종목별 매수일·경과·잔여 거래일 반환
- `close_reason` 자동 설정: `SIGNAL` / `TRAILING_STOP` / `TIME_CUT` / `FORCE_EXIT`
- 스킵 신호 3종 자동 기록: `BUDGET_INSUFFICIENT` / `MAX_POSITIONS` / `MARKET_WARN`
- Pod 재시작 후 트레일링 스탑·타임컷 인메모리 데이터 자동 복구 (`@PostConstruct`)

### Fixed

- **KIS 종목명 오류**: `bstp_kor_isnm`(업종명) → `hts_kor_isnm`(HTS 종목명)으로 수정 — "일반서비스", "금융" 등으로 잘못 표시되던 문제 해결
- **Slack 중복 알림**: 신호 발송(`sendSignal`) + 결과 발송(`sendOrderFilled`) 이중 발송 → `sendTradeResult` 단일 메시지로 통합
- **Slack 오류 알림 과다**: 투자금 부족(정상 동작)을 오류로 Slack 발송하던 문제 제거
- **트레일링 스탑/타임컷 UI 미표시**: Pod 재시작 시 인메모리 Map 초기화로 데이터 소실 → `@PostConstruct` 복구 로직 추가

### Changed

- KIS API 현재가 조회에 재시도 로직 추가 (fixedDelay 1회, 1초)
- 전략 실행 시 종목 간 200ms 딜레이 추가 (KIS API Rate Limit 대응)
- **트레일링 스탑 체크 주기 단축**: `StrategyEngine` 내 5분 주기 → `TrailingStopScheduler` 분리로 **1분 주기** 추가 (보유 종목만 별도 체크, StrategyEngine 내 기존 체크 유지)

---

## [0.5.0] - 2026-02 (이전)

- MSA 구조 확립 (5개 Spring Boot 서비스 + React PWA)
- Kubernetes 기반 배포 (Docker Desktop 내장 K8s)
- 하이브리드 자동매매 전략 구현 (상승장/횡보장 시장 필터)
  - 상승장: 변동성 돌파, 골든크로스
  - 횡보장: RSI + 볼린저밴드
- 트레일링 스탑 (고점 -7%), 타임컷 (3거래일) 리스크 관리
- 08:30 시장 판별, 09:05~15:20 5분 주기 전략 실행, 15:20 강제청산
- Slack Webhook 알림 연동
- KIS 모의투자 API 연동 (paper mode)
- Kafka 이벤트 기반 포트폴리오 자동 갱신
