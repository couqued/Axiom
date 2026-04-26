# 자동매매 전략 상세 문서

> strategy-service에 구현된 하이브리드 자동매매 전략 전체를 정리합니다.

---

## 목차

1. [전략 아키텍처 개요](#1-전략-아키텍처-개요)
2. [시장 상태 판별 (Market Filter)](#2-시장-상태-판별-market-filter)
3. [골든크로스 전략 (상승장)](#3-골든크로스-전략-상승장)
4. [변동성 돌파 전략 (상승장 단기)](#4-변동성-돌파-전략-상승장-단기)
5. [RSI + 볼린저밴드 통합 전략 (횡보장)](#5-rsi--볼린저밴드-통합-전략-횡보장)
6. [ML 예측 전략 (XGBoost, 모든 시장 상태)](#6-ml-예측-전략-xgboost-모든-시장-상태)
7. [트레일링 스탑](#7-트레일링-스탑)
8. [타임 컷](#8-타임-컷)
9. [개장 초 보호 + 지수 하락 매수 차단](#9-개장-초-보호--지수-하락-매수-차단)
10. [스케줄러 전체 구조](#10-스케줄러-전체-구조)
11. [설정 방법](#11-설정-방법)
12. [포지션 사이징](#12-포지션-사이징)
13. [종목 선정 우선순위 점수화 \[계획\]](#13-종목-선정-우선순위-점수화-계획)
14. [매수 신호 근접도 (Signal Gap)](#14-매수-신호-근접도-signal-gap)
15. [하락장(BEARISH) 전략 \[계획\]](#15-하락장bearish-전략-계획)

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

[09:00 첫 실행, 이후 09:05~15:19 5분 주기] 전략 실행
    ↓
  BULLISH          → 변동성 돌파 + 골든크로스 + ML 예측 (동시 실행)
  SIDEWAYS/BEARISH → RSI + 볼린저밴드 + ML 예측
    ↓
[Phase 1: 전체 종목 평가]
    SELL·트레일링 스탑·타임 컷 → 즉시 처리
    BUY → score 계산 후 buyQueue 수집 (① 시장경보 스킵 ② 보유 중 스킵)
    ↓
[Phase 1.5: 개장 초 보호 + 지수 하락 체크]
    (09:20 이전 BUY 가드 제거됨 — 모든 전략 09:00부터 허용)
      ML 예측은 EntryQuality 3축 multiplier + 갭업/FOMO 가드로 자체 진입 타이밍 조절
    ↓
    현재 시각 ≥ 09:20 + 당일 첫 체크?
      → 코스피 현재 지수 조회
      → 하락률 = (yesterdayClose - currentIndex) / yesterdayClose × 100
      → 하락률 ≥ indexDropBlockPct% → 당일 BUY 전체 차단
    ↓
    indexDropBlockedToday == true (AND indexDropBlockPct > 0)? → BUY 스킵
    ↓
[Phase 2: BUY score 정렬 → 상위 maxPositions개 매수]
    buyQueue.sort(score 내림차순)
    ③ maxPositions 초과 시 스킵 (MAX_POSITIONS)
    ④ 그룹 슬롯 한도 초과 시 스킵 (GROUP_LIMIT)
       - rsi-bollinger: bollingerMaxPositions 한도
       - volatility-breakout / golden-cross: (maxPositions - bollingerMaxPositions) 한도
       - ml-prediction: mlDailyLimit 한도 (Admin 설정)
    수량 = floor(investAmountKrw / 현재가), 0주이면 BUDGET_INSUFFICIENT 스킵
    ↓
[공통 리스크 관리]
    → 트레일링 스탑: 고점 -7% 하락 시 자동 청산 (ML 포함)
    → 타임 컷: RSI+볼린저 매수 후 3거래일 미반등 시 강제 청산 (ML 제외)
    → ML 청산: TP / SL / maxDays 기준 MlExitService가 5분마다 체크
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
코스피(0001) 지수 최근 20일 캔들 조회  ← IndexCandleService (항상 KIS real 서버 사용)
  ↓
MA20 = 최근 20개 종가의 단순 이동평균(SMA)
  ↓
마지막 종가 > MA20 → BULLISH (상승장)
마지막 종가 ≤ MA20 → SIDEWAYS (횡보장)
```

> **지수 데이터는 항상 KIS real 서버에서 조회합니다.**
> paper 서버가 지수 API에서 10일 이상 지연된 잘못된 데이터를 반환하는 문제를 우회합니다.
> `KIS_REAL_APP_KEY`, `KIS_REAL_APP_SECRET`이 미설정이면 mock 데이터로 폴백합니다.

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

`todayBought`는 `evaluate()` 내부가 아닌 **주문 체결 확정 후** `markBought(ticker)`를 통해 등록합니다. BUY 신호를 발생시켰더라도 StrategyEngine의 BUY 가드(maxPositions 등)에 의해 스킵된 경우에는 등록되지 않습니다.

### 재시작 복구 (todayBought)

서비스 재기동 시 `@PostConstruct initFromDb()`에서 `strategy.strategy_state` 테이블(type=TODAY_BOUGHT)을 직접 조회하여 즉시 복구합니다.

```
initFromDb() (@PostConstruct)
  → stateStore.loadAllTodayBought()
  ← strategy.strategy_state WHERE type='TODAY_BOUGHT'
  → todayBought.putAll(loaded)
```

> order-service/portfolio-service 기동 순서에 무관. 자체 DB에서 직접 복구.

### 강제 청산 (ForceExitScheduler)

`ForceExitScheduler`는 두 가지 청산 스케줄을 실행합니다.

#### ① 당일 마감 청산 (15:19)

매일 **15:19**에 변동성 돌파로 당일 매수한 포지션을 강제 청산합니다.

```
forceExit() — cron: 0 19 15 * * MON-FRI
  → todayBought에서 오늘 날짜와 일치하는 종목 추출
  → portfolio-service에서 실제 보유 여부 확인
  → 보유 중이면 SELL 주문 + Slack 알림
  → 처리 완료 후 todayBought에서 제거
```

#### ② 오버나이트 미청산 익일 청산 (09:05)

서비스 재시작 등으로 15:19 청산이 누락된 경우, 다음 거래일 **09:05**에 보완 청산합니다.

```
exitOvernightPositions() — cron: 0 5 9 * * MON-FRI
  ① todayBought에서 오늘 날짜가 아닌 항목 추출 (오버나이트 후보)
  ② order-service 이력 검증
     - ticker 일치 + strategyName = "volatility-breakout"
     - orderType = BUY + status = FILLED
     - 매수일 = todayBought에 기록된 날짜
  ③ portfolio-service에서 실제 보유 확인
  ④ 검증 통과 종목만 SELL 주문
     → 대상 없으면: Slack "📋 [09:05 오버나이트청산] 대상 없음"
     → 청산 완료: Slack "🔔 [09:05 오버나이트청산] 완료" + 종목별 매수가·결과
  ⑤ 처리 완료 후 todayBought에서 제거
```

> 09:00 정각은 시초가 호가 스프레드가 넓어 체결이 불안정하므로 09:05에 실행합니다.

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

#### BUY — OR 조건 + 점수화 + 분할매수 (buyStage)

| 조건 | buyStage | 매수 비율 |
|------|----------|-----------|
| 종가 < 하단밴드만 충족 (RSI ≥ 30) | 1단계 | 투자금 50% |
| RSI < 30 AND 종가 < 하단밴드 (동시) | 2단계 | 투자금 나머지 50% |
| RSI < 30만 충족 (종가 ≥ 하단밴드) | 2단계 | 투자금 나머지 50% |

```
BB 하단 이탈 깊이 점수 = min((lower - close) / close × 100 / 5, 1) × 50  ← 최대 50점 (5% 이탈)
RSI 과매도 깊이 점수  = min((30 - RSI) / 30, 1) × 50                    ← 최대 50점 (RSI=0이면 50점)
총점 = BB 점수 + RSI 점수                                                  ← 최대 100점
```

- 1단계 매수 후 2단계 진입 조건 충족 시 `BollingerReserveService`가 나머지 금액 자동 추가 매수 예약

#### SELL — %B 필터

| 조건 | 처리 |
|------|------|
| 종가 ≥ 상단밴드(Upper) | 즉시 익절 (상단선 도달) |
| RSI > 70 AND %B ≥ 0.5 | 과매수 청산 |
| RSI > 70 AND %B < 0.5 | **HOLD** (중심선 미달, 추가 상승 기대) |
| 그 외 | HOLD |

```
%B = (현재가 - 하단밴드) / (상단밴드 - 하단밴드)
     0 = 하단밴드,  0.5 = 중심선(MA20),  1 = 상단밴드
```

### 전략 원리

- **BUY (OR + 점수화)**: RSI 과매도 또는 BB 하단 이탈 중 하나만 충족해도 1단계 진입 허용. 두 조건 동시 충족 시 과매도 심화(2단계)로 분할 추가 매수 → 허위 신호는 소액(50%)으로 제한, 진짜 신호는 풀포지션
- **SELL(상단밴드 도달)**: BB 상단까지 반등 완료 → 즉시 익절
- **SELL(RSI 과매수 + %B 필터)**: RSI > 70이어도 %B < 0.5(중심선 미달)이면 HOLD — 밴드 위쪽에서 더 오를 여지가 있다고 판단
- **RSI 과매수 HOLD 알림**: `SlackNotifier.sendRsiOverboughtHold()` — 종목당 1일 1회 Slack 알림

---

## 6. ML 예측 전략 (XGBoost, 모든 시장 상태)

| 항목 | 값 |
|------|-----|
| 클래스 | `MLPredictionStrategy` |
| 전략 이름 | `ml-prediction` |
| 시장 상태 | BULLISH / SIDEWAYS / BEARISH 모두 실행 |
| 최소 캔들 | 80개 (28개 피처 계산 최소 요건) |
| ML 서비스 | `ml-service` (FastAPI, port 8085) |

### 개요

XGBoost 기반 멀티 타겟 모델이 각 종목의 **상승 확률(confidence)**, **기대 수익률(mlScore)**, **예상 보유일수(expectedDays)** 를 예측하고, EntryQuality 3축 multiplier로 진입 타이밍을 조절한다.

```
ml-service POST /predict
  ↓
TradePlanDto (confidence, mlScore, entryPrice, TP, SL, expectedDays, maxDays)
  ↓
confidence < mlBuyThreshold (기본 0.75)? → HOLD
  ↓
EntryQualityEvaluator (3축 multiplier × 2 강제 가드)
  → multiplier ≤ 0 → HOLD (갭업/FOMO 가드)
  ↓
effectiveScore = mlScore × multiplier
  ↓
SignalDto.BUY (score = effectiveScore)
  ↓
StrategyEngine buyQueue에 편입 → score 기준 상위 maxPositions개 선택
```

### 3개 XGBoost 모델

| 모델 | 예측 대상 | 파일 |
|------|----------|------|
| cls | 5일 내 목표가 도달 확률 (0~1) | `model_cls.json` |
| ret | 기대 수익률 (%) | `model_ret.json` |
| days | 예상 보유 일수 | `model_days.json` |

학습 데이터: 감시 종목 전체 일봉 (`candle-days: 90`), Triple-Barrier 라벨링 적용.  
모델 저장 위치: K8s PVC `/app/models/` (재시작 시 유지).

### 28개 피처

| 그룹 | 피처 수 | 설명 |
|------|---------|------|
| 개별 종목 기술적 지표 | 15 | RSI, MACD, 볼린저, SMA 비율, 거래량 비율, ATR 등 |
| 시장 Regime (코스피) | 5 | 코스피 MA20 비율, 5일 수익률, ATR, MA60 위/아래, 시장 폭 |
| 섹터 상대강도 | 3 | 현재 0 placeholder (향후 구현 예정) |
| 시간/캘린더 | 5 | 요일, 월 내 날짜, 월말 잔여일, 옵션 만기주 여부 등 |

### EntryQuality 3축 Multiplier

| 축 | 기준 | 범위 |
|----|------|------|
| shortMult | 직전 5분봉 숏 패턴 강도 | 0.6 ~ 1.0 |
| sessionMult | 시가 대비 현재가 위치 | 0.6 ~ 1.1 |
| gapMult | 전일 종가 대비 갭업 크기 | 0.5 ~ 1.0 |

`effectiveScore = mlScore × min(shortMult, sessionMult, gapMult)`

#### 강제 DEFER 가드

| 가드 | 조건 | 적용 시간 |
|------|------|---------|
| 갭업 가드 | 전일 종가 대비 +2% 이상 갭업 | 09:15 이전 |
| FOMO 가드 | 당일 시가 대비 +2% 이상 상승 | 10:30 이전 |

연속 3회 DEFER → 당일 블랙리스트 등록 (`MlDeferTracker`)

### 청산 (MlExitService)

5분 주기로 활성 ML 포지션을 체크해 세 조건 중 먼저 발생한 것으로 시장가 청산:

| 청산 조건 | 기준 |
|---------|------|
| **TP (익절)** | 현재가 ≥ takeProfitPrice |
| **SL (손절)** | 현재가 ≤ stopLossPrice |
| **maxDays (기간)** | 보유 거래일 ≥ maxDays (기본 7일) |

> TrailingStop(-7%)도 병행 적용 — 이중 보호

### Slack 알림

```
✅ [ML 예측 매수] 삼성전자 (005930)
> 확신도: 83%  |  스코어: 74.5
> 매수가: 78,900원  |  TP: 82,500원  |  SL: 76,200원
> 예상 보유: 4일 (최대 7일)

💰 [ML 예측 ML TP] 삼성전자 (005930)
> 수익률: +4.56%  |  3거래일 보유
> 매수: 78,900원 → 매도: 82,500원
> TP: 82,500원  |  SL: 76,200원  |  최대: 7일  |  주문: 성공

⛔ [ML 블랙리스트] 삼성전자 (005930)
> 연속 DEFER 3회 → 당일 추가 평가 제외
```

### Admin 설정

| 항목 | 키 | 기본값 | 설명 |
|------|-----|--------|------|
| ML 일일 한도 | `mlDailyLimit` | 4 (=maxPositions) | 당일 ML 전략 최대 매수 횟수. 0이면 비활성 |
| ML 확신도 임계값 | `mlBuyThreshold` | 0.75 (75%) | 이 값 미만이면 HOLD |
| 분봉 미세조정 | `mlEntryTimingEnabled` | true | false 시 multiplier=1.0 고정 |

### 학습 트리거

| 방법 | 설명 |
|------|------|
| 서비스 시작 시 자동 | 최초 기동 시 즉시 학습 시도 |
| 수동 | `POST http://ml-service:8085/train` |
| 모델 상태 확인 | `GET http://ml-service:8085/model/status` |

---

## 7. 트레일링 스탑

| 항목 | 값 |
|------|-----|
| 클래스 | `TrailingStopService` |
| 설정 | `AdminConfigStore.getActiveSettings().trailingStopPct()` (런타임 변경 가능) |
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

- `peakPrices`는 `ConcurrentHashMap<String, BigDecimal>`에 저장 (메모리), 키 형식: `"mode:ticker"` (예: `"paper:005930"`, `"real:000660"`)
- 상태 변경 시마다 `strategy.strategy_state` 테이블(type=PEAK_PRICE_paper / PEAK_PRICE_real)에 즉시 동기화
- **재시작 복구**: `@PostConstruct initFromPortfolio()`에서 DB 직접 로드 (타 서비스 기동 순서 무관)
  - 고점 갱신: 실제 값이 변경된 경우에만 DB write (불필요한 write 방지)
  - 복구 후 다음 체크 시 현재가가 더 높으면 자동으로 고점 갱신
- 보유 포지션 없는 종목은 자동으로 제외됨 (check() 내에서 DB도 동시 삭제)

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

## 8. 타임 컷

| 항목 | 값 |
|------|-----|
| 클래스 | `TimeCutService` |
| 설정 | `AdminConfigStore.getActiveSettings().timeCutDays()` (런타임 변경 가능) |
| 기본값 | `strategy.time-cut.max-holding-days: 3` (yml → AdminConfigStore 초기화) |
| 적용 전략 | `rsi-bollinger` (applicable-strategies 목록) |
| 실행 시점 | StrategyEngine.run() 내 (5분 주기) |

### 동작 방식

```
RSI+볼린저 전략으로 BUY 발생 시:
  buyDates["mode:ticker"] = LocalDate.now()  ← 매수일 기록 (예: "paper:005930")

5분 주기 실행 시 각 ticker 확인:
  경과 거래일 = TradingCalendar.tradingDaysBetween(buyDate, today)
  ↓
  경과 거래일 ≥ 3 → SELL 주문 + Slack 알림 (⏱️ [타임 컷])
  경과 거래일 < 3 → 아무것도 안 함
```

### 거래일 계산 (TradingCalendar)

주말(토/일) 및 `application.yml`의 `trading.holidays`에 등록된 공휴일을 제외한 실제 거래일 수를 계산합니다.
`KrxHolidayInitializer`가 앱 시작 시 YAML 목록을 `TradingCalendar.HOLIDAYS`에 주입하므로 별도 코드 변경 없이 반영됩니다.

```java
// 예시: 월요일 매수, 수요일이 공휴일인 경우
tradingDaysBetween(Monday, Thursday)  = 2   // 수요일 제외 → 아직 유지
tradingDaysBetween(Monday, Friday)    = 3   // 강제 청산!
```

### 재시작 복구

- `buyDates`는 `ConcurrentHashMap<String, LocalDate>`에 저장 (메모리), 키 형식: `"mode:ticker"` (예: `"paper:005930"`, `"real:000660"`)
- 상태 변경 시마다 `strategy.strategy_state` 테이블(type=BUY_DATE_paper / BUY_DATE_real)에 즉시 동기화
- **재시작 복구**: `@PostConstruct initFromOrders()`에서 DB 직접 로드 (타 서비스 기동 순서 무관)
  - order-service/portfolio-service HTTP 호출 없이 자체 DB에서 즉시 복구
  - `recordBuy()` 호출 시 메모리 + DB 동시 저장, `clearBuy()` 시 메모리 + DB 동시 삭제

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

## 9. 개장 초 보호 + 지수 하락 매수 차단

### 목표

- 코스피 급락 당일 추가 손실 방지
- 개장 초 BUY 가드는 ML 도입 시 제거됨 (모든 전략 09:00부터 허용)

### 동작 방식

```
StrategyEngine.run() (09:00 첫 실행, 이후 매 5분)
  ↓
Phase 1.5 — BUY 가드
  ↓
당일 첫 실행 시 →
  코스피 지수 현재가 조회 (getIndexCandles, 2개)
  하락률 = (yesterdayClose - currentIndex) / yesterdayClose × 100
  하락률 ≥ indexDropBlockPct% → indexDropBlockedToday = true
  ↓
indexDropBlockedToday == true AND indexDropBlockPct > 0 →
  당일 BUY 전체 스킵 (indexBlocked flag)
  ↓
정상 BUY 진행
```

### 설정

| 항목 | 기본값 | 설명 |
|------|--------|------|
| `indexDropBlockPct` | `1.0` | 하락률 임계값(%). 0으로 설정 시 비활성화 |

- Admin UI "투자 설정 → 지수 하락 매수차단 (%)" 또는 API로 런타임 변경
- 다음날 08:30 자동 초기화 (`MarketStateService.refresh()`)

### 수동 해제

당일 차단이 발동된 후 매수를 재개하려면:
Admin UI → "지수 하락 매수차단 (%)" → `0` 입력 → 설정 저장

> `indexDropBlockPct = 0`이면 `indexDropBlockedToday` 플래그가 true여도 BUY 가드를 건너뜁니다.

---

## 10. 스케줄러 전체 구조

| Cron | 클래스 | 역할 | Slack 알림 |
|------|--------|------|-----------|
| `0 20 8 * * MON-FRI` | `StockScreenerService` (market-service) | stock-universe.json 로드 → 코스피200+코스닥150 목록 캐싱 | — |
| `0 30 8 * * MON-FRI` | `MarketStateScheduler` (strategy-service) | ① 감시 종목 목록 갱신(watchTickers) ② 코스피 MA20 → 시장 상태 판별 | 📋 감시종목 수 + 시장 상태 |
| `0 5 9 * * MON-FRI` | `ForceExitScheduler` | 오버나이트 미청산 포지션 익일 장 시작 직후 청산 (전략 검증 포함) | 🔔 종목별 매수가·매도 주문가 포함 / 대상 없으면 "대상 없음" |
| `0 0 9 * * MON-FRI` | `StrategyScheduler` | 09:00 첫 실행 (ML 예측 전용 — 규칙 기반 전략은 09:20 이후에만 BUY) | — |
| `0 5/5 9-15 * * MON-FRI` | `StrategyScheduler` | 전략 실행 + 트레일링 스탑 + 타임 컷 + ML TP/SL/maxDays 청산 체크 | — (15:25 일일 요약으로 취합) |
| `0 * 9-15 * * MON-FRI` | `TrailingStopScheduler` | 보유 종목 트레일링 스탑 1분 단독 체크 (09:00~15:19) | — (발동 시만 🛑 알림) |
| `0 0 10-15 * * MON-FRI` | `StrategyScheduler` | 직전 1시간 실행 요약 Slack 발송 | 🕐 시간별 요약 |
| `0 22 15 * * MON-FRI` | `StrategyScheduler` | 15시대 실행 요약 Slack 발송 | 🕐 시간별 요약 (15시) |
| `0 19 15 * * MON-FRI` | `ForceExitScheduler` | 변동성 돌파 당일 매수 포지션 마감 청산 | 🔔 종목별 개별 알림 |
| `0 25 15 * * MON-FRI` | `DailySummaryCollector` | 전략 실행 일일 요약 발송 + 카운터 초기화 | 📊 일일 요약 (실행횟수·매수·매도·스킵 종목 포함) |
| `0 40 15 * * MON-FRI` | `CandleCollectScheduler` (market-service) | 당일 일봉 수집 및 DB 저장 (mock 모드 시 스킵) | — |

> 모든 스케줄러에 `zone = "Asia/Seoul"` 설정 — KST 기준으로 동작
>
> `TradingCalendar.isTradingDay()`를 이용한 **공휴일 guard**가 5개 메서드 모두에 적용됩니다. 공휴일에는 "공휴일 — 스킵" 로그 후 즉시 return합니다.
> `runStrategies()`, `checkTrailingStop()`, `exitOvernightPositions()` 3개는 추가로 **수능일 늦은 개장 guard**가 적용되어 10시 이전 실행을 스킵합니다.

### Slack 알림 상세

#### 08:30 감시종목 갱신
```
📋 [08:30 감시종목갱신] 완료
> 감시 종목: 107개  |  시장 상태: BULLISH
```

#### 09:05 오버나이트 청산
```
📋 [09:05 오버나이트청산] 대상 없음                    ← 대상 없을 때

🔔 [09:05 오버나이트청산] 완료                         ← 청산 시
> 청산 종목: 2개  |  결과: 전체 성공 ✅
> 삼성전자 (005930) | 매수가: 78,900원 | 매도 주문가: 78,900원 | ✅
> SK하이닉스 (000660) | 매수가: 195,000원 | 매도 주문가: 195,000원 | ✅
```
> 매수가 = `avgPrice` (portfolio-service 평균매수가), 매도 주문가 = 동일 (시장가 주문 참조가). 실제 체결가는 OrderResult에 포함되지 않음.

#### 15:25 전략 일일 요약
```
📊 [전략 일일 요약] 2026-03-09
> 실행 횟수: 75회 (09:05 ~ 15:19)
> 평가 종목: 107개 × 75회
> 매수: 2건  |  매도: 1건  |  오류: 0건
> 스킵(시장경보): 1건  |  스킵(최대보유): 3건
> 매수 종목: 삼성전자 (005930) 78,900원, SK하이닉스 (000660) 195,000원
> 매도 종목: NAVER (035420) 212,000원
```

> v0.6.1부터 스킵 종목 목록은 일일 요약에서 제거됩니다 (가독성 개선).

`DailySummaryCollector`가 `StrategyScheduler` 실행마다 `RunResult`를 누적하고, 15:25에 한 번에 발송 후 카운터를 초기화합니다.

### StrategyScheduler 상세

두 개의 cron 설정이 적용됩니다:

- `0 0 9 * * MON-FRI` — 09:00 단 1회 실행 (ML 예측 전략 전용 진입 타이밍)
- `0 5/5 9-15 * * MON-FRI` — 09:05, 09:10 ... 15:55까지 5분 주기. 코드로 15:19 이후 추가 차단:

```java
if (hour == 15 && minute > 19) return;
// 실제 실행: 09:05, 09:10 ... 15:15 (15:20 미만)
```

---

## 11. 설정 방법

### 런타임 관리자 설정 (AdminConfigStore)

`application.yml` 재시작 없이 실시간으로 매매 동작을 변경할 수 있습니다.

```bash
# 매매 긴급 정지
POST http://localhost:8084/api/strategy/admin/pause

# 매매 재개
POST http://localhost:8084/api/strategy/admin/resume

# 투자 설정 변경 (null 필드는 기존 값 유지)
PATCH http://localhost:8084/api/strategy/admin/config
{
  "investAmountKrw": 300000,
  "maxPositions": 5,
  "trailingStopPct": 7.0,
  "timeCutDays": 3,
  "indexDropBlockPct": 2.0,
  "volatilityBreakoutDailyLimit": 4,
  "goldenCrossDailyLimit": 4,
  "bollingerDailyLimit": 4
}

# 전략 모드 전환
PATCH http://localhost:8084/api/strategy/admin/config
{ "strategyMode": "all-strategies" }   # "market-based" | "all-strategies"

# 현재 설정 조회
GET http://localhost:8084/api/strategy/admin/status
→ {
    "strategyMode": "market-based",
    "settings": {
      "paused": false,
      "investAmountKrw": 300000,
      "maxPositions": 5,
      "trailingStopPct": 7.0,
      "timeCutDays": 3,
      "indexDropBlockPct": 2.0,
      "volatilityBreakoutDailyLimit": 4,
      "goldenCrossDailyLimit": 4,
      "bollingerDailyLimit": 4
    },
    "indexDropBlockedToday": false,
    "indexDropCheckedToday": false
  }
```

설정은 `admin-config.json`에 자동 저장되어 서비스 재시작 후에도 유지됩니다.
파일이 없으면 `application.yml`의 기본값으로 초기화됩니다.
구 플랫 포맷(`admin-config.json`)이 존재하면 서비스 기동 시 자동으로 새 `settings` 중첩 포맷으로 마이그레이션됩니다.

> **우선순위:** `admin-config.json` > `application.yml` 기본값

### AdminConfigStore 관리 항목

| 항목 | 필드 | yml 기본값 | 적용 시점 |
|------|------|-----------|----------|
| 전략 모드 | `strategyMode` | `"market-based"` | 즉시 |
| 매매 중단 여부 | `settings.paused` | `false` | 즉시 |
| 1회 매수금액 | `settings.investAmountKrw` | `position-sizing.invest-amount-krw` | 다음 5분 사이클 |
| 최대 보유 종목 수 | `settings.maxPositions` | `position-sizing.max-positions` | 다음 5분 사이클 |
| 트레일링 스탑 % | `settings.trailingStopPct` | `trailing-stop.stop-percent` | 다음 5분 사이클 |
| 타임 컷 거래일 | `settings.timeCutDays` | `time-cut.max-holding-days` | 다음 5분 사이클 |
| 지수 하락 매수차단 % | `settings.indexDropBlockPct` | `1.0` (하드코딩) | 다음 5분 사이클 |
| 변동성 돌파 일일 매수 한도 | `settings.volatilityBreakoutDailyLimit` | `4` | 다음 5분 사이클 |
| 골든크로스 일일 매수 한도 | `settings.goldenCrossDailyLimit` | `4` | 다음 5분 사이클 |
| 볼린저 일일 매수 한도 | `settings.bollingerDailyLimit` | `4` | 다음 5분 사이클 |

> **`strategyMode`**: `"market-based"` — 시장 상태(BULLISH/SIDEWAYS)에 따라 전략 자동 선택 / `"all-strategies"` — 시장 상태 무관하게 활성화된 전략 전체 실행

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

trading:
  holidays:                # KRX 공휴일 목록 — 매년 초 KRX 공고 후 업데이트
    - "2026-01-01"         # 신정
    - "2026-02-17"         # 설날 (연휴 포함 전후 날짜도 추가)
    # ... 나머지 공휴일
  late-open-days:          # 10:00 늦은 개장일 (수능 등)
    - "2026-11-19"         # 대학수학능력시험

portfolio-service:
  url: http://localhost:8083
```

> 공휴일 목록은 연 1회 KRX 공식 휴장일 공고 확인 후 수동 업데이트. 변경 후 strategy-service 재배포 필요.

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

## 12. 포지션 사이징

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

### BUY 4단계 가드

| 단계 | 조건 | 처리 | skip_reason 기록 |
|------|------|------|-----------------|
| ① 시장경보 | `!priceData.isSafe()` | BUY 스킵 (투자주의/경고/위험 종목 제외) | `MARKET_WARN` |
| ② 중복 보유 | `positions.contains(ticker)` | BUY 스킵 (이미 보유 중인 종목 재진입 방지) | — (기록 없음) |
| ③ 총량 한도 | `positions.size() + boughtThisRun[0] >= maxPositions` | BUY 스킵 (전체 최대 종목 수 초과 방지) | `MAX_POSITIONS` |
| ④ 그룹 한도 | `bollingerHeld + boughtBollingerThisRun[0] >= bollingerMaxPositions` (볼린저 신호) 또는 `trendHeld + boughtTrendThisRun[0] >= trendMaxPositions` (추세 신호) | BUY 스킵 (그룹 슬롯 한도 초과) | `GROUP_LIMIT` |

> ④는 EXTREME_FEAR bypass 매수와 레거시 포지션(`entryTag=null`)에는 적용되지 않습니다.

수량 부족(투자금으로 1주 미만): `handleSignal()` 내에서 `BUDGET_INSUFFICIENT` 기록 후 스킵.

스킵 기록은 order-service의 `skipped_signals` 테이블에 저장됩니다 (당일 동일 ticker+reason은 upsert로 count 누적).

### 그룹 슬롯 분리 (bollingerMaxPositions)

`maxPositions`를 두 그룹으로 분리하여 시장 상태 전환 시 슬롯 경합을 방지합니다.

```
bollingerMaxPositions = 2  (볼린저 전용)
trendMaxPositions     = maxPositions - bollingerMaxPositions  (추세 전용)

예) maxPositions=4, bollingerMaxPositions=2
  → rsi-bollinger: 최대 2종목
  → volatility-breakout + golden-cross: 최대 2종목
```

**entryTag 저장**: 매수 성공 시 전략명을 `entryTag`로 `strategy_state` DB에 저장합니다.
재기동 후 `loadAllEntryTags()`로 복구하여 기존 포지션의 그룹 귀속을 유지합니다.

| 시나리오 | 결과 |
|---------|------|
| 횡보장에서 볼린저 2슬롯 소진 후 볼린저 신호 | 그룹 한도 → 스킵 |
| 볼린저 2슬롯 소진 → 상승장 전환 → 추세 신호 | 추세 슬롯 여유 있으면 정상 매수 |
| 레거시 포지션 (entryTag=null) | 총량 체크만 적용, 그룹 체크 미적용 |
| 2차 매수 (물타기) | 그룹 체크 우회 |
| EXTREME_FEAR bypass | 총량 초과 허용 + 그룹 체크도 우회 |

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

---

## 13. 종목 선정 우선순위 점수화

### 개요

BUY 신호가 발생한 종목 전체를 score 기준으로 정렬하여 **가장 강한 신호 상위 3종목만 실제 매수**합니다.

### 구현 구조

#### `SignalDto.score` 필드

```java
private double score; // BUY 신호 강도 점수 (0~100). SELL/HOLD = 0
```

#### 전략별 score 계산 (0~100점)

| 전략 | 점수 구성 | 각 항목 상한 |
|------|----------|------------|
| **변동성 돌파** | 돌파 강도(0~50) + 거래량 급증(0~50) | 돌파율 2% = 50점, 거래량 3배 = 50점 |
| **골든크로스** | MA 이격률(0~50) + 거래량 급증(0~50) | 이격 1% = 50점, 거래량 3배 = 50점 |
| **RSI+볼린저** | RSI 과매도 깊이(0~50) + 밴드 이탈 깊이(0~50) | RSI=0 = 50점, 하단밴드 5% 이탈 = 50점 |

```
변동성 돌파 score:
  breakoutPct = (현재가 - 목표가) / 목표가 × 100
  volRatio    = 오늘거래량 / 최근20일평균거래량
  score = min(breakoutPct/2, 1) × 50 + min(volRatio/3, 1) × 50

골든크로스 score:
  maGapPct = (MA5 - MA20) / MA20 × 100
  volRatio = 오늘거래량 / 최근20일평균거래량
  score = min(maGapPct/1, 1) × 50 + min(volRatio/3, 1) × 50

RSI+볼린저 score:
  rsiScore  = min((30 - RSI) / 30, 1) × 50
  bandScore = min((하단밴드 - 현재가) / 하단밴드 × 100 / 5, 1) × 50
  score = rsiScore + bandScore
```

#### `StrategyEngine` 2단계 처리

```
Phase 1 — 전체 종목 순회:
  SELL 신호     → 즉시 처리 (지연 없음)
  트레일링 스탑  → 즉시 처리 (지연 없음)
  타임 컷       → 즉시 처리 (지연 없음)
  BUY 신호      → 가드 ①②(시장경보·중복보유) 통과 시 buyQueue에 수집
                   동일 종목 여러 전략 BUY → score 최고값 채택

Phase 2 — BUY 실행:
  buyQueue.sort(score 내림차순)
  로그: "[Engine] BUY 후보 N개 수집 → score 기준 상위 3개 실행 — [005930(87.3), 000660(72.1), ...]"
  상위부터 순서대로 가드 ③(최대보유 수) 체크 → 통과 시 주문 실행
```

### 특징

- **SELL·리스크 관리는 즉시 처리** — BUY만 랭킹 적용, 청산 지연 없음
- **동일 종목 중복 방지** — BULLISH 시 golden-cross + volatility-breakout 둘 다 BUY 신호 내도 score 높은 전략 1개만 채택
- `reason` 필드에 `[score=XX.X]` 포함 → 로그 및 Slack 알림에서 확인 가능

---

## 14. 매수 신호 근접도 (Signal Gap)

### 개요

감시 중인 전 종목(코스피200+코스닥150)에 대해 **현재 상태에서 매수 신호까지 얼마나 남았는지**를 % 단위로 계산하고, 가장 가까운 N개를 반환하는 분석 기능입니다.

장중에 어떤 종목이 매수 신호에 근접해 있는지 확인하거나, 전략이 실행되지 않는 원인을 진단하는 데 활용합니다.

### 동작 방식

350개 종목을 순회하며 종목당 API 호출이 필요하므로 **비동기 캐시 패턴**으로 구현합니다.

```
POST /api/strategy/signal-gap/refresh
  → 즉시 {"result": "계산 시작"} 반환
  → 백그라운드(CompletableFuture.runAsync)에서 전 종목 순회
    각 종목: getCurrentPrice() + getCandles() + 라이브캔들 생성 + gap 계산 + 200ms sleep
    ≈ 350개 × 200ms = 약 70초 소요
  → 완료 시 signalGapCache 갱신 + signalGapComputedAt 타임스탬프 기록

GET /api/strategy/signal-gap
  → 캐시 즉시 반환: { items, computedAt, running }
```

### 전략별 gap 계산

| 시장 상태 | 전략 | 계산 대상 | gapPct 의미 |
|-----------|------|-----------|------------|
| BULLISH | 변동성 돌파 | 목표가 | `(목표가 - 현재가) / 현재가 × 100` — 양수: 상승 필요, 음수: 조건 충족 |
| BULLISH | 골든크로스 | MA20 | `(MA20 - MA5) / MA20 × 100` — 양수: 크로스 임박, 음수: MA5 ≥ MA20 |
| SIDEWAYS | RSI+볼린저 | 하단밴드 | `(현재가 - 하단밴드) / 현재가 × 100` — 양수: 하락 필요, 음수: 조건 충족 |

```
변동성 돌파 gap:
  목표가 = 오늘시가 + (전일고가 - 전일저가) × 0.5
  gapPct = (목표가 - 현재가) / 현재가 × 100

골든크로스 gap:
  MA5  = 최근 5개 종가 평균 (라이브캔들 포함)
  MA20 = 최근 20개 종가 평균 (라이브캔들 포함)
  gapPct = (MA20 - MA5) / MA20 × 100
  → gapPct > 0: 크로스 임박 (값이 작을수록 근접)
  → gapPct ≤ 0: MA5 ≥ MA20 — 오늘 처음 크로스라면 다음 5분 사이클에서 BUY 신호

RSI+볼린저 gap:
  하단밴드 = MA20 - 2σ  (최근 20일 기준)
  RSI(14)  = Wilder's Smoothed RSI
  gapPct   = (현재가 - 하단밴드) / 현재가 × 100
```

> **골든크로스 주의**: gapPct ≤ 0은 "MA5 ≥ MA20" 상태를 의미할 뿐, BUY 신호가 보장되지는 않습니다.
> 실제 BUY 조건은 `전일 MA5 ≤ MA20 AND 당일 MA5 > MA20` (오늘 처음 크로스)이므로,
> 이미 며칠 전 크로스된 종목은 gapPct ≤ 0이더라도 HOLD입니다.

결과는 전략별 구분 없이 `gapPct` 오름차순 전체 정렬 후 상위 N개 반환. BULLISH 시 변동성 돌파 + 골든크로스가 섞여 표시됩니다.

### API

```bash
# 백그라운드 계산 시작
POST http://localhost:8084/api/strategy/signal-gap/refresh?top=10
→ {"result": "계산 시작"}   # 이미 실행 중이면 {"result": "계산 중"}

# 캐시 조회 (running=true이면 아직 계산 중)
GET http://localhost:8084/api/strategy/signal-gap
→ {
    "running": false,
    "computedAt": "2026-03-14T10:30:00",
    "items": [
      {
        "rank": 1,
        "ticker": "005930",
        "stockName": "삼성전자",
        "strategy": "volatility-breakout",
        "currentPrice": 74800,
        "threshold": 75300,
        "gapPct": 0.67,
        "rsi": -1,
        "detail": "목표가=75300, 현재=74800, Range=2100"
      },
      ...
    ]
  }
```

### 관련 클래스

| 클래스/파일 | 역할 |
|------------|------|
| `StrategyEngine.triggerSignalGapRefresh(int topN)` | 비동기 계산 시작 (중복 실행 방지) |
| `StrategyEngine.calcSignalGapsInternal(int topN)` | 실제 gap 계산 로직 (private) — BULLISH 시 변동성 돌파 + 골든크로스 동시 계산 |
| `StrategyEngine.calcGoldenCrossGap()` | MA5/MA20 이격률 계산 (private) |
| `StrategyEngine.calcVolBreakoutGap()` | 변동성 돌파 목표가까지 gap 계산 (private) |
| `StrategyEngine.calcRsiBollingerGap()` | RSI+볼린저 하단밴드까지 gap 계산 (private) |
| `StrategyController GET /signal-gap` | 캐시 즉시 반환 |
| `StrategyController POST /signal-gap/refresh` | 계산 트리거 |
| `SignalGapDto` | `rank, ticker, stockName, strategy, currentPrice, threshold, gapPct, rsi, detail` |

### 프론트엔드 연동

Strategy 페이지 "매수 신호 근접도" 섹션:
- "조회" 버튼 클릭 → `POST /signal-gap/refresh` → 3초 간격 폴링 → `running=false` 시 테이블 표시
- `computedAt` 타임스탬프 표시 (마지막 계산 기준)
- `gapPct ≤ 0`: "조건 충족" (녹색)
- BULLISH: `gapPct > 0` → "X% 상승 필요" (파란색)
- SIDEWAYS: `gapPct > 0` → "X% 하락 필요" (빨간색)

---

## 15. 하락장(BEARISH) 전략 [계획]

### 현재 문제

시장 상태가 BULLISH / SIDEWAYS 2단계뿐이어서, 코스피가 MA20 아래로 크게 하락한 경우에도 SIDEWAYS로 분류되어 RSI+볼린저밴드 전략이 실행됩니다.
강한 하락 추세에서 RSI+볼린저밴드 전략은 추가 하락 구간에서 계속 매수 신호를 내어 손실이 누적될 수 있습니다.

### Step 1: 3단계 시장 상태 도입

```java
public enum MarketState {
    BULLISH,   // 상승장: 종가 > MA20
    SIDEWAYS,  // 횡보장: MA20 × 0.97 ≤ 종가 ≤ MA20
    BEARISH    // 하락장: 종가 < MA20 × 0.97  (MA20 대비 3% 이상 하락)
}
```

| 조건 | 상태 |
|------|------|
| 종가 > MA20 | BULLISH |
| MA20 × 0.97 ≤ 종가 ≤ MA20 | SIDEWAYS |
| 종가 < MA20 × 0.97 | BEARISH |

> 3% 기준은 조정 가능 (`market-filter.bearish-threshold: 0.03`).

### Step 2: BEARISH 상태에서의 동작 옵션

#### 옵션 A: 매수 중단 (가장 보수적) ← **1차 구현 권장**

BEARISH 상태에서 BUY 신호를 모두 무시합니다. SELL 신호·트레일링 스탑·타임 컷은 정상 동작합니다.

```
BEARISH → 신규 BUY 없음 (현금 보유)
         → 기존 포지션 청산 로직은 계속 동작 (손실 최소화)
```

```java
// StrategyEngine.run() 변경
if (marketState == MarketState.BEARISH && signal.isBuy()) {
    // BUY 스킵, skip_reason = "BEARISH_MARKET" 기록
    continue;
}
```

#### 옵션 B: 인버스 ETF 전략 ← **2차 구현 검토**

하락장에서 인버스 ETF를 매수하여 시장 하락 수익을 추구합니다.

| ETF | 코드 | 설명 |
|-----|------|------|
| KODEX 200 인버스 | `114800` | 코스피200 역방향 추종 |
| KODEX 코스닥150 인버스 | `251340` | 코스닥150 역방향 추종 |

```
BEARISH → BearMarketInverseEtfStrategy 실행
  → 인버스 ETF 매수 (변동성 돌파 유사 로직 or 단순 시장 상태 진입)
  → 시장 상태가 SIDEWAYS/BULLISH 복귀 시 인버스 ETF 매도
```

#### 옵션 C: 트레일링 스탑 강화 (하락장 자동 축소)

BEARISH 상태 진입 시 트레일링 스탑 퍼센트를 자동으로 줄여 더 빠른 손절을 유도합니다.

```java
// MarketStateService에서 상태 변경 시
if (newState == MarketState.BEARISH) {
    adminConfigStore.setTrailingStopPct(
        Math.min(adminConfigStore.getTrailingStopPct(), 4.0)  // 최대 4%로 축소
    );
}
```

### Step 3: Slack 알림 추가

```
🐻 [시장 상태] BEARISH 진입 — 코스피 X,XXX pt (MA20 대비 -X.X%)
> 신규 매수 중단. 기존 포지션 청산 전략 계속 동작.
```

### 구현 우선순위

| 단계 | 내용 | 난이도 |
|------|------|--------|
| 1 | `MarketState.BEARISH` enum 추가 + 판별 로직 수정 | 낮음 |
| 2 | `StrategyEngine`에서 BEARISH 시 BUY 스킵 처리 | 낮음 |
| 3 | BEARISH 진입/복귀 Slack 알림 | 낮음 |
| 4 | BEARISH 시 트레일링 스탑 자동 강화 | 중간 |
| 5 | `BearMarketInverseEtfStrategy` 구현 | 높음 |
