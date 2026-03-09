# Axiom 데이터 관리 구조

> 최종 수정: 2026-03-09 (인메모리 상태 DB 영속화 반영)

---

## DB 스키마 구조

PostgreSQL 단일 인스턴스, 스키마로 서비스 격리.

```
axiom (database)
├── orders    스키마 → order-service 소유
│   ├── trade_orders      주문 이력
│   └── skipped_signals   투자 스킵 신호 이력
├── portfolio 스키마 → portfolio-service 소유
│   └── portfolio         보유 포지션
├── market    스키마 → market-service 소유
│   └── daily_candles     일봉 캐시
└── strategy  스키마 → strategy-service 소유
    └── strategy_state    리스크 관리 인메모리 상태 영속화
```

---

## 테이블 상세

### orders.trade_orders

매수/매도 주문 이력 저장.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| ticker | VARCHAR(10) | 종목코드 |
| stock_name | VARCHAR(50) | 종목명 (market-service 캔들 API fallback으로 한글명 보장) |
| order_type | ENUM | BUY / SELL |
| quantity | INT | 수량 |
| price | NUMERIC(15,2) | 단가 |
| total_amount | NUMERIC(15,2) | 총액 |
| status | ENUM | PENDING / FILLED / FAILED |
| kis_order_id | VARCHAR(50) | KIS 주문번호 |
| strategy_name | VARCHAR(50) | 전략명 (`golden-cross` 등) |
| market_state | VARCHAR(20) | 시장상태 (`BULLISH` / `SIDEWAYS`) |
| close_reason | VARCHAR(30) | 청산사유 (아래 표 참고) |
| created_at | TIMESTAMP | 주문 생성 시각 |
| filled_at | TIMESTAMP | 체결 시각 |

**close_reason 값:**

| 값 | 설명 |
|----|------|
| `SIGNAL` | 전략 신호에 의한 매수/매도 |
| `TRAILING_STOP` | 트레일링 스탑 청산 |
| `TIME_CUT` | 타임컷 청산 |
| `FORCE_EXIT` | 15:20 강제청산 (volatility-breakout) |

---

### orders.skipped_signals

BUY 신호가 발생했으나 실행되지 않은 종목 이력.
같은 날 동일 ticker + skip_reason 이면 skip_count를 증가시키는 **upsert** 방식.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| trade_date | DATE | 스킵 발생 일자 |
| ticker | VARCHAR(20) | 종목코드 |
| stock_name | VARCHAR(100) | 종목명 (market-service 캔들 API fallback으로 한글명 보장) |
| price | NUMERIC(15,2) | 신호 발생 시 현재가 |
| strategy_name | VARCHAR(50) | 신호를 발생시킨 전략 |
| market_state | VARCHAR(20) | 시장상태 |
| skip_reason | VARCHAR(50) | 스킵 사유 (아래 표 참고) |
| skip_count | INT | 당일 스킵 횟수 (5분마다 +1) |
| first_skipped_at | TIMESTAMP | 첫 스킵 시각 |
| last_skipped_at | TIMESTAMP | 마지막 스킵 시각 |

**UNIQUE 제약: (trade_date, ticker, skip_reason)**

**skip_reason 값:**

| 값 | 설명 |
|----|------|
| `BUDGET_INSUFFICIENT` | 1회 매수금액으로 1주 미만 |
| `MAX_POSITIONS` | 최대 보유 종목 수 도달 |
| `MARKET_WARN` | 투자주의/경고/위험 종목 (`mrkt_warn_cls_code ≠ 00`) |

**스킵 기록 발생 위치 (strategy-service StrategyEngine):**

```
runForTicker()
  ├── !priceData.isSafe()          → MARKET_WARN 기록
  └── effectivePositions >= max    → MAX_POSITIONS 기록

handleSignal()
  └── quantity < 1                 → BUDGET_INSUFFICIENT 기록
```

---

### strategy.strategy_state

