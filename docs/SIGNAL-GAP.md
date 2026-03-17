# 매수신호근접도 & BUY 랭킹 — 로직 참조 문서

> 이 문서는 `StrategyEngine.java` 라인 638~856에 구현된 두 기능의 전체 로직,
> 변수 의미, 점수·랭킹 산출 방식, 화면 표시 방법을 정리한다.

---

## 1. 매수 신호 근접도 (SignalGap)

관심 종목 각각에 대해 "현재가가 매수 기준가에 얼마나 가까운지"를 점수로 산출하고,
점수 순으로 상위 N개를 반환하는 기능이다.

---

### 1-1. DTO 필드 설명

파일: `strategy-service/.../dto/SignalGapDto.java`

| 필드 | 타입 | 설명 |
|------|------|------|
| `rank` | `int` | 목록 내 순위 (1부터) |
| `ticker` | `String` | 종목코드 |
| `stockName` | `String` | 종목명 |
| `strategy` | `String` | 전략 구분: `"rsi-bollinger"` / `"volatility-breakout"` / `"golden-cross"` |
| `currentPrice` | `double` | 현재가 |
| `threshold` | `double` | 매수 기준가 (BB 하단 / 변동성돌파 목표가 / MA20) |
| `gapPct` | `double` | 기준가까지 남은 거리 % — 양수=아직 미달, 음수=이미 돌파 |
| `rsi` | `double` | RSI(14) 값. `rsi-bollinger` 전략만 유효; 나머지는 `-1` |
| `bbUpper` | `double` | 볼린저밴드 상단(익절 기준). `rsi-bollinger` 전략만 유효; 나머지는 `0` |
| `score` | `double` | 진입 점수 (전략별 0~150점) |
| `detail` | `String` | 사람이 읽을 수 있는 계산 부연 설명 |

---

### 1-2. 백엔드 상태 변수

파일: `strategy-service/.../engine/StrategyEngine.java` (라인 59–61)

| 변수 | 타입 | 설명 |
|------|------|------|
| `signalGapCache` | `volatile List<SignalGapDto>` | 가장 최근 계산 결과 캐시 |
| `signalGapComputedAt` | `volatile LocalDateTime` | 마지막 계산 완료 시각 |
| `signalGapRunning` | `volatile boolean` | 현재 계산 중 여부 (중복 실행 방지) |

---

### 1-3. 조회 흐름

```
[프론트 조회 버튼]
  → POST /api/strategy/signal-gap/refresh?top=10   (triggerSignalGapRefresh)
  → signalGapRunning = true
  → CompletableFuture.runAsync → calcSignalGapsInternal(topN)
      for ticker in watchTickers:
          현재가 + 캔들(candleDays) 조회
          라이브 캔들(당일) 추가
          시장상태 분기:
              SIDEWAYS / BEARISH → calcRsiBollingerGap()
              BULLISH            → calcVolBreakoutGap() + calcGoldenCrossGap()
      score 내림차순 → gapPct 오름차순 정렬
      상위 topN개에 rank 부여 → signalGapCache 갱신
      signalGapRunning = false
  → 프론트 3초 폴링: GET /api/strategy/signal-gap
      running=false 확인 후 화면 테이블 갱신
```

---

### 1-4. 전략별 점수 산출

#### RSI + 볼린저밴드 (SIDEWAYS / BEARISH 시장)

**입력값**
- `BB_PERIOD = 20`, 밴드 배수 `2σ`
- `lowerBand` = MA20 − 2σ (볼린저 하단, 매수 기준가)
- `upperBand` = MA20 + 2σ (볼린저 상단, 익절 기준)
- `rsi` = Wilder's Smoothed RSI(14)

**점수 변수**
```
bandGapPct   = (lowerBand − currentPrice) / lowerBand × 100   // currentPrice < lowerBand 일 때만
bbScore      = min(bandGapPct / 5%, 1) × 50                   // 최대 50점 (하단 이탈 시만)

rsiScore     = min((30 − rsi) / 30, 1) × 50                   // 최대 50점 (RSI < 30 일 때만)

proximityScore = (20 − gapPct) / 20 × 30                      // gapPct ≤ 20% 이고 currentPrice ≥ lowerBand
rsiProxScore   = (50 − rsi) / 50 × 20                         // RSI < 50 이고 접근 중일 때
```

**상태별 최종 점수 및 gapPct 해석**

