package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * RSI + 볼린저밴드 결합 전략 (횡보장 단기~스윙 매매).
 *
 * <h3>매수 조건 (AND)</h3>
 * <ul>
 *   <li>RSI(14) &lt; 30 (과매도)</li>
 *   <li>현재 종가 &lt; 볼린저밴드 하단 (MA20 - 2σ)</li>
 * </ul>
 *
 * <h3>매도 조건 (OR)</h3>
 * <ul>
 *   <li>RSI(14) &gt; 70 (과매수)</li>
 *   <li>현재 종가 &gt; 볼린저밴드 중심선 (MA20) — 평균 회귀 완료</li>
 * </ul>
 *
 * <p>두 조건을 AND로 결합하여 오신호를 줄이고, 매도는 OR로 빠른 청산을 유도.
 */
@Slf4j
@Component
public class RsiBollingerStrategy implements TradingStrategy {

    private static final int RSI_PERIOD    = 14;
    private static final int BB_PERIOD     = 20;  // 볼린저밴드 기간
    private static final double BB_MULT    = 2.0; // 표준편차 배수
    private static final double RSI_OVERSOLD  = 30.0;
    private static final double RSI_OVERBOUGHT = 70.0;

    @Override
    public String getName() {
        return "rsi-bollinger";
    }

    @Override
    public int minimumCandles() {
        // RSI는 RSI_PERIOD+1, 볼린저는 BB_PERIOD+1(라이브 캔들) → 최대값
        return BB_PERIOD + 1;
    }

    @Override
    public SignalDto evaluate(String ticker, List<CandleDto> candles) {
        int size = candles.size();
        BigDecimal currentPrice = candles.get(size - 1).getClosePrice();

        double rsi = calcRsi(candles, size - 1);
        BollingerBands bb = calcBollingerBands(candles, size - 1);

        log.debug("[RsiBollinger] {} | RSI: {} | 현재가: {} | 하단: {} | 중심: {} | 상단: {}",
                ticker, String.format("%.1f", rsi), currentPrice, bb.lower, bb.middle, bb.upper);

        // 매수: RSI 과매도 AND 볼린저 하단밴드 이탈
        if (rsi < RSI_OVERSOLD && currentPrice.compareTo(bb.lower) < 0) {
            return SignalDto.builder()
                    .action(SignalDto.Action.BUY)
                    .ticker(ticker)
                    .price(currentPrice)
                    .strategyName(getName())
                    .reason(String.format("과매도 진입 — RSI(%.1f) < %.0f & 종가(%.0f) < 하단밴드(%.0f)",
                            rsi, RSI_OVERSOLD, currentPrice.doubleValue(), bb.lower.doubleValue()))
                    .signalAt(LocalDateTime.now())
                    .build();
        }

        // 매도: RSI 과매수 OR 볼린저 중심선(MA20) 도달 (평균 회귀 완료)
        if (rsi > RSI_OVERBOUGHT || currentPrice.compareTo(bb.middle) >= 0) {
            String reason = rsi > RSI_OVERBOUGHT
                    ? String.format("과매수 청산 — RSI(%.1f) > %.0f", rsi, RSI_OVERBOUGHT)
                    : String.format("평균 회귀 청산 — 종가(%.0f) ≥ 중심선(%.0f)", currentPrice.doubleValue(), bb.middle.doubleValue());
            return SignalDto.builder()
                    .action(SignalDto.Action.SELL)
                    .ticker(ticker)
                    .price(currentPrice)
                    .strategyName(getName())
                    .reason(reason)
                    .signalAt(LocalDateTime.now())
                    .build();
        }

        return SignalDto.builder()
                .action(SignalDto.Action.HOLD)
                .ticker(ticker)
                .price(currentPrice)
                .strategyName(getName())
                .reason(String.format("관망 — RSI(%.1f) | 종가(%.0f) vs 하단(%.0f)~중심(%.0f)",
                        rsi, currentPrice.doubleValue(), bb.lower.doubleValue(), bb.middle.doubleValue()))
                .signalAt(LocalDateTime.now())
                .build();
    }

    // ── RSI 계산 ─────────────────────────────────────────────────────────────

    /**
     * Wilder's Smoothed RSI(14).
     * endIndex 기준으로 최근 RSI_PERIOD+1개 캔들의 종가 변화를 이용.
     */
    private double calcRsi(List<CandleDto> candles, int endIndex) {
        int start = endIndex - RSI_PERIOD;
        if (start < 0) return 50.0; // 데이터 부족 시 중립값

        double avgGain = 0, avgLoss = 0;

        // 첫 RSI_PERIOD개의 단순 평균으로 초기값 설정
        for (int i = start + 1; i <= start + RSI_PERIOD; i++) {
            double change = candles.get(i).getClosePrice()
                    .subtract(candles.get(i - 1).getClosePrice())
                    .doubleValue();
            if (change > 0) avgGain += change;
            else             avgLoss += Math.abs(change);
        }
        avgGain /= RSI_PERIOD;
        avgLoss /= RSI_PERIOD;

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    // ── 볼린저밴드 계산 ───────────────────────────────────────────────────────

    private BollingerBands calcBollingerBands(List<CandleDto> candles, int endIndex) {
        int start = endIndex - BB_PERIOD + 1;

        // 중심선 (MA20)
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i <= endIndex; i++) {
            sum = sum.add(candles.get(i).getClosePrice());
        }
        BigDecimal middle = sum.divide(BigDecimal.valueOf(BB_PERIOD), 2, RoundingMode.HALF_UP);

        // 표준편차
        BigDecimal variance = BigDecimal.ZERO;
        for (int i = start; i <= endIndex; i++) {
            BigDecimal diff = candles.get(i).getClosePrice().subtract(middle);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(BigDecimal.valueOf(BB_PERIOD), 10, RoundingMode.HALF_UP);
        BigDecimal stdDev = variance.sqrt(new MathContext(10, RoundingMode.HALF_UP));

        BigDecimal band   = stdDev.multiply(BigDecimal.valueOf(BB_MULT));
        BigDecimal upper  = middle.add(band).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lower  = middle.subtract(band).setScale(2, RoundingMode.HALF_UP);

        return new BollingerBands(upper, middle, lower);
    }

    private record BollingerBands(BigDecimal upper, BigDecimal middle, BigDecimal lower) {}
}
