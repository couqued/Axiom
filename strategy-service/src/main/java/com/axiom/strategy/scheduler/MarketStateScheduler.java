package com.axiom.strategy.scheduler;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.util.TradingCalendar;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
    private final StrategyEngine strategyEngine;
    private final SlackNotifier slackNotifier;

    /**
     * 서비스 재시작 시 감시 종목 복구.
     * volatility-breakout todayBought는 DB에서 직접 복구 (@PostConstruct in VolatilityBreakoutStrategy).
     */
    @PostConstruct
    public void initOnStartup() {
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
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshMorningRoutine() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) {
            log.info("[MarketStateScheduler] 공휴일 — 스킵");
            return;
        }

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

        // ③ 슬랙 알림
        int tickerCount = tickers.isEmpty() ? strategyEngine.getWatchTickerCount() : tickers.size();
        slackNotifier.sendSchedulerScreenerRefresh(tickerCount, marketStateService.getCurrentState().name());
    }
}
