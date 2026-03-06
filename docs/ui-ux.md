# Axiom UI/UX 구조

> 최종 수정: 2026-03-06

## 탭 구조

앱은 하단 네비게이션 3개 탭으로 구성된다.

```
┌──────────────────────────────────┐
│        Axiom Automated Trade  ⚙️ │  ← 헤더 (관리자 패널 버튼)
├──────────────────────────────────┤
│                                  │
│         [탭별 콘텐츠]             │
│                                  │
├──────────────────────────────────┤
│  📊 대시보드  📋 매매내역  ⚡ 전략 │  ← 하단 탭
└──────────────────────────────────┘
```

---

## 대시보드 탭

### 화면 구성

```
잔고 카드
─────────────────────────────────
총 자산       12,450,000원
현금          10,500,000원
주식 평가액    1,950,000원
손익          +50,000원 (+2.6%)
─────────────────────────────────

보유 종목
─────────────────────────────────────────────────
 삼성전자 (005930)
 6주 · 평균 75,000원 · 현재 78,500원
 평가손익 +21,000원  수익률 +4.7% ▲
 트레일링 스탑: 76,260원 │ 3,740원(4.7%) 남음 [5분 주기]
─────────────────────────────────────────────────
 iM금융지주 (139130)
 3주 · 평균 16,500원 · 현재 15,800원
 평가손익 -2,100원  수익률 -4.2% ▼
 타임컷: 2거래일 남음 (경과 1일)
─────────────────────────────────────────────────
```

### 데이터 조회 흐름

```
페이지 로드
    ↓
Promise.all([
  getPortfolio(),              → GET /api/portfolio
  getBalance(),                → GET /api/portfolio/balance
  getTrailingStopStatus(),     → GET /api/strategy/admin/trailing-stop-status
  getTimeCutStatus(),          → GET /api/strategy/admin/time-cut-status
])
    ↓
보유 종목별 현재가 병렬 조회
  getStockPrice(ticker)        → GET /api/market/stocks/{ticker}/price
    ↓
프론트엔드 계산
  remainingAmt = currentPrice - stopPrice
  remainingPct = remainingAmt / currentPrice × 100
  pnl          = (currentPrice - avgPrice) × quantity
  pnlRate      = (currentPrice - avgPrice) / avgPrice × 100
```

---

## 매매내역 탭

### 화면 구성 (카드형)

```
[매수]  2026.03.05  09:12    ● 체결
삼성전자 (005930)
6주 × 75,000원 = 450,000원
전략: 골든크로스  |  시장: 상승장

[매도]  2026.03.07  14:23    ● 체결
삼성전자 (005930)
6주 × 78,500원 = 471,000원
청산: 트레일링 스탑  |  시장: 상승장
```

### 표시 매핑

| 코드 | 한국어 |
|------|--------|
| `golden-cross` | 골든크로스 |
| `rsi-bollinger` | RSI+볼린저 |
| `volatility-breakout` | 변동성 돌파 |
| `SIGNAL` | 전략 신호 |
| `TRAILING_STOP` | 트레일링 스탑 |
| `TIME_CUT` | 타임컷 |
| `FORCE_EXIT` | 강제청산 (15:20) |
| `BULLISH` | 상승장 |
| `SIDEWAYS` | 횡보장 |

### 데이터 조회 흐름

```
페이지 로드
    ↓
getOrders()    → GET /api/orders
    ↓
createdAt 기준 내림차순 정렬하여 카드로 렌더링
```

---

## 전략 탭

### 화면 구성

```
자동매매 전략
─────────────────────────────────
시장 상태 (코스피 MA20 기준)
상승장  [BULLISH]              [갱신]
활성 전략: [변동성 돌파] [골든크로스]
매일 08:30 자동 갱신 · 수동 갱신 시 즉시 반영
─────────────────────────────────

보유 포지션  2 / 3
[━━━━━━━━━━░░░░░]

┌─────────────────────────────────────┐
│ 삼성전자 (005930)                    │
│ 6주 · 평균 75,000원                  │
│ 투자금 450,000원 · 3거래일 경과       │
│ 트레일링 스탑: 73,125원              │
└─────────────────────────────────────┘

전략 제어
  [▶ 전략 즉시 실행]  [Slack 테스트]

투자 스킵 종목  (최근 7일)
─────────────────────────────────
2026-03-06  [상승장]
  코웨이 (021240)    [투자금 부족]  변동성 돌파  75,000원  3회
  SK하이닉스 (000660) [최대종목 초과] 골든크로스  185,000원 12회
─────────────────────────────────

전략 설정
  1회 매수금액   500,000원
  최대 보유 종목  3종목
  트레일링 스탑  고점 -7%
  타임 컷       3거래일
  감시 유니버스  코스피200 + 코스닥150
  실행 주기      5분 (09:05~15:20)
```

### 데이터 조회 흐름

```
페이지 로드
    ↓
Promise.all([
  getMarketState(),            → GET /api/strategy/market-state
  getPortfolio(),              → GET /api/portfolio
  getAdminStatus(),            → GET /api/strategy/admin/status
  getTrailingStopStatus(),     → GET /api/strategy/admin/trailing-stop-status
  getTimeCutStatus(),          → GET /api/strategy/admin/time-cut-status
  getSkippedSignals(7),        → GET /api/orders/skipped?days=7
])

전략 실행 후 → 포지션/TS/TC/스킵 4개 API 재호출하여 갱신
```

---

## 관리자 패널 (⚙️)

헤더 우측 버튼 클릭 시 하단 슬라이드업 모달로 표시.

| 항목 | 설명 |
|------|------|
| 매매 중단/재개 | `POST /api/strategy/admin/pause` / `resume` |
| 1회 매수금액 | PATCH `/api/strategy/admin/config` |
| 최대 보유 종목 수 | 동일 |
| 트레일링 스탑 % | 동일 |
| 타임 컷 거래일 | 동일 |

---

## 서비스별 API 역할 분담

| 서비스 | 담당 API 경로 | 주요 데이터 |
|--------|--------------|------------|
| market-service | `/api/market/**` | 현재가, 캔들, 종목검색 |
| order-service | `/api/orders/**` | 주문이력, 스킵신호 |
| portfolio-service | `/api/portfolio/**` | 보유종목, 잔고 |
| strategy-service | `/api/strategy/**` | 전략실행, 시장상태, TS/TC 현황 |
