package com.axiom.strategy.fixture;

import com.axiom.strategy.dto.CandleDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 전략 단위 테스트에서 사용하는 캔들 데이터 생성 헬퍼.
 * 각 시나리오별로 전략의 신호 조건을 충족하는 캔들 목록을 반환한다.
 */
public class CandleFixture {

    /**
     * 골든크로스 시나리오: 21개 캔들 (day 20까지 같은 가격, day 21에 급등)
     * - 전일(day 20): MA5 = MA20 = 100 (equal → ≤ 조건 충족)
     * - 당일(day 21): MA5 = 110, MA20 = 102.5 → MA5 > MA20 (골든크로스)
     */
    public static List<CandleDto> goldenCrossCandles(String ticker) {
        List<CandleDto> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        // indices 0-19: price=100, index 20: price=150
        for (int i = 0; i < 20; i++) {
            candles.add(candle(ticker, date.plusDays(i), 100));
        }
        candles.add(candle(ticker, date.plusDays(20), 150));
        return candles;
    }

    /**
     * 데드크로스 시나리오: 21개 캔들 (day 20까지 같은 가격, day 21에 급락)
     * - 전일(day 20): MA5 = MA20 = 100 (equal → ≥ 조건 충족)
     * - 당일(day 21): MA5 = 90, MA20 = 97.5 → MA5 < MA20 (데드크로스)
     */
    public static List<CandleDto> deadCrossCandles(String ticker) {
        List<CandleDto> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 20; i++) {
            candles.add(candle(ticker, date.plusDays(i), 100));
        }
        candles.add(candle(ticker, date.plusDays(20), 50));
        return candles;
    }

    /**
     * 관망(HOLD) 시나리오: 21개 캔들 (MA5 < MA20 유지, 크로스 없음)
     * - indices 0-9: price=100, indices 10-20: price=95
     * - 전일: MA5=95, MA20=97.5 (MA5 < MA20)
     * - 당일: MA5=95, MA20=97.25 (MA5 < MA20) → 크로스 없음
     */
    public static List<CandleDto> holdCandles(String ticker) {
        List<CandleDto> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 10; i++) {
            candles.add(candle(ticker, date.plusDays(i), 100));
        }
        for (int i = 10; i < 21; i++) {
            candles.add(candle(ticker, date.plusDays(i), 95));
        }
        return candles;
    }

    /**
     * RSI-볼린저 과매도 매수 시나리오:
     * - indices 0-17: price=100, indices 18-19: price=95, index 20: price=50
     * - RSI = 0 (전 구간 하락) < 30 ✓
     * - MA20 ≈ 97, stdDev ≈ 10.9, lower ≈ 75.2 → close(50) < lower ✓
     */
    public static List<CandleDto> rsiBollingerOversoldCandles(String ticker) {
        List<CandleDto> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 18; i++) {
            candles.add(candle(ticker, date.plusDays(i), 100));
        }
        for (int i = 18; i < 20; i++) {
            candles.add(candle(ticker, date.plusDays(i), 95));
        }
        candles.add(candle(ticker, date.plusDays(20), 50));
        return candles;
    }

    /**
     * RSI-볼린저 과매수 매도 시나리오:
     * - indices 0-14: price=70, indices 15-20: price=110
     * - RSI = 100 (avgLoss=0) > 70 ✓
     * - close(110) >= MA20(82) ✓
     */
    public static List<CandleDto> rsiBollingerOverboughtCandles(String ticker) {
        List<CandleDto> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 15; i++) {
            candles.add(candle(ticker, date.plusDays(i), 70));
        }
        for (int i = 15; i < 21; i++) {
            candles.add(candle(ticker, date.plusDays(i), 110));
        }
        return candles;
    }

    /**
     * RSI-볼린저 관망 시나리오:
     * - 교대 가격(100, 101) 20회 후 index 20: price=95
     * - RSI ≈ 50 (30~70 범위)
     * - close < lower 이지만 RSI ≥ 30이라 BUY 조건 미충족 → HOLD
     */
    public static List<CandleDto> rsiBollingerHoldCandles(String ticker) {
        List<CandleDto> candles = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 20; i++) {
            int price = (i % 2 == 0) ? 100 : 101;
            candles.add(candle(ticker, date.plusDays(i), price));
        }
        candles.add(candle(ticker, date.plusDays(20), 95));
        return candles;
    }

    /**
     * 변동성 돌파 시나리오 (3개 캔들): 목표가 돌파 → BUY
     * - 어제: high=110, low=90, range=20
     * - 오늘: open=105, close=120 → target=115, close >= target ✓
     * 당일 캔들의 tradeDate = LocalDate.now() (markBought()와 날짜 일치)
     */
    public static List<CandleDto> volatilityBreakoutBuyCandles(String ticker) {
        LocalDate today = LocalDate.now();
        return List.of(
            candle(ticker, today.minusDays(2), 100),
            CandleDto.builder()
                .tradeDate(today.minusDays(1))
                .openPrice(BigDecimal.valueOf(100))
                .highPrice(BigDecimal.valueOf(110))
                .lowPrice(BigDecimal.valueOf(90))
                .closePrice(BigDecimal.valueOf(100))
                .volume(1_000_000L)
                .build(),
            // 당일: target = 105 + 20*0.5 = 115, close=120 >= 115 → BUY
            CandleDto.builder()
                .tradeDate(today)
                .openPrice(BigDecimal.valueOf(105))
                .highPrice(BigDecimal.valueOf(125))
                .lowPrice(BigDecimal.valueOf(105))
                .closePrice(BigDecimal.valueOf(120))
                .volume(2_000_000L)
                .build()
        );
    }

    /**
     * 변동성 돌파 시나리오 (3개 캔들): 목표가 미달 → HOLD
     * - 어제: high=110, low=90, range=20
     * - 오늘: open=105, close=108 → target=115, close < target ✓
     */
    public static List<CandleDto> volatilityBreakoutHoldCandles(String ticker) {
        LocalDate today = LocalDate.now();
        return List.of(
            candle(ticker, today.minusDays(2), 100),
            CandleDto.builder()
                .tradeDate(today.minusDays(1))
                .openPrice(BigDecimal.valueOf(100))
                .highPrice(BigDecimal.valueOf(110))
                .lowPrice(BigDecimal.valueOf(90))
                .closePrice(BigDecimal.valueOf(100))
                .volume(1_000_000L)
                .build(),
            // target = 105 + 20*0.5 = 115, close=108 < 115 → HOLD
            CandleDto.builder()
                .tradeDate(today)
                .openPrice(BigDecimal.valueOf(105))
                .highPrice(BigDecimal.valueOf(112))
                .lowPrice(BigDecimal.valueOf(105))
                .closePrice(BigDecimal.valueOf(108))
                .volume(800_000L)
                .build()
        );
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static CandleDto candle(String ticker, LocalDate date, int price) {
        return CandleDto.builder()
                .tradeDate(date)
                .openPrice(BigDecimal.valueOf(price))
                .highPrice(BigDecimal.valueOf(price + 500))
                .lowPrice(BigDecimal.valueOf(price - 500))
                .closePrice(BigDecimal.valueOf(price))
                .volume(1_000_000L)
                .build();
    }
}
