package com.axiom.strategy.service;

import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.util.TradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final OrderClient orderClient;
    private final SlackNotifier slackNotifier;

    /** ticker → 매수일 (메모리, 재시작 시 초기화) */
    private final Map<String, LocalDate> buyDates = new ConcurrentHashMap<>();

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
        int maxDays  = config.getMaxHoldingDays();

        if (elapsed >= maxDays) {
            log.warn("[TimeCut] 타임 컷 발동 — {} | 매수일: {} | 경과 거래일: {}일 ≥ {}일",
                    ticker, buyDate, elapsed, maxDays);
            executeSell(ticker, currentPrice, positions, elapsed, maxDays);
            buyDates.remove(ticker);
        }
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
                            .build();

                    boolean success = orderClient.sell(sellOrder);
                    log.info("[TimeCut] 강제 청산 — ticker: {}, qty: {}, success: {}",
                            ticker, position.getQuantity(), success);

                    String stockLabel = position.getStockName() != null
                            ? position.getStockName() + " (" + ticker + ")" : ticker;
                    slackNotifier.sendError(String.format(
                            "⏱️ [타임 컷] %s — %d거래일 경과 (기준: %d일) → 강제 청산 %,.0f원 (%s)",
                            stockLabel, elapsed, maxDays, currentPrice, success ? "성공" : "실패"));
                });
    }
}
