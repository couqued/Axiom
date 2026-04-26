package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.dto.StockPriceDto;
import com.axiom.strategy.dto.TradePlanDto;
import com.axiom.strategy.ml.MlPerformanceService;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.util.TradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ML 전략 포지션 전용 청산 서비스.
 *
 * <p>StrategyEngine 5분 사이클마다 {@link #check()} 호출.
 * 활성 ML 포지션에 대해 TP / SL / maxDays 세 가지 기준을 체크해
 * 먼저 발생한 조건으로 시장가 매도.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlExitService {

    private final AdminConfigStore adminConfigStore;
    private final MlPositionStore planStore;
    private final PortfolioClient portfolioClient;
    private final MarketClient marketClient;
    private final OrderClient orderClient;
    private final SlackNotifier slackNotifier;
    private final DailySellBlockService dailySellBlockService;
    private final TrailingStopService trailingStopService;
    private final MlDeferTracker deferTracker;
    private final MlPerformanceService mlPerformanceService;

    /**
     * 5분 주기로 ML 활성 포지션 검사.
     * @param positions 이미 조회된 포지션 목록 (중복 호출 방지)
     */
    public void check(List<PortfolioItemDto> positions) {
        if (adminConfigStore.isMlSellPaused()) {
            log.debug("[MlExit] ML 매도 중지 상태 — 스킵");
            return;
        }
        LocalDate today = LocalDate.now(TradingCalendar.KST);

        for (Map.Entry<String, MlPositionStore.ActivePlan> e : planStore.activeEntries()) {
            String ticker = e.getKey();
            MlPositionStore.ActivePlan ap = e.getValue();

            Optional<PortfolioItemDto> positionOpt = positions.stream()
                    .filter(p -> p.getTicker().equals(ticker))
                    .findFirst();
            if (positionOpt.isEmpty()) {
                log.debug("[MlExit] 포지션 없음 — ML_PLAN 정리 ticker: {}", ticker);
                planStore.clear(ticker);
                continue;
            }
            PortfolioItemDto position = positionOpt.get();

            BigDecimal current = getCurrentPrice(ticker);
            if (current == null || current.compareTo(BigDecimal.ZERO) <= 0) continue;

            int elapsed = TradingCalendar.tradingDaysBetween(ap.entryDate(), today);
            TradePlanDto plan = ap.plan();

            String exitTag = null;
            if (plan.takeProfitPrice() != null && current.compareTo(plan.takeProfitPrice()) >= 0) {
                exitTag = "ML TP";
            } else if (plan.stopLossPrice() != null && current.compareTo(plan.stopLossPrice()) <= 0) {
                exitTag = "ML SL";
            } else if (elapsed >= plan.maxDays()) {
                exitTag = "ML 최대보유";
            }

            if (exitTag != null) {
                exit(ticker, exitTag, current, position, ap, elapsed);
            }
        }
    }

    private void exit(String ticker, String tag, BigDecimal current,
                      PortfolioItemDto position, MlPositionStore.ActivePlan ap, int daysHeld) {
        OrderRequest req = OrderRequest.builder()
                .ticker(ticker)
                .stockName(position.getStockName())
                .quantity(position.getQuantity())
                .price(null) // 시장가
                .strategyName("ml-prediction")
                .closeReason("ML_" + tag.replace(" ", "_"))
                .build();

        OrderResult result = orderClient.sellWithRetry(req);

        slackNotifier.sendMlExit(
                ticker, position.getStockName(), tag,
                ap.actualEntryPrice(), current,
                daysHeld,
                ap.plan().takeProfitPrice(), ap.plan().stopLossPrice(), ap.plan().maxDays(),
                result.success());

        if (result.success()) {
            mlPerformanceService.recordTradeResult(
                    ticker, position.getStockName(),
                    ap.actualEntryPrice(), current,
                    ap.plan().confidence(), ap.plan().takeProfitPrice(),
                    tag, ap.entryDate());
            planStore.clear(ticker);
            trailingStopService.removePeak(ticker);
            dailySellBlockService.markSoldToday(ticker);
            deferTracker.clear(ticker);
            log.info("[MlExit] {} 청산 완료 — ticker: {}, current: {}", tag, ticker, current);
        } else {
            log.warn("[MlExit] {} 청산 실패 — ticker: {}, error: {}", tag, ticker, result.errorMsg());
        }
    }

    private BigDecimal getCurrentPrice(String ticker) {
        try {
            StockPriceDto price = marketClient.getCurrentPrice(ticker);
            return price != null ? price.getCurrentPrice() : null;
        } catch (Exception e) {
            log.warn("[MlExit] 현재가 조회 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            return null;
        }
    }
}