| 상태 | score | gapPct 의미 |
|------|-------|-------------|
| `currentPrice < lowerBand` | `50 + bbScore + rsiScore` (51 ~ 150점) | RSI 30까지 남은 % (1차 충족 시) 또는 BB 하단까지 거리 |
| `currentPrice ≥ lowerBand`, `gapPct ≤ 20%` | `proximityScore + rsiProxScore` (0 ~ 50점) | BB 하단까지 남은 % |
| `currentPrice ≥ lowerBand`, `gapPct > 20%` | `0점` | BB 하단까지 남은 % |

> `50점` 베이스가 더해지는 이유: 하단 이탈 종목을 "접근 중" 종목(최대 50점)과 점수 구간에서 명확히 분리하기 위함.

---

#### 변동성돌파 (BULLISH 시장)

**입력값**
- `range`       = 전일 고가 − 전일 저가
- `targetPrice` = 당일 시가 + range × 0.5  (매수 기준가)
- `avgVol`      = 20일 평균 거래량 (당일 제외)
- `volRatio`    = 당일 거래량 / avgVol

**점수 변수**
```
volScore  = min(volRatio / 3, 1) × 50    // 3배 거래량 = 50점
proximity = max(0, (5% − max(gapPct, 0)) / 5%) × 50   // 목표가 5% 이내만 계산
score     = volScore + proximity          // 0 ~ 100점
gapPct    = (targetPrice − currentPrice) / currentPrice × 100
            // 양수 = 아직 미달, 음수 = 이미 돌파
```

---

#### 골든크로스 (BULLISH 시장)

**입력값**
- `ma5`  = 5일 단순이동평균
- `ma20` = 20일 단순이동평균

**점수 변수**
```
maGapPct = (ma5 − ma20) / ma20 × 100    // 양수 = MA5가 MA20 상회 (크로스 완료)
maScore  = maGapPct > 0 ? min(maGapPct / 2%, 1) × 50 : 0   // 2% 이격 = 50점
volScore = min(volRatio / 3, 1) × 50
score    = maScore + volScore             // 0 ~ 100점
gapPct   = (ma20 − ma5) / ma20 × 100    // 음수 = 크로스 완료(돌파), 양수 = 아직 미달
```

---

### 1-5. 정렬 기준

```java
gaps.sort(
    Comparator.comparingDouble(SignalGapDto::score).reversed()  // 1순위: 점수 높은 순
        .thenComparingDouble(SignalGapDto::gapPct)              // 2순위: 기준가에 가까운 순
)
```

- `score=0` 종목들은 하단에 몰리고, 그 중에서는 `gapPct` 오름차순으로 BB 하단에 가장 가까운 종목이 상위 배치됨.
- 정렬 후 상위 `topN`개에 1부터 `rank` 부여.

---

### 1-6. 화면 표시 (`Strategy.jsx`)

| 열 | 표시 내용 | 색상 조건 |
|----|----------|----------|
| 순위 | `rank` | — |
| 종목명(현재가) | `stockName` + `currentPrice` + 전략 배지 | 전략별 고정색 |
| 매수기준가 | `threshold` + 상태 문자열 | 황색(조건 충족/돌파) / 청색(대기) |
| RSI(14) | `rsi` (`rsi-bollinger`만; 나머지 `'-'`) | 빨강(RSI < 30) / 기본 |
| 청산 기준 | `bbUpper`원 + "or RSI≥70" / "당일 청산" / "데드크로스 매도" | — |
| 진입점수 | `score`점 / `'-'`(score=0) | 빨강(≥80) / 황색(1~79) / 회색(0) |

**매수기준가 상태 문자열 규칙**

| 조건 | 표시 | 색상 |
|------|------|------|
| `rsi-bollinger` + BB 이탈 + RSI < 30 | `"2차 조건 충족"` | 황색 |
| `rsi-bollinger` + BB 이탈 + RSI ≥ 30 | `"1차 충족 (RSI 대기)"` | 황색 |
| `rsi-bollinger` + BB 위 | `-X.X%` | 청색 |
| `volatility-breakout` + `gapPct ≤ 0` | `"목표가 돌파"` | 황색 |
| `golden-cross` + `gapPct ≤ 0` | `"골든크로스 발생"` | 황색 |
| 공통(미달) | `-X.X%` | 청색 |

---

## 2. BUY 신호 랭킹 (EvalRanking)

전략 `run()` 실행 시 BUY 후보로 평가된 종목들의 점수·결과를 최대 30개 보존하여
마지막 실행의 의사결정 과정을 사후 확인할 수 있는 기능이다.

---

### 2-1. DTO 필드 설명

파일: `strategy-service/.../engine/StrategyEngine.java` — inner record `EvalRankEntry` (라인 147)

