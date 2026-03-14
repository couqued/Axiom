package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.admin.TimeCutStatusDto;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.util.TradingCalendar;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 타임 컷 서비스.
 *
 * <p>RSI+볼린저 전략으로 매수한 뒤, 설정된 거래일 수(기본 3일) 이내에
 * 매도 조건이 충족되지 않으면 기계적으로 손절한다.
 *
 * <p>buyDates 맵 키 형식: "{tradingMode}:{ticker}" (e.g. "paper:005930")
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeCutService {

    private final StrategyConfig strategyConfig;
    private final AdminConfigStore adminConfigStore;
    private final OrderClient orderClient;
    private final PortfolioClient portfolioClient;
    private final SlackNotifier slackNotifier;
    private final StrategyStateStore stateStore;

    /** "{tradingMode}:{ticker}" → 매수일 */
    private final Map<String, LocalDate> buyDates = new ConcurrentHashMap<>();

    @PostConstruct
    public void initFromOrders() {
        if (!strategyConfig.getTimeCut().isEnabled()) return;
        for (String mode : List.of("paper", "real")) {
            try {
                Map<String, LocalDate> fromDb = stateStore.loadAllBuyDates(mode);
                fromDb.forEach((ticker, date) -> buyDates.put(key(mode, ticker), date));
                log.info("[TimeCut] DB에서 buyDates 복구 — mode={}, {}개", mode, fromDb.size());
            } catch (Exception e) {
                log.warn("[TimeCut] DB buyDates 복구 실패 (mode={}): {}", mode, e.getMessage());
            }
        }
    }

    public void recordBuy(String ticker, String strategyName) {
        StrategyConfig.TimeCutConfig config = strategyConfig.getTimeCut();
        if (!config.isEnabled()) return;
        if (!config.getApplicableStrategies().contains(strategyName)) return;

        String mode = adminConfigStore.getTradingMode();
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        buyDates.put(key(mode, ticker), today);
        stateStore.saveBuyDate(ticker, today, mode);
        log.info("[TimeCut][{}] 매수 기록 — ticker: {}, strategy: {}, date: {}",
                mode, ticker, strategyName, today);
    }

    public void clearBuy(String ticker) {
        String mode = adminConfigStore.getTradingMode();
        buyDates.remove(key(mode, ticker));
        stateStore.removeBuyDate(ticker, mode);
    }

    public void checkAndCut(String ticker, BigDecimal currentPrice, List<PortfolioItemDto> positions, BigDecimal ma5) {
        StrategyConfig.TimeCutConfig config = strategyConfig.getTimeCut();
        if (!config.isEnabled()) return;

        String mode = adminConfigStore.getTradingMode();
        String mapKey = key(mode, ticker);
        LocalDate buyDate = buyDates.get(mapKey);
        if (buyDate == null) return;

        Optional<PortfolioItemDto> positionOpt = positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst();

        if (positionOpt.isEmpty()) {
            buyDates.remove(mapKey);
            stateStore.removeBuyDate(ticker, mode);
            return;
        }

        PortfolioItemDto position = positionOpt.get();
        int elapsed = TradingCalendar.tradingDaysBetween(buyDate, LocalDate.now(TradingCalendar.KST));
        int maxDays  = adminConfigStore.getTimeCutDays();

        if (elapsed >= maxDays) {
            // ── 지능형 타임컷 조건 체크 ──
            // 1. 수익률 체크 (1.5% 미만인 경우)
            BigDecimal avgPrice = position.getAvgPrice();
            double profitRate = currentPrice.subtract(avgPrice)
                    .divide(avgPrice, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

            // 2. MA5 체크 (현재가 < MA5 인 경우)
            boolean isBelowMa5 = ma5 != null && currentPrice.compareTo(ma5) < 0;

            boolean shouldCut = profitRate < 1.5 || isBelowMa5;

            if (shouldCut) {
                String reason = isBelowMa5 ? "MA5 하회" : String.format("수익률 저조(%.1f%%)", profitRate);
                log.warn("[TimeCut][{}] 타임 컷 발동 — {} | 사유: {} | 경과: {}일", mode, ticker, reason, elapsed);
                boolean sold = executeSell(ticker, currentPrice, positions, elapsed, maxDays);
                if (sold) {
                    buyDates.remove(mapKey);
                    stateStore.removeBuyDate(ticker, mode);
                }
            } else {
                log.info("[TimeCut][{}] 조건 만족으로 홀딩 연장 — {} | 수익률: %.1f%%, MA5: {}", 
                        mode, ticker, profitRate, ma5);
            }
        }
    }

    public Map<String, TimeCutStatusDto> getStatus() {
        String mode = adminConfigStore.getTradingMode();
        String prefix = mode + ":";
        int maxDays = adminConfigStore.getTimeCutDays();
        Map<String, TimeCutStatusDto> result = new HashMap<>();
        buyDates.forEach((mapKey, buyDate) -> {
            if (!mapKey.startsWith(prefix)) return;
            String ticker = mapKey.substring(prefix.length());
            int elapsed   = TradingCalendar.tradingDaysBetween(buyDate, LocalDate.now(TradingCalendar.KST));
            int remaining = Math.max(0, maxDays - elapsed);
            result.put(ticker, new TimeCutStatusDto(buyDate, elapsed, remaining));
        });
        return result;
    }

    private boolean executeSell(String ticker, BigDecimal currentPrice,
                                List<PortfolioItemDto> positions, int elapsed, int maxDays) {
        return positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst()
                .map(position -> {
                    OrderRequest sellOrder = OrderRequest.builder()
                            .ticker(ticker)
                            .quantity(position.getQuantity())
                            .price(currentPrice)
                            .closeReason("TIME_CUT")
                            .build();
                    OrderResult result = orderClient.sellWithRetry(sellOrder);
                    log.info("[TimeCut] 타임컷 청산 — ticker: {}, qty: {}, success: {}",
                            ticker, position.getQuantity(), result.success());
                    slackNotifier.sendTimeCut(
                            ticker, position.getStockName(), currentPrice, elapsed, maxDays, result.success());
                    return result.success();
                })
                .orElse(false);
    }

    private String key(String mode, String ticker) {
        return mode + ":" + ticker;
    }
}
