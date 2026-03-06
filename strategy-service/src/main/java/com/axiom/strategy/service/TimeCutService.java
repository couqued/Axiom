package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.OrderSummaryDto;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.util.TradingCalendar;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.axiom.strategy.admin.TimeCutStatusDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 타임 컷 서비스.
 *
 * <p>RSI+볼린저 전략으로 매수한 뒤, 설정된 거래일 수(기본 3일) 이내에
 * 매도 조건이 충족되지 않으면 기계적으로 손절한다.
 *
 * <p>횡보장이 하락장으로 전환될 때 계좌 손실을 제한하기 위한 안전장치.
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

    /** ticker → 매수일 (메모리, 재시작 시 order 이력에서 복구) */
    private final Map<String, LocalDate> buyDates = new ConcurrentHashMap<>();

    /**
     * 서비스 재시작 후 order-service 이력을 조회하여 buyDates 복구.
     * 현재 보유 중인 종목 중 적용 전략(rsi-bollinger 등)으로 매수된 것만 복구.
     */
    @PostConstruct
    public void initFromOrders() {
        StrategyConfig.TimeCutConfig config = strategyConfig.getTimeCut();
        if (!config.isEnabled()) return;

        try {
            List<PortfolioItemDto> positions = portfolioClient.getPositions();
            if (positions.isEmpty()) return;

            Set<String> heldTickers = positions.stream()
                    .map(PortfolioItemDto::getTicker)
                    .collect(Collectors.toSet());

            List<OrderSummaryDto> orders = orderClient.getFilledOrders();

            for (String ticker : heldTickers) {
                orders.stream()
                        .filter(o -> ticker.equals(o.getTicker()))
                        .filter(o -> "BUY".equals(o.getOrderType()))
                        .filter(o -> "FILLED".equals(o.getStatus()))
                        .filter(o -> config.getApplicableStrategies().contains(o.getStrategyName()))
                        .max(Comparator.comparing(OrderSummaryDto::getCreatedAt))
                        .ifPresent(o -> {
                            LocalDate buyDate = o.getCreatedAt().toLocalDate();
                            buyDates.putIfAbsent(ticker, buyDate);
                            log.info("[TimeCut] 재시작 복구 — ticker: {}, buyDate: {}", ticker, buyDate);
                        });
            }
            if (!buyDates.isEmpty()) {
                log.info("[TimeCut] 재시작 복구 완료 — {}개 종목", buyDates.size());
            }
        } catch (Exception e) {
            log.warn("[TimeCut] 재시작 복구 실패: {}", e.getMessage());
        }
    }

    /**
     * RSI+볼린저 전략 BUY 신호 발생 시 매수일을 기록한다.
     * StrategyEngine에서 호출.
     *
     * @param ticker      종목 코드
     * @param strategyName 신호를 발생시킨 전략 이름
     */
    public void recordBuy(String ticker, String strategyName) {
        StrategyConfig.TimeCutConfig config = strategyConfig.getTimeCut();
        if (!config.isEnabled()) return;
        if (!config.getApplicableStrategies().contains(strategyName)) return;

        buyDates.put(ticker, LocalDate.now());
        log.info("[TimeCut] 매수 기록 — ticker: {}, strategy: {}, date: {}",
                ticker, strategyName, LocalDate.now());
    }

    /**
     * 매도(SELL) 또는 포지션 청산 시 타임 컷 기록을 제거한다.
     */
    public void clearBuy(String ticker) {
        buyDates.remove(ticker);
    }

    /**
     * 타임 컷 조건을 체크하고 조건 충족 시 강제 청산한다.
     * StrategyEngine에서 매 5분마다 호출.
     *
     * @param ticker       종목 코드
     * @param currentPrice 현재가
     * @param positions    현재 보유 포지션 목록
     */
    public void checkAndCut(String ticker, BigDecimal currentPrice, List<PortfolioItemDto> positions) {
        StrategyConfig.TimeCutConfig config = strategyConfig.getTimeCut();
        if (!config.isEnabled()) return;

        LocalDate buyDate = buyDates.get(ticker);
        if (buyDate == null) return;

        // 보유 중인지 확인
        boolean isHolding = positions.stream().anyMatch(p -> p.getTicker().equals(ticker));
        if (!isHolding) {
            buyDates.remove(ticker);
            return;
        }

        int elapsed = TradingCalendar.tradingDaysBetween(buyDate, LocalDate.now());
        int maxDays  = adminConfigStore.getTimeCutDays();

        if (elapsed >= maxDays) {
            log.warn("[TimeCut] 타임 컷 발동 — {} | 매수일: {} | 경과 거래일: {}일 ≥ {}일",
                    ticker, buyDate, elapsed, maxDays);
            executeSell(ticker, currentPrice, positions, elapsed, maxDays);
            buyDates.remove(ticker);
        }
    }

    public Map<String, TimeCutStatusDto> getStatus() {
        int maxDays = adminConfigStore.getTimeCutDays();
        Map<String, TimeCutStatusDto> result = new HashMap<>();
        buyDates.forEach((ticker, buyDate) -> {
            int elapsed   = TradingCalendar.tradingDaysBetween(buyDate, LocalDate.now());
            int remaining = Math.max(0, maxDays - elapsed);
            result.put(ticker, new TimeCutStatusDto(buyDate, elapsed, remaining));
        });
        return result;
    }

    private void executeSell(String ticker, BigDecimal currentPrice,
                             List<PortfolioItemDto> positions, int elapsed, int maxDays) {
        positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst()
                .ifPresent(position -> {
                    OrderRequest sellOrder = OrderRequest.builder()
                            .ticker(ticker)
                            .quantity(position.getQuantity())
                            .price(currentPrice)
                            .closeReason("TIME_CUT")
                            .build();

                    OrderResult result = orderClient.sell(sellOrder);
                    log.info("[TimeCut] 타임컷 청산 — ticker: {}, qty: {}, success: {}",
                            ticker, position.getQuantity(), result.success());

                    slackNotifier.sendTimeCut(
                            ticker, position.getStockName(), currentPrice, elapsed, maxDays, result.success());
                });
    }
}