리스크 관리 서비스의 인메모리 상태를 영속화하는 테이블. strategy-service 재기동 시 자체 DB에서 직접 복구하기 위해 사용.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGSERIAL PK | |
| type | VARCHAR(50) | 상태 유형 (아래 표 참고) |
| ticker | VARCHAR(10) | 종목코드 |
| value | VARCHAR(100) | 저장 값 (숫자 또는 날짜 문자열) |
| updated_at | TIMESTAMP | 마지막 저장/갱신 시각 |

**UNIQUE 제약: (type, ticker)**

**type 값:**

| 값 | 관리 클래스 | value 형식 | 설명 |
|----|-----------|-----------|------|
| `PEAK_PRICE` | `TrailingStopService` | `BigDecimal.toPlainString()` | 종목별 고점 가격 |
| `BUY_DATE` | `TimeCutService` | `LocalDate.toString()` (yyyy-MM-dd) | rsi-bollinger 매수일 |
| `TODAY_BOUGHT` | `VolatilityBreakoutStrategy` | `LocalDate.toString()` (yyyy-MM-dd) | 변동성 돌파 당일 매수일 |

**저장/갱신 시점:**

| type | 저장 | 삭제 |
|------|------|------|
| `PEAK_PRICE` | 고점 신규 진입 또는 갱신 시 (실제 값이 변경된 경우에만) | 포지션 청산 또는 트레일링 스탑 발동 시 |
| `BUY_DATE` | rsi-bollinger BUY 체결 시 | SELL 체결 또는 타임컷 발동 시 |
| `TODAY_BOUGHT` | volatility-breakout BUY 체결 시 | 15:20 강제청산 또는 09:05 오버나이트 청산 시 |

---

### portfolio.portfolio

보유 포지션 목록. Kafka `order-events` 토픽을 consume하여 자동 갱신.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| ticker | VARCHAR(10) PK | 종목코드 |
| stock_name | VARCHAR(50) | 종목명 (추가 매수 시 최신값으로 자동 갱신) |
| quantity | INT | 수량 |
| avg_price | NUMERIC(15,2) | 평균 매수단가 |
| total_invest | NUMERIC(15,2) | 총 투자금액 |

---

## 인메모리 데이터 (strategy-service)

리스크 관리 핵심 3개 상태는 **메모리 + DB 이중 저장** 방식으로 운영됩니다.
상태 변경 시마다 `strategy.strategy_state` 테이블에 즉시 동기화하며, 재기동 시 자체 DB에서 직접 복구합니다.

### TrailingStopService.peakPrices

```
Map<String, BigDecimal>  →  { ticker: 고점가격 }
```

| 항목 | 내용 |
|------|------|
| 저장 위치 | ConcurrentHashMap (힙 메모리) + `strategy.strategy_state` (type=PEAK_PRICE) |
| 갱신 주기 | 1분마다 TrailingStopScheduler.check() + 5분마다 StrategyEngine.check() → max(기존고점, 현재가)로 갱신 |
| 재시작 복구 | `@PostConstruct initFromPortfolio()` → `strategy.strategy_state`에서 직접 로드 |
| 삭제 시점 | 포지션 청산 시 (매도 또는 트레일링 스탑 발동) — 메모리 + DB 동시 삭제 |

**stopPrice 계산:**
```
stopPrice = peakPrice × (1 - trailingStopPct / 100)
          = peakPrice × 0.93   (기본 7%)
```

**UI 표시용 추가 계산 (프론트엔드):**
```
remainingAmt = currentPrice - stopPrice
remainingPct = remainingAmt / currentPrice × 100
```

---

### TimeCutService.buyDates

```
Map<String, LocalDate>  →  { ticker: 매수일 }
```

| 항목 | 내용 |
|------|------|
| 저장 위치 | ConcurrentHashMap (힙 메모리) + `strategy.strategy_state` (type=BUY_DATE) |
| 적용 대상 | rsi-bollinger 전략으로 매수한 종목만 |
| 갱신 주기 | BUY 체결 시 `recordBuy()` 호출로 오늘 날짜 저장 — 메모리 + DB 동시 저장 |
| 재시작 복구 | `@PostConstruct initFromOrders()` → `strategy.strategy_state`에서 직접 로드 |
| 삭제 시점 | SELL 체결 시 또는 타임컷 발동 시 `clearBuy()` 호출 — 메모리 + DB 동시 삭제 |

