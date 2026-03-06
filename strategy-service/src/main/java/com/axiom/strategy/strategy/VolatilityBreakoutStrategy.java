package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 변동성 돌파 전략 (상승장 단기 매매).
 *
 * <p>목표가 = 오늘 시가 + (전일 고가 - 전일 저가) × K
 * <p>현재가 ≥ 목표가 → 매수 (당일 내 중복 매수 방지)
 * <p>매도는 ForceExitScheduler가 15:20에 강제 청산.
 */
@Slf4j
@Component
public class VolatilityBreakoutStrategy implements TradingStrategy {

    private static final double K = 0.5; // 변동성 계수 (0.3~0.7 범위, 0.5가 표준)

    /** 당일 매수 종목 추적 (ticker → 매수일). ForceExitScheduler에서도 참조. */
    private final Map<String, LocalDate> todayBought = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "volatility-breakout";
    }

    @Override
    public int minimumCandles() {
        return 3; // 전일 + 오늘(라이브)
    }

    @Override
    public SignalDto evaluate(String ticker, List<CandleDto> candles) {
        // candles 마지막 = 오늘 라이브 캔들 (StrategyEngine에서 주입)
        // candles 마지막-1 = 전일 캔들
        CandleDto today     = candles.get(candles.size() - 1);
        CandleDto yesterday = candles.get(candles.size() - 2);

        BigDecimal range       = yesterday.getHighPrice().subtract(yesterday.getLowPrice());
        BigDecimal targetPrice = today.getOpenPrice().add(range.multiply(BigDecimal.valueOf(K)));
        BigDecimal currentPrice = today.getClosePrice();

        log.debug("[VolBreakout] {} | 목표가: {} | 현재가: {} | Range: {}",
                ticker, targetPrice, currentPrice, range);

        // 당일 이미 매수한 종목 스킵
        LocalDate todayDate = today.getTradeDate();
        if (todayBought.containsKey(ticker) && todayBought.get(ticker).equals(todayDate)) {
            return hold(ticker, currentPrice, "오늘 이미 매수됨");
        }

        if (currentPrice.compareTo(targetPrice) >= 0) {
            return SignalDto.builder()
                    .action(SignalDto.Action.BUY)
                    .ticker(ticker)
                    .price(currentPrice)
                    .strategyName(getName())
                    .reason(String.format("변동성 돌파 — 현재(%.0f) ≥ 목표(%.0f) = 시가(%.0f) + Range(%.0f)×%.1f",
                            currentPrice.doubleValue(), targetPrice.doubleValue(),
                            today.getOpenPrice().doubleValue(), range.doubleValue(), K))
                    .signalAt(LocalDateTime.now())
                    .build();
        }

        return hold(ticker, currentPrice,
                String.format("목표가 미달성 — 현재(%.0f) < 목표(%.0f)", currentPrice.doubleValue(), targetPrice.doubleValue()));
    }

    /**
     * 오늘 변동성 돌파로 매수한 종목 목록 반환.
     * ForceExitScheduler에서 15:20 강제 청산 시 사용.
     */
    public Map<String, LocalDate> getTodayBought() {
        return todayBought;
    }

    /**
     * 주문 체결 확정 후 호출. evaluate()가 아닌 실제 매수 성공 시점에 등록.
     * StrategyEngine에서 호출.
     */
    public void markBought(String ticker) {
        todayBought.put(ticker, LocalDate.now());
    }

    /**
     * 서비스 재시작 시 todayBought 복구.
     * order-service 주문 이력에서 오늘 volatility-breakout으로 FILLED된 BUY를 찾아 등록.
     * MarketStateScheduler @PostConstruct에서 호출.
     */
    public void restoreFromOrders(List<com.axiom.strategy.dto.OrderSummaryDto> orders) {
        LocalDate today = LocalDate.now();
        orders.stream()
                .filter(o -> "BUY".equals(o.getOrderType()))
                .filter(o -> "FILLED".equals(o.getStatus()))
                .filter(o -> "volatility-breakout".equals(o.getStrategyName()))
                .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().toLocalDate().equals(today))
                .collect(Collectors.toMap(
                        com.axiom.strategy.dto.OrderSummaryDto::getTicker,
                        o -> today,
                        (a, b) -> a))
                .forEach(todayBought::putIfAbsent);
    }

    private SignalDto hold(String ticker, BigDecimal price, String reason) {
        return SignalDto.builder()
                .action(SignalDto.Action.HOLD)
                .ticker(ticker)
                .price(price)
                .strategyName(getName())
                .reason(reason)
                .signalAt(LocalDateTime.now())
                .build();
    }
}