| 필드 | 타입 | 설명 |
|------|------|------|
| `rank` | `int` | 순위 (1부터) |
| `ticker` | `String` | 종목코드 |
| `stockName` | `String` | 종목명 |
| `strategyName` | `String` | 신호를 발생시킨 전략 이름 |
| `score` | `double` | 신호 강도 점수 |
| `reason` | `String` | 신호 발생 이유 설명 |
| `result` | `String` | 실행 결과: `"매수"` / `"한도초과"` / `"예산부족"` / `"이미보유"` / `"대기"` |
| `tradingMode` | `String` | 거래 모드: `"paper"` / `"real"` |

---

### 2-2. 백엔드 상태 변수

파일: `strategy-service/.../engine/StrategyEngine.java` (라인 62–64)

| 변수 | 타입 | 설명 |
|------|------|------|
| `lastBuyRankingByMode` | `ConcurrentHashMap<String, List<EvalRankEntry>>` | 모드별 마지막 랭킹 (`"paper"` / `"real"` 키) |
| `lastEvalAt` | `volatile LocalDateTime` | 마지막 전략 실행 완료 시각 |

---

### 2-3. 생성 흐름

```
전략 run() 메서드 실행 (스케줄러 또는 수동 즉시 실행)

  Phase 1 — 후보 수집
    for ticker in watchTickers:
        collectBuyCandidate() → SignalDto(score, reason, strategyName, ...)
        BuyCandidate(signal, candles, priceData) → buyQueue 추가

  Phase 2 — score 내림차순 정렬 후 매수 실행
    buyQueue.sort(score 내림차순)
    for candidate in buyQueue:
        maxPositions / 그룹 한도 초과 → candidateResultMap.put(ticker, "한도초과")
        예산 충분   → 주문 실행     → candidateResultMap.put(ticker, "매수")
        예산 부족   →               → candidateResultMap.put(ticker, "예산부족")

  Phase 3 — BUY 랭킹 저장
    buyQueue.stream().limit(30)
        .map(c → EvalRankEntry(rank, ticker, stockName, strategyName, score, reason,
                               candidateResultMap.getOrDefault(ticker, "대기"),
                               tradingMode))
    lastBuyRankingByMode.put(tradingMode, ranking)
    lastEvalAt = now (KST)
```

---

### 2-4. 랭킹 조회 API

- **엔드포인트**: `GET /api/strategy/eval-ranking`
- **응답**: `{ evaluatedAt, items: List<EvalRankEntry> }` — 최대 30개
- 현재 거래 모드(`paper` / `real`)에 해당하는 랭킹만 반환
  (`adminConfigStore.getTradingMode()` 기준)

---

### 2-5. 화면 표시 (`Strategy.jsx`)

각 항목은 카드 형태로 표시:

```
┌─────────────────────────────────────────────┐
│  #rank (청색)  stockName  ticker             │  score점 (빨강, 굵음)
│  reason (신호 발생 이유)                      │
│  [result 태그]  ("매수" / "한도초과" / ...)   │
└─────────────────────────────────────────────┘
```

**헤더**: 마지막 실행 시각 (`rankingEvalAt`)

**빈 목록 안내 메시지**

| 조건 | 표시 |
|------|------|
| `rankingEvalAt` 있음, items 없음 | "마지막 실행에서 BUY 신호가 발생한 종목이 없습니다." |
| `rankingEvalAt` 없음 | "아직 전략이 실행되지 않았습니다. 전략 즉시 실행 버튼을 누르거나..." |

---

## 3. 핵심 파일 경로

| 파일 | 역할 |
|------|------|
| `strategy-service/.../engine/StrategyEngine.java` | SignalGap + EvalRanking 계산 엔진 (라인 638~856) |
| `strategy-service/.../dto/SignalGapDto.java` | SignalGap DTO record |
| `strategy-service/.../controller/StrategyController.java` | REST 엔드포인트 (`/signal-gap`, `/signal-gap/refresh`, `/eval-ranking`) |
| `frontend/src/pages/Strategy.jsx` | 두 기능 UI 렌더링 |
| `frontend/src/api/stockApi.js` | API 호출 함수 (`getSignalGap`, `triggerSignalGapRefresh`, `getEvalRanking`) |

---

## 4. 점수 범위 요약

| 전략 | 최소 | 최대 | 비고 |
|------|------|------|------|
| rsi-bollinger (BB 이탈) | 51 | 150 | 베이스 50 + bbScore(50) + rsiScore(50) |
| rsi-bollinger (BB 접근, 20% 이내) | 0 | 50 | proximityScore(30) + rsiProxScore(20) |
| volatility-breakout | 0 | 100 | volScore(50) + proximity(50) |
| golden-cross | 0 | 100 | maScore(50) + volScore(50) |