**경과/잔여 거래일 계산:**
```
elapsed   = TradingCalendar.tradingDaysBetween(buyDate, today)
remaining = max(0, timeCutDays - elapsed)
```

---

### VolatilityBreakoutStrategy.todayBought

```
Map<String, LocalDate>  →  { ticker: 매수일 }
```

| 항목 | 내용 |
|------|------|
| 저장 위치 | ConcurrentHashMap (힙 메모리) + `strategy.strategy_state` (type=TODAY_BOUGHT) |
| 적용 대상 | volatility-breakout 전략으로 당일 매수한 종목 |
| 갱신 주기 | BUY 체결 확정 후 `markBought()` 호출 — 메모리 + DB 동시 저장 |
| 재시작 복구 | `@PostConstruct initFromDb()` → `strategy.strategy_state`에서 직접 로드 |
| 삭제 시점 | 15:20 강제청산 또는 09:05 오버나이트 청산 완료 후 `removeTodayBought()` 호출 — 메모리 + DB 동시 삭제 |

---

### 인메모리 유지 (재기동 무방)

| 상태 | 클래스 | 이유 |
|------|--------|------|
| `currentState`, `yesterdayClose`, `ma20` 등 | `MarketStateService` | 매일 08:30 자동 갱신 |
| `boughtList`, `soldList` 카운터 | `DailySummaryCollector` | 15:25 일일 요약 후 리셋, 로그 목적 |
| `cachedToken`, `cachedRealToken` | `KisTokenService` | 만료 시 자동 갱신 |
| `stockNameCache` | `KisMarketApiService` | 성능 캐시, 재조회 가능 |
| `watchTickers`, `lastBuyRanking`, `lastEvalAt` | `StrategyEngine` | 매 5분 주기 실시간 계산 |
| AdminConfigStore 설정 | `AdminConfigStore` | 이미 `admin-config.json`으로 파일 영속화됨 |

---

## 재시작 복구 흐름

```
strategy-service Pod 기동
        │
        ▼
 @PostConstruct 실행 (Bean 초기화 완료 직후 1회)
        │
        ├──► TrailingStopService.initFromPortfolio()
        │         │
        │         ▼
        │    stateStore.loadAllPeakPrices()
        │    ← strategy.strategy_state WHERE type='PEAK_PRICE'
        │         │
        │         ▼
        │    peakPrices.putAll(loaded)
        │    로그: "[TrailingStop] DB에서 peakPrices 복구 — N개"
        │
        ├──► TimeCutService.initFromOrders()
        │         │
        │         ▼
        │    stateStore.loadAllBuyDates()
        │    ← strategy.strategy_state WHERE type='BUY_DATE'
        │         │
        │         ▼
        │    buyDates.putAll(loaded)
        │    로그: "[TimeCut] DB에서 buyDates 복구 — N개"
        │
        └──► VolatilityBreakoutStrategy.initFromDb()
                  │
                  ▼
             stateStore.loadAllTodayBought()
             ← strategy.strategy_state WHERE type='TODAY_BOUGHT'
                  │
                  ▼
             todayBought.putAll(loaded)
             로그: "[VolBreakout] DB에서 todayBought 복구 — N개"
```

> 타 서비스(portfolio-service, order-service) 기동 순서에 **무관**합니다.
> strategy-service가 자체 PostgreSQL에서 직접 읽으므로 가상 스레드 재시도 로직 없음.

---

## Slack 알림 구조

| 메서드 | 발생 시점 | 메시지 형식 |
|--------|----------|------------|
| `sendTradeResult(signal, success, errorMsg)` | 전략 신호 주문 직후 | 아래 참조 |
| `sendTrailingStop(ticker, stockName, currentPrice, stopPercent, success)` | 트레일링 스탑 발동 | 아래 참조 |
| `sendTimeCut(ticker, stockName, currentPrice, elapsed, maxDays, success)` | 타임컷 청산 | 아래 참조 |
| `sendForceExit(ticker, stockName, quantity, price, success)` | 마감청산 (15:20) | 아래 참조 |
| `sendError(message)` | 전략 예외 오류 | `⚠️ [전략 오류] 메시지` |
| `sendServiceStarted()` | strategy-service 기동 | `🟢 strategy-service 시작` |
| `sendServiceStopped()` | strategy-service 종료 | `🔴 strategy-service 종료` |

