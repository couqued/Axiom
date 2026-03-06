# 자동매매 전략 상세 문서

> strategy-service에 구현된 하이브리드 자동매매 전략 전체를 정리합니다.

---

## 목차

1. [전략 아키텍처 개요](#1-전략-아키텍처-개요)
2. [시장 상태 판별 (Market Filter)](#2-시장-상태-판별-market-filter)
3. [골든크로스 전략 (상승장)](#3-골든크로스-전략-상승장)
4. [변동성 돌파 전략 (상승장 단기)](#4-변동성-돌파-전략-상승장-단기)
5. [RSI + 볼린저밴드 통합 전략 (횡보장)](#5-rsi--볼린저밴드-통합-전략-횡보장)
6. [트레일링 스탑](#6-트레일링-스탑)
7. [타임 컷](#7-타임-컷)
8. [스케줄러 전체 구조](#8-스케줄러-전체-구조)
9. [설정 방법](#9-설정-방법)

---

## 1. 전략 아키텍처 개요

### 상태 기반 하이브리드 전략

시장 상황을 먼저 판별한 뒤 그에 맞는 전략을 자동 선택합니다.

```
[매일 08:20] 감시 종목 목록 갱신 (market-service)
    ↓
  StockScreenerService: stock-universe.json 로드
  → 코스피200(85개) + 코스닥150(22개) = 107개 종목 캐싱

[매일 08:30] 감시 종목 동적 갱신 + 시장 상태 판별 (strategy-service)
    ↓
  MarketStateScheduler:
    ① market-service /internal/screened-tickers → watchTickers 갱신
    ② 코스피 종가 > MA20 → BULLISH (상승장)
       코스피 종가 ≤ MA20 → SIDEWAYS (횡보장)

[09:05~15:20 5분 주기] 전략 실행
    ↓
  BULLISH  → 변동성 돌파 + 골든크로스 (동시 실행)
  SIDEWAYS → RSI + 볼린저밴드 통합 전략
    ↓
[포지션 사이징 + BUY 가드]
    → 수량 = floor(50만 원 / 현재가)
    → ① 시장경보 종목 스킵 → ② 이미 보유 중 스킵 → ③ maxPositions(3) 초과 스킵
    ↓
[공통 리스크 관리]
    → 트레일링 스탑: 고점 -7% 하락 시 자동 청산
    → 타임 컷: RSI+볼린저 매수 후 3거래일 미반등 시 강제 청산
```

### 전략 등록 방식

`TradingStrategy` 인터페이스를 구현하고 `@Component`를 붙이면 Spring이 자동 등록합니다. `application.yml`의 `enabled-strategies`에 전략 이름을 추가하면 활성화됩니다.

```java
public interface TradingStrategy {
    String getName();           // application.yml의 enabled-strategies 값과 매칭
    int minimumCandles();       // 전략 계산에 필요한 최소 캔들 수
    SignalDto evaluate(String ticker, List<CandleDto> candles);
}
```

### LiveCandle 주입

`StrategyEngine`은 5분마다 실행 시 현재가(`getCurrentPrice()`)를 조회해 당일 LiveCandle을 생성하고, 역사적 캔들 목록의 마지막에 추가합니다. 변동성 돌파 전략이 장중 실시간 현재가를 기반으로 매수 조건을 판별할 수 있습니다.

```
historicalCandles[0..N-1] + LiveCandle(오늘시가, 현재가=종가, 장중고가, 장중저가)
                    ↓
       allCandles → evaluate(ticker, allCandles)
```

---

## 2. 시장 상태 판별 (Market Filter)

### 클래스

- `MarketStateService`: 시장 상태 판별 및 메모리 저장
- `MarketStateScheduler`: 매일 08:30 자동 실행

### 판별 로직

```
코스피(0001) 지수 최근 20일 캔들 조회
  ↓
MA20 = 최근 20개 종가의 단순 이동평균(SMA)
  ↓
마지막 종가 > MA20 → BULLISH (상승장)
마지막 종가 ≤ MA20 → SIDEWAYS (횡보장)
```

### 특징

- 기본값: `SIDEWAYS` (서비스 시작 직후 / 데이터 부족 시 보수적 판단)
- 결과는 `AtomicReference<MarketState>`에 메모리 저장
- `market-filter.enabled: false` 설정 시 판별 없이 항상 모든 전략 실행

### 수동 갱신

```bash
POST http://localhost:8084/api/strategy/refresh-market-state

응답:
{"state": "BULLISH"}
```

---

## 3. 골든크로스 전략 (상승장)

| 항목 | 값 |
|------|-----|
| 클래스 | `GoldenCrossStrategy` |
| 전략 이름 | `golden-cross` |
| 시장 상태 | BULLISH |
| 최소 캔들 | 21개 (MA20 + 전일 비교용 1개) |
| 보유 기간 | 며칠 ~ 몇 주 (스윙) |

### 계산 로직

```
전일: MA5_prev  = candles[last-2 ~ last-6] 5개 평균
      MA20_prev = candles[last-2 ~ last-21] 20개 평균

당일: MA5_curr  = candles[last-1 ~ last-5] 5개 평균
      MA20_curr = candles[last-1 ~ last-20] 20개 평균
```

### 신호 조건

| 신호 | 조건 |
|------|------|
| **BUY** (골든크로스) | 전일 MA5 ≤ MA20 AND 당일 MA5 > MA20 |
| **SELL** (데드크로스) | 전일 MA5 ≥ MA20 AND 당일 MA5 < MA20 |
| HOLD | 그 외 |

### 특징

- 추세 전환 시점에만 신호 발생 → 잦은 매매 없음
- 중장기 보유 (며칠~몇 주) 적합
- 상승 추세 중반에 진입하는 특성

---

## 4. 변동성 돌파 전략 (상승장 단기)

| 항목 | 값 |
|------|-----|
| 클래스 | `VolatilityBreakoutStrategy` |
| 전략 이름 | `volatility-breakout` |
| 시장 상태 | BULLISH |
| 최소 캔들 | 3개 |
| 보유 기간 | 당일 (오버나이트 방지) |

### 계산 로직

```
yesterday = candles[last-1]   ← 전일 캔들
today     = candles[last]     ← 당일 LiveCandle (현재가)

Range  = yesterday.highPrice - yesterday.lowPrice  (전일 변동폭)
목표가 = today.openPrice + Range × K              (K = 0.5)

현재가(today.closePrice) ≥ 목표가 → BUY 신호
```

### 신호 조건

| 신호 | 조건 |
|------|------|
| **BUY** | 현재가 ≥ 목표가 AND 당일 아직 매수 안 함 |
| HOLD | 현재가 < 목표가 |
| SELL | 전략 자체는 매도 신호 없음 — 강제 청산(ForceExitScheduler)으로 처리 |

### 당일 중복 매수 방지

`ConcurrentHashMap<String, LocalDate> todayBought`에 매수 날짜를 저장합니다. 당일 이미 매수한 종목은 재진입하지 않습니다.

### 강제 청산 (ForceExitScheduler)

매일 **15:20**에 `ForceExitScheduler`가 실행되어 변동성 돌파로 당일 매수한 포지션을 모두 강제 청산합니다. 오버나이트 리스크를 방지합니다.

```
ForceExitScheduler (cron: 0 20 15 * * MON-FRI, zone: Asia/Seoul)
  → VolatilityBreakoutStrategy.getTodayBought() 조회
  → portfolio-service에서 실제 보유 여부 확인
  → 보유 중이면 order-service에 SELL 주문
  → Slack 알림 발송
```

### K 값 (0.5)의 의미

- K=0.5: 전일 변동폭의 절반 이상 상승 시 매수
- K가 클수록 필터 강함(진입 어려움), K가 작을수록 진입 쉬움
- 국내 대형주 기준 0.5가 일반적으로 사용됨

---

## 5. RSI + 볼린저밴드 통합 전략 (횡보장)

| 항목 | 값 |
|------|-----|
| 클래스 | `RsiBollingerStrategy` |
| 전략 이름 | `rsi-bollinger` |
| 시장 상태 | SIDEWAYS |
| 최소 캔들 | 21개 (BB_PERIOD=20 + 1) |
| 보유 기간 | 1 ~ 5일 (단기~스윙) |

### RSI 계산 (Wilder's Smoothed RSI)

```
기간: 14일

초기 평균 상승폭 = 최초 14일 상승분 합 / 14
초기 평균 하락폭 = 최초 14일 하락분 합 / 14

이후 (Wilder's Smoothing):
  avgGain = (avgGain × 13 + 당일 상승폭) / 14
  avgLoss = (avgLoss × 13 + 당일 하락폭) / 14

RS  = avgGain / avgLoss
RSI = 100 - (100 / (1 + RS))
```

### 볼린저밴드 계산

```
기간: 20일, 승수: 2

중심선 (Middle) = MA20 = 최근 20개 종가 평균
표준편차 (σ)   = sqrt(Σ(종가 - MA20)² / 20)

상단밴드 (Upper) = MA20 + 2σ
하단밴드 (Lower) = MA20 - 2σ
```

### 신호 조건

| 신호 | 조건 |
|------|------|
| **BUY** | RSI(14) < 30 **AND** 현재 종가 < 하단밴드 |
| **SELL** | RSI(14) > 70 **OR** 현재 종가 ≥ 중심선(MA20) |
| HOLD | 그 외 |

### 전략 원리

- **BUY**: RSI 과매도(< 30) **동시에** 볼린저밴드 하단 이탈 → 두 지표가 동시에 과매도를 확인 → 허위 신호 최소화
- **SELL(RSI 과매수)**: RSI > 70 → 단기 과열 상태에서 차익 실현
- **SELL(중심선 회귀)**: 횡보장 특성상 하단→중심선 반등이 목표 — 중심선 도달 시 익절

---

## 6. 트레일링 스탑

| 항목 | 값 |
|------|-----|
| 클래스 | `TrailingStopService` |
| 설정 | `AdminConfigStore.trailingStopPct` (런타임 변경 가능) |
| 기본값 | `strategy.trailing-stop.stop-percent: 7.0` (yml → AdminConfigStore 초기화) |
| 적용 범위 | 모든 보유 종목 |
| 실행 시점 | `TrailingStopScheduler` 1분 주기 (보유 종목 전용) + `StrategyEngine.run()` 5분 주기 |

### 동작 방식

```
보유 종목별 현재가 확인
  ↓
peakPrice = max(peakPrice, 현재가)    ← 고점 갱신
  ↓
stopPrice = peakPrice × (1 - 7/100)   ← 청산 기준가
  ↓
현재가 ≤ stopPrice → SELL 주문 + Slack 알림 (🛑 [트레일링 스탑])
현재가 > stopPrice → 아무것도 안 함
```

### 고점 초기화 및 재시작 복구

- `peakPrices`는 `ConcurrentHashMap<String, BigDecimal>`에 저장 (메모리)
- **재시작 복구**: `@PostConstruct initFromPortfolio()` 실행 → portfolio-service의 `avgPrice`로 즉시 초기화
  (avgPrice는 보수적 초기값, 다음 체크 시 실제 현재가로 자동 갱신)
- 보유 포지션 없는 종목은 자동으로 제외됨

### 현황 조회 API

```
GET /api/strategy/admin/trailing-stop-status
→ { "005930": { "peakPrice": 78500, "stopPrice": 72975 }, ... }
```

### 7% 설정 이유

- 삼성전자, SK하이닉스 등 대형주의 일일 변동성: 약 1~3%
- 5% 설정 시 단기 변동으로 오청산 위험 높음
- 7%: 2~3일 내 반전을 기대하는 적정 완충 폭

---

## 7. 타임 컷

| 항목 | 값 |
|------|-----|
| 클래스 | `TimeCutService` |
| 설정 | `AdminConfigStore.timeCutDays` (런타임 변경 가능) |
| 기본값 | `strategy.time-cut.max-holding-days: 3` (yml → AdminConfigStore 초기화) |
| 적용 전략 | `rsi-bollinger` (applicable-strategies 목록) |
| 실행 시점 | StrategyEngine.run() 내 (5분 주기) |

### 동작 방식

```
RSI+볼린저 전략으로 BUY 발생 시:
  buyDates[ticker] = LocalDate.now()  ← 매수일 기록

5분 주기 실행 시 각 ticker 확인:
  경과 거래일 = TradingCalendar.tradingDaysBetween(buyDate, today)
  ↓
  경과 거래일 ≥ 3 → SELL 주문 + Slack 알림 (⏱️ [타임 컷])
  경과 거래일 < 3 → 아무것도 안 함
```

### 거래일 계산 (TradingCalendar)

주말(토/일)을 제외한 실제 거래일 수를 계산합니다. 공휴일은 별도 처리하지 않습니다 (KIS API에서 공휴일 주문 불가 오류 반환으로 대응).

```java
// 예시: 월요일 매수 → 수요일(2거래일 후)까지 유지, 목요일(3거래일 후) 강제 청산
tradingDaysBetween(Monday, Wednesday) = 2   // 아직 유지
tradingDaysBetween(Monday, Thursday)  = 3   // 강제 청산!
```

### 재시작 복구

- `buyDates`는 `ConcurrentHashMap<String, LocalDate>`에 저장 (메모리)
- **재시작 복구**: `@PostConstruct initFromOrders()` 실행
  → order-service에서 FILLED BUY 이력 조회
  → 현재 보유 중인 종목 중 `rsi-bollinger` 전략 매수 건의 매수일 자동 복구

### 현황 조회 API

```
GET /api/strategy/admin/time-cut-status
→ { "000660": { "buyDate": "2026-03-04", "elapsed": 2, "remaining": 1 }, ... }
```

### 3거래일 설정 이유

- RSI+볼린저밴드 전략의 기대 반등 기간: 1~3일 (단기 과매도 해소)
- 3거래일 내 반등 없으면 추세 전환 가능성 → 손실 제한을 위해 청산
- 설정 변경: `max-holding-days: 5`로 늘리면 더 긴 보유 허용

---

## 8. 스케줄러 전체 구조

| Cron | 클래스 | 역할 |
|------|--------|------|
| `0 20 8 * * MON-FRI` | `StockScreenerService` (market-service) | stock-universe.json 로드 → 코스피200+코스닥150 목록 캐싱 |
| `0 30 8 * * MON-FRI` | `MarketStateScheduler` (strategy-service) | ① 감시 종목 목록 갱신(watchTickers) ② 코스피 MA20 → 시장 상태 판별 |
| `0 5/5 9-15 * * MON-FRI` | `StrategyScheduler` | 전략 실행 + 트레일링 스탑 + 타임 컷 |
| `0 * 9-15 * * MON-FRI` | `TrailingStopScheduler` | 보유 종목 트레일링 스탑 1분 단독 체크 (09:00~15:20) |
| `0 20 15 * * MON-FRI` | `ForceExitScheduler` | 변동성 돌파 포지션 강제 청산 |
| `0 40 15 * * MON-FRI` | `CandleCollectScheduler` (market-service) | 당일 일봉 수집 및 DB 저장 (mock 모드 시 스킵) |

> 모든 스케줄러에 `zone = "Asia/Seoul"` 설정 — KST 기준으로 동작

### StrategyScheduler 상세

`0 5/5 9-15 * * MON-FRI` cron은 09:05, 09:10 ... 15:55까지 실행을 시도합니다. 코드로 15:20 이후를 추가 차단합니다:

```java
if (hour == 15 && minute > 20) return;
// 실제 실행: 09:05, 09:10 ... 15:15, 15:20 (최대 76회/일)
```

---

## 9. 설정 방법

### 런타임 관리자 설정 (AdminConfigStore)

`application.yml` 재시작 없이 실시간으로 매매 동작을 변경할 수 있습니다.

```bash
# 매매 긴급 정지 (StrategyEngine.run() 스킵, ForceExitScheduler는 항상 동작)
POST http://localhost:8084/api/strategy/admin/pause

# 매매 재개
POST http://localhost:8084/api/strategy/admin/resume

# 투자 설정 변경 (null 필드는 기존 값 유지)
PATCH http://localhost:8084/api/strategy/admin/config
{
  "investAmountKrw": 300000,
  "maxPositions": 2,
  "trailingStopPct": 5.0,
  "timeCutDays": 5
}

# 현재 설정 조회
GET http://localhost:8084/api/strategy/admin/status
→ {
    "paused": false,
    "investAmountKrw": 500000,
    "maxPositions": 3,
    "trailingStopPct": 7.0,
    "timeCutDays": 3
  }
```

설정은 `admin-config.json`에 자동 저장되어 서비스 재시작 후에도 유지됩니다.
파일이 없으면 `application.yml`의 기본값으로 초기화됩니다.

> **우선순위:** `admin-config.json` > `application.yml` 기본값

### AdminConfigStore 관리 항목

| 항목 | 필드 | yml 기본값 | 적용 시점 |
|------|------|-----------|----------|
| 1회 매수금액 | `investAmountKrw` | `position-sizing.invest-amount-krw` | 다음 5분 사이클 |
| 최대 보유 종목 수 | `maxPositions` | `position-sizing.max-positions` | 다음 5분 사이클 |
| 트레일링 스탑 % | `trailingStopPct` | `trailing-stop.stop-percent` | 다음 5분 사이클 |
| 타임 컷 거래일 | `timeCutDays` | `time-cut.max-holding-days` | 다음 5분 사이클 |
| 매매 중단 여부 | `paused` | `false` | 즉시 |

### application.yml 전체 예시

```yaml
strategy:
  watch-tickers:           # yml fallback (08:30 이전 또는 market-service 응답 실패 시)
    - "005930"             # 삼성전자
    - "000660"             # SK하이닉스
  candle-days: 60          # 역사적 캔들 조회 기간
  position-sizing:
    invest-amount-krw: 500000  # 1회 매수 금액(원). 수량 = floor(금액 / 현재가)
    max-positions: 3           # 동시에 보유할 수 있는 최대 종목 수 (초과 시 BUY 스킵)
  enabled-strategies:      # 등록된 전략 목록 (시장 상태에 따라 런타임 필터링)
    - golden-cross
    - volatility-breakout
    - rsi-bollinger
  market-filter:
    enabled: true          # false 시 시장 상태 무관하게 모든 전략 실행
    index-code: "0001"     # 코스피(0001) / 코스닥(1001)
    ma-period: 20          # 이동평균 기간
  trailing-stop:
    enabled: true
    stop-percent: 7.0      # 고점 대비 하락 허용 폭 (%)
  time-cut:
    enabled: true
    max-holding-days: 3    # 최대 보유 거래일
    applicable-strategies:
      - rsi-bollinger       # 타임 컷 적용 전략 (변동성 돌파는 ForceExit으로 별도 관리)

portfolio-service:
  url: http://localhost:8083
```

> `watch-tickers`는 서비스 재시작 직후 또는 `market-service` 응답 실패 시 사용하는 **fallback 목록**입니다.
> 정상 운영 시 매일 08:30에 `market-service /internal/screened-tickers`로 **코스피200+코스닥150** 전체로 자동 교체됩니다.

### 감시 종목 관리

감시 종목은 `market-service/src/main/resources/stock-universe.json`에서 관리합니다.

```json
{
  "description": "코스피200 + 코스닥150 구성 종목",
  "lastUpdated": "2026-03-04",
  "kospi200": ["005930", "000660", "005380", ...],
  "kosdaq150": ["247540", "086520", "263750", ...]
}
```

KRX는 6월/12월에 지수 구성 종목을 리밸런싱합니다. 변경 시 `stock-universe.json`을 수동 업데이트하고 서비스를 재시작합니다.

### 전략 활성화/비활성화

```yaml
strategy:
  enabled-strategies:
    - golden-cross          # 이 줄 제거 → 골든크로스 비활성화
    - volatility-breakout
    # - rsi-bollinger       # 주석 처리 → RSI+볼린저밴드 비활성화
```

### 시장 필터 비활성화 (전체 전략 항상 실행)

```yaml
strategy:
  market-filter:
    enabled: false   # 시장 상태 무관하게 모든 enabled-strategies 실행
```

### 리스크 관리 조정

런타임 변경 (재시작 불필요, 다음 5분 사이클 즉시 반영):

```bash
PATCH http://localhost:8084/api/strategy/admin/config
{ "trailingStopPct": 5.0, "timeCutDays": 5 }
```

또는 관리자 패널(⚙️)의 "투자 설정" 섹션에서 직접 입력.

yml 기본값 변경 (서비스 재시작 필요, admin-config.json 없을 때만 적용):

```yaml
strategy:
  trailing-stop:
    enabled: true
    stop-percent: 5.0    # 더 엄격하게 (빠른 청산)
    # stop-percent: 10.0 # 더 느슨하게 (긴 보유)
  time-cut:
    enabled: true
    max-holding-days: 5  # 5거래일로 연장
```

---

## 10. 포지션 사이징

### 개요

코스피200+코스닥150(107개) 규모의 유니버스에서 여러 종목에 동시 BUY 신호가 발생할 수 있으므로, 예산 초과를 방지하기 위한 포지션 사이징과 중복 매수 방지 로직을 구현합니다.

**예산 구조:** 50만 원 × 최대 3종목 = **최대 150만 원 동시 투자**

> **설정 소스:** `StrategyEngine`은 `investAmountKrw`와 `maxPositions`를 `application.yml` 대신 `AdminConfigStore`에서 실시간 조회합니다.
> 관리자 패널(REST API 또는 `admin-config.json`)에서 설정이 변경되면 다음 5분 사이클부터 즉시 반영됩니다.

### 수량 계산

```
BUY 수량 = floor(adminConfigStore.getInvestAmountKrw() / 현재가)

예시:
  삼성전자 75,000원 → floor(500,000 / 75,000) = 6주
  고가 종목 600,000원 → floor(500,000 / 600,000) = 0주 → BUY 스킵 + Slack 경고
```

SELL은 수량을 직접 계산하지 않고, portfolio-service에서 실제 보유 수량을 조회하여 **전량 매도**합니다.

### BUY 3단계 가드

| 단계 | 조건 | 처리 | skip_reason 기록 |
|------|------|------|-----------------|
| ① 시장경보 | `!priceData.isSafe()` | BUY 스킵 (투자주의/경고/위험 종목 제외) | `MARKET_WARN` |
| ② 중복 보유 | `positions.contains(ticker)` | BUY 스킵 (이미 보유 중인 종목 재진입 방지) | — (기록 없음) |
| ③ 한도 초과 | `positions.size() + boughtThisRun[0] >= maxPositions` | BUY 스킵 (최대 3종목 초과 방지) | `MAX_POSITIONS` |

수량 부족(투자금으로 1주 미만): `handleSignal()` 내에서 `BUDGET_INSUFFICIENT` 기록 후 스킵.

스킵 기록은 order-service의 `skipped_signals` 테이블에 저장됩니다 (당일 동일 ticker+reason은 upsert로 count 누적).

### `boughtThisRun` 카운터

동일 5분 사이클 내에서 여러 종목에 BUY가 발생하는 상황을 처리합니다.

```java
int[] boughtThisRun = {0};  // 이번 사이클 신규 매수 수

// 종목 순회 중 BUY 성공 시
boughtThisRun[0]++;

// 다음 종목의 한도 체크 시
int effective = positions.size() + boughtThisRun[0];
if (effective >= maxPositions) skip;  // 실시간 카운터로 초과 방지
```

`positions`는 사이클 시작 시 1회만 조회합니다. 주문 API 응답 지연 등으로 인해 DB 반영 전에 다음 종목 평가가 실행될 수 있으므로, 메모리 카운터로 보완합니다.

### 동작 예시

| 상황 | 처리 |
|------|------|
| 보유 1종목, 이번 사이클 0건 → BUY 신호 | effective=1 → 진입 (잔여 2슬롯) |
| 보유 2종목, 이번 사이클 1건 → BUY 신호 | effective=3 → **스킵** (한도 도달) |
| 이미 삼성전자 보유 중, 삼성전자 BUY 신호 | **스킵** (중복 보유) |
| 투자경고 종목 BUY 신호 | **스킵** (시장경보) |
| SELL 신호, 보유 6주 | portfolio에서 6주 조회 → **6주 전량 매도** |
| SELL 신호, 보유 없음 | **스킵** |
