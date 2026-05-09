package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.MlClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.dto.StockPriceDto;
import com.axiom.strategy.dto.TradePlanDto;
import com.axiom.strategy.ml.MlPerformanceService;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.util.TradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ML 전략 포지션 전용 청산 서비스.
 *
 * <p>StrategyEngine 5분 사이클마다 {@link #check(List)} 호출.
 * 활성 ML 포지션에 대해 TP / SL / maxDays / expectedDays 재평가 순으로 체크.
 *
 * <ul>
 *   <li>TP / SL — 가격 조건 충족 시 즉시 청산</li>
 *   <li>maxDays — 절대 최대 보유 기간 초과 시 즉시 청산 (모델 훈련 범위 이탈)</li>
 *   <li>expectedDays — 모델 예측 보유일 경과 시 ML 재추론:
 *       confidence ≥ threshold 면 maxDays까지 연장, 미달 시 청산</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlExitService {

    private final AdminConfigStore adminConfigStore;
    private final MlPositionStore planStore;
    private final PortfolioClient portfolioClient;
    private final MarketClient marketClient;
    private final MlClient mlClient;
    private final MarketStateService marketStateService;
    private final StrategyConfig strategyConfig;
    private final OrderClient orderClient;
    private final SlackNotifier slackNotifier;
    private final DailySellBlockService dailySellBlockService;
    private final TrailingStopService trailingStopService;
    private final MlDeferTracker deferTracker;
    private final MlPerformanceService mlPerformanceService;
    private final StrategyStateStore strategyStateStore;

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
            } else if (elapsed >= plan.expectedDays()) {
                reevaluate(ticker, current, position, ap, elapsed);
                continue;
            }

            if (exitTag != null) {
                exit(ticker, exitTag, current, position, ap, elapsed);
            }
        }
    }

    private void reevaluate(String ticker, BigDecimal current,
                             PortfolioItemDto position, MlPositionStore.ActivePlan ap, int elapsed) {
        log.info("[MlExit] 재평가 시작 — ticker: {}, elapsed: {}일, expectedDays: {}",
                ticker, elapsed, ap.plan().expectedDays());

        List<CandleDto> candles = marketClient.getCandles(ticker, strategyConfig.getCandleDays());
        List<CandleDto> indexCandles = marketStateService.getKospiCandlesCached();
        double marketBreadth = marketStateService.getMarketBreadth();

        if (candles == null || candles.isEmpty()) {
            log.warn("[MlExit] 재평가 캔들 없음 — 보수적 청산 ticker: {}", ticker);
            exit(ticker, "ML 재평가 매도", current, position, ap, elapsed);
            return;
        }

        TradePlanDto newPlan = mlClient.predict(ticker, candles, indexCandles, marketBreadth,
                Collections.emptyList(), null);

        if (newPlan == null) {
            log.warn("[MlExit] 재평가 ML 응답 없음 — 보수적 청산 ticker: {}", ticker);
            exit(ticker, "ML 재평가 매도", current, position, ap, elapsed);
            return;
        }

        double threshold = adminConfigStore.getMlBuyThreshold();

        if (newPlan.confidence() >= threshold) {
            // expectedDays = maxDays 센티넬 → 같은 포지션에 재평가 1회만 발동
            TradePlanDto extendedPlan = new TradePlanDto(
                    newPlan.ticker(), newPlan.confidence(), newPlan.mlScore(),
                    ap.actualEntryPrice(),
                    newPlan.takeProfitPrice(),
                    newPlan.stopLossPrice(),
                    ap.plan().maxDays(),
                    ap.plan().maxDays(),
                    newPlan.reason(),
                    ap.plan().features()  // 기존 포지션 피처 유지 (재평가분은 새로 저장 안 함)
            );
            planStore.activateDirect(ticker, extendedPlan, ap.entryDate(), ap.actualEntryPrice());
            slackNotifier.sendMlReEval(ticker, position.getStockName(),
                    ap.actualEntryPrice(), current,
                    newPlan.confidence(), threshold,
                    newPlan.takeProfitPrice(), newPlan.stopLossPrice(),
                    elapsed, ap.plan().maxDays(), true);
            log.info("[MlExit] 재평가 — 연장 결정 ticker: {}, conf={}%",
                    ticker, String.format("%.1f", newPlan.confidence() * 100));
        } else {
            slackNotifier.sendMlReEval(ticker, position.getStockName(),
                    ap.actualEntryPrice(), current,
                    newPlan.confidence(), threshold,
                    newPlan.takeProfitPrice(), newPlan.stopLossPrice(),
                    elapsed, ap.plan().maxDays(), false);
            exit(ticker, "ML 재평가 매도", current, position, ap, elapsed);
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
                .closeReason("ML_" + tag.replace("ML ", "").trim().replace(" ", "_"))
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
            strategyStateStore.removeBuyStage(ticker);
            strategyStateStore.removeEntryTag(ticker);
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