> `sendTradeResult`는 신호 알림 + 주문 결과를 **단일 메시지**로 통합하여 중복 발송 방지.
> 리스크 관리(트레일링 스탑·타임컷·마감청산)는 전용 메서드로 분리하여 `[전략 오류]` 표시 제거.

### 메시지 상세 형식

#### sendTradeResult — 전략 신호 매수/매도

```
✅ *[매수 체결]* 삼성전자 (005930)
> 전략: rsi-bollinger
> 가격: 74,000원
> 신호: RSI 과매도 + 볼린저 하단 터치
```

```
❌ *[매수 실패]* 삼성전자 (005930)
> 전략: rsi-bollinger
> 가격: 74,000원
> 신호: RSI 과매도 + 볼린저 하단 터치
> 실패사유: [KIS-ERRCD] 잔고 부족
```

```
✅ *[매도 체결]* 삼성전자 (005930)
> 전략: rsi-bollinger
> 가격: 78,500원
> 신호: RSI 과매수 + 볼린저 상단 돌파
```

#### sendTrailingStop — 트레일링 스탑

```
🛑 *[전략 실행 | 트레일링 스탑]* 삼성전자 (005930)
> 고점 대비 7.0% 하락 → 강제 매도
> 매도가: 73,200원  |  주문: 성공
```

#### sendTimeCut — 타임컷

```
⏱️ *[전략 실행 | 타임컷]* SK하이닉스 (000660)
> 3거래일 경과 (기준: 3일) → 강제 매도
> 매도가: 235,000원  |  주문: 성공
```

#### sendForceExit — 마감청산 (15:20)

```
🔔 *[전략 실행 | 마감청산]* 카카오 (035720) 10주
> 변동성 돌파 — 오버나이트 방지 (15:20)
> 매도가: 42,500원  |  주문: 성공
```

---

## API 엔드포인트 전체 목록

### market-service (8081)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/market/stocks/{ticker}/price` | 현재가 조회 |
| GET | `/api/market/stocks/search` | 종목 검색 |
| GET | `/api/market/stocks/{ticker}` | 종목 상세 조회 |
| GET | `/api/market/stocks/{ticker}/candles` | 일봉 조회 |
| GET | `/api/market/index/{code}/candles` | 지수 일봉 조회 (strategy-service 내부 전용) |

### order-service (8082)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/orders/buy` | 매수 주문 |
| POST | `/api/orders/sell` | 매도 주문 |
| GET | `/api/orders` | 전체 주문 이력 |
| GET | `/api/orders/ticker/{ticker}` | 종목별 주문 이력 |
| POST | `/api/orders/skipped` | 스킵 신호 기록 (strategy-service 전용) |
| GET | `/api/orders/skipped?days=N` | 최근 N일 스킵 목록 (프론트엔드용) |

### portfolio-service (8083)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/portfolio` | 보유 포지션 목록 |
| GET | `/api/portfolio/balance` | 계좌 잔고 |

### strategy-service (8084)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/strategy/market-state` | 현재 시장 상태 조회 |
| POST | `/api/strategy/refresh-market-state` | 시장 상태 수동 갱신 |
| POST | `/api/strategy/run` | 전략 즉시 실행 |
| POST | `/api/strategy/test-slack` | Slack 테스트 |
| GET | `/api/strategy/admin/status` | 관리자 설정 조회 |
| POST | `/api/strategy/admin/pause` | 매매 중단 |
| POST | `/api/strategy/admin/resume` | 매매 재개 |
| PATCH | `/api/strategy/admin/config` | 투자 설정 변경 |
| GET | `/api/strategy/admin/trailing-stop-status` | 트레일링 스탑 현황 |
| GET | `/api/strategy/admin/time-cut-status` | 타임컷 현황 |
