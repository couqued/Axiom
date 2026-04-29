package com.axiom.strategy.strategy;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.MlClient;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.InvestorFlowDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.dto.TradePlanDto;
import com.axiom.strategy.service.EntryQualityEvaluator;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.service.MlDeferTracker;
import com.axiom.strategy.service.MlPositionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ML 예측 전략 — ml-service 의 TradePlan 을 받아 SignalDto로 변환.
 *
 * <p>score = mlScore × entryQualityMultiplier. Entry Quality 는 분봉 3축 multiplier
 * + 갭업/FOMO 가드를 적용해 시초가 급등·과열 구간 매수를 억제한다.
 *
 * <p>체결 성공 시 StrategyEngine 에서 {@link MlPositionStore#activate} 를 호출해
 * staged → active 로 승격한다. SELL 은 {@code MlExitService} 가 TP/SL/maxDays 로 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MLPredictionStrategy implements TradingStrategy {

    private final MlClient mlClient;
    private final MarketClient marketClient;
    private final MarketStateService marketStateService;
    private final MlPositionStore planStore;
    private final AdminConfigStore adminConfigStore;
    private final EntryQualityEvaluator entryQualityEvaluator;
    private final MlDeferTracker deferTracker;

    @Override
    public String getName() {
        return "ml-prediction";
    }

    @Override
    public int minimumCandles() {
        return 80;
    }

    @Override
    public SignalDto evaluate(String ticker, List<CandleDto> candles) {
        // 당일 블랙리스트 종목은 추론 자체 스킵
        if (deferTracker.isBlacklisted(ticker)) {
            return hold(ticker, "ML 당일 블랙리스트 (연속 3회 DEFER)");
        }

        List<CandleDto> indexCandles = marketStateService.getKospiCandlesCached();
        double marketBreadth = marketStateService.getMarketBreadth();
        List<InvestorFlowDto> investorFlows = marketClient.getInvestorFlows(ticker, 30);
        InvestorFlowDto todayFlow = marketClient.getTodayInvestorFlow(ticker);
        TradePlanDto plan = mlClient.predict(ticker, candles, indexCandles, marketBreadth,
                investorFlows, todayFlow);

        if (plan == null) {
            return hold(ticker, "ML 응답 없음 (HOLD)");
        }

        double threshold = adminConfigStore.getMlBuyThreshold();
        if (plan.confidence() < threshold) {
            return hold(ticker, String.format("ML 확신도 부족 %.1f%% < %.0f%%",
                    plan.confidence() * 100, threshold * 100));
        }

        // Entry Quality 계산 (Admin 토글로 비활성화 시 1.0)
        double multiplier = adminConfigStore.isMlEntryTimingEnabled()
                ? entryQualityEvaluator.evaluate(ticker, plan.entryPrice(), candles)
                : 1.0;

        // 강제 가드(multiplier < 0)로 약속된 강제 DEFER
        if (multiplier <= 0.0) {
            return hold(ticker, "ML 강제 DEFER — 갭업/FOMO 가드");
        }

        double effectiveScore = plan.mlScore() * multiplier;
        planStore.stage(ticker, plan, multiplier);

        return SignalDto.builder()
                .action(SignalDto.Action.BUY)
                .ticker(ticker)
                .price(plan.entryPrice())
                .strategyName(getName())
                .score(effectiveScore)
                .reason(String.format("ML BUY [conf=%.0f%% × entry=%.2f = %.1f | TP=%s SL=%s ~%dd/%dd] %s",
                        plan.confidence() * 100, multiplier, effectiveScore,
                        plan.takeProfitPrice(), plan.stopLossPrice(),
                        plan.expectedDays(), plan.maxDays(),
                        plan.reason() != null ? plan.reason() : ""))
                .signalAt(LocalDateTime.now())
                .build();
    }

    private SignalDto hold(String ticker, String reason) {
        return SignalDto.builder()
                .action(SignalDto.Action.HOLD)
                .ticker(ticker)
                .strategyName(getName())
                .reason(reason)
                .signalAt(LocalDateTime.now())
                .build();
    }
}
