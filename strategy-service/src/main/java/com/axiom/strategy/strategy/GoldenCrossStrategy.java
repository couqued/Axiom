package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 이동평균 골든크로스 / 데드크로스 전략.
 *
 * <p>골든크로스: 단기 MA가 장기 MA를 아래에서 위로 돌파 → 매수
 * <p>데드크로스: 단기 MA가 장기 MA를 위에서 아래로 돌파 → 매도
 *
 * <p>기본값: 단기 MA5, 장기 MA20
 */
@Slf4j
@Component
public class GoldenCrossStrategy implements TradingStrategy {

    private static final int SHORT_PERIOD = 5;
    private static final int LONG_PERIOD  = 20;

    @Override
    public String getName() {
        return "golden-cross";
    }

    @Override
    public int minimumCandles() {
        // 전일 MA 계산을 위해 LONG_PERIOD + 1일치 필요
        return LONG_PERIOD + 1;
    }

    @Override
    public SignalDto evaluate(String ticker, List<CandleDto> candles) {
        // candles는 날짜 오름차순 (oldest → newest)
        int size = candles.size();

        // 당일 기준 MA 계산
        BigDecimal ma5Curr  = calcMA(candles, size - 1, SHORT_PERIOD);
        BigDecimal ma20Curr = calcMA(candles, size - 1, LONG_PERIOD);

        // 전일 기준 MA 계산
        BigDecimal ma5Prev  = calcMA(candles, size - 2, SHORT_PERIOD);
        BigDecimal ma20Prev = calcMA(candles, size - 2, LONG_PERIOD);

        BigDecimal currentPrice = candles.get(size - 1).getClosePrice();

        log.debug("[GoldenCross] {} | MA5: {} → {} | MA20: {} → {}",
                ticker, ma5Prev, ma5Curr, ma20Prev, ma20Curr);

        // 골든크로스: 전일 MA5 ≤ MA20, 당일 MA5 > MA20
        if (ma5Prev.compareTo(ma20Prev) <= 0 && ma5Curr.compareTo(ma20Curr) > 0) {
            return SignalDto.builder()
                    .action(SignalDto.Action.BUY)
                    .ticker(ticker)
                    .price(currentPrice)
                    .strategyName(getName())
                    .reason(String.format("골든크로스 — MA%d(%.0f) > MA%d(%.0f)",
                            SHORT_PERIOD, ma5Curr, LONG_PERIOD, ma20Curr))
                    .signalAt(LocalDateTime.now())
                    .build();
        }

        // 데드크로스: 전일 MA5 ≥ MA20, 당일 MA5 < MA20
        if (ma5Prev.compareTo(ma20Prev) >= 0 && ma5Curr.compareTo(ma20Curr) < 0) {
            return SignalDto.builder()
                    .action(SignalDto.Action.SELL)
                    .ticker(ticker)
                    .price(currentPrice)
                    .strategyName(getName())
                    .reason(String.format("데드크로스 — MA%d(%.0f) < MA%d(%.0f)",
                            SHORT_PERIOD, ma5Curr, LONG_PERIOD, ma20Curr))
                    .signalAt(LocalDateTime.now())
                    .build();
        }

        return SignalDto.builder()
                .action(SignalDto.Action.HOLD)
                .ticker(ticker)
                .price(currentPrice)
                .strategyName(getName())
                .reason(String.format("관망 — MA%d(%.0f) vs MA%d(%.0f)",
                        SHORT_PERIOD, ma5Curr, LONG_PERIOD, ma20Curr))
                .signalAt(LocalDateTime.now())
                .build();
    }

    /**
     * endIndex 기준으로 period일 단순이동평균(SMA)을 계산한다.
     *
     * @param candles  날짜 오름차순 캔들 목록
     * @param endIndex 기준 인덱스 (포함)
     * @param period   기간
     */
    private BigDecimal calcMA(List<CandleDto> candles, int endIndex, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            sum = sum.add(candles.get(i).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
    }
}
