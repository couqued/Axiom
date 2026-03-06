package com.axiom.strategy.scheduler;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.dto.OrderSummaryDto;
import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 매일 장 시작 전(08:30) 실행:
 * 1. 코스피200 + 코스닥150 감시 종목 목록 갱신
 * 2. 코스피 지수 기반 시장 상태(상승장/횡보장) 판별
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketStateScheduler {

    private final MarketStateService marketStateService;
    private final MarketClient marketClient;
    private final OrderClient orderClient;
    private final StrategyEngine strategyEngine;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;

    /**
     * 서비스 재시작 시 인메모리 상태 복구:
     * 1. 감시 종목 목록 — market-service에서 즉시 로드 (yml fallback 방지)
     * 2. volatility-breakout todayBought — 오늘 체결된 BUY 이력으로 복구 (ForceExit 누락 방지)
     */
    @PostConstruct
    public void initOnStartup() {
        // ① 감시 종목 복구
        try {
            List<String> tickers = marketClient.getScreenedTickers();
            if (!tickers.isEmpty()) {
                strategyEngine.updateWatchTickers(tickers);
                log.info("[MarketStateScheduler] 기동 시 감시 종목 로드 완료 — {}개", tickers.size());
            } else {
                log.warn("[MarketStateScheduler] 기동 시 screened-tickers 빈 응답 — yml watch-tickers 유지");
            }
        } catch (Exception e) {
            log.warn("[MarketStateScheduler] 기동 시 감시 종목 로드 실패 — yml fallback 유지: {}", e.getMessage());
        }

        // ② todayBought 복구 (재시작으로 인한 ForceExit 누락 방지)
        try {
            List<OrderSummaryDto> orders = orderClient.getFilledOrders();
            volatilityBreakoutStrategy.restoreFromOrders(orders);
            log.info("[MarketStateScheduler] 기동 시 todayBought 복구 완료 — 복구된 종목: {}",
                    volatilityBreakoutStrategy.getTodayBought().keySet());
        } catch (Exception e) {
            log.warn("[MarketStateScheduler] 기동 시 todayBought 복구 실패 — ForceExit 정상 작동 불가: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshMorningRoutine() {
        // ① 감시 종목 갱신
        List<String> tickers = marketClient.getScreenedTickers();
        if (!tickers.isEmpty()) {
            strategyEngine.updateWatchTickers(tickers);
            log.info("[MarketStateScheduler] 감시 종목 갱신 완료 — {}개", tickers.size());
        } else {
            log.warn("[MarketStateScheduler] screened-tickers 빈 응답 — yml watch-tickers 유지");
        }

        // ② 시장 상태 판별
        log.info("[MarketStateScheduler] 시장 상태 판별 시작");
        marketStateService.refresh();
    }
}
