package com.axiom.market.controller;

import com.axiom.market.repository.DailyCandleRepository;
import com.axiom.market.service.InvestorFlowService;
import com.axiom.market.service.StockScreenerService;
import com.axiom.market.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 서비스 간 내부 통신용 market-service API.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMarketController {

    private final StockScreenerService   stockScreenerService;
    private final DailyCandleRepository  dailyCandleRepository;
    private final InvestorFlowService    investorFlowService;
    private final WatchlistService       watchlistService;

    /**
     * 코스피200 + 코스닥150 스크리닝 종목 목록 반환 (strategy-service 호출용).
     * GET /internal/screened-tickers
     *
     * Phase 1+: DB(market.watch_tickers ACTIVE) 우선. 비어있으면 JSON fallback.
     */
    @GetMapping("/screened-tickers")
    public List<String> getScreenedTickers() {
        List<String> dbTickers = watchlistService.loadActiveTickers();
        if (dbTickers != null && !dbTickers.isEmpty()) {
            return dbTickers;
        }
        log.warn("[Internal] DB watchlist 비어있음 — JSON fallback");
        return stockScreenerService.getScreenedTickers();
    }

    /**
     * 티커-종목명 맵 반환.
     * GET /internal/ticker-names
     */
    @GetMapping("/ticker-names")
    public Map<String, String> getTickerNames() {
        Map<String, String> dbNames = watchlistService.loadActiveTickerNames();
        if (dbNames != null && !dbNames.isEmpty()) {
            return dbNames;
        }
        return stockScreenerService.getTickerNames();
    }

    /**
     * 데이터 신선도 상태 반환 — strategy-service DataFreshnessChecker 호출용.
     * GET /internal/data-status
     */
    @GetMapping("/data-status")
    public Map<String, Object> getDataStatus() {
        LocalDate latest = dailyCandleRepository.findLatestTradeDate().orElse(null);
        return Map.of("latestCandleDate", latest != null ? latest.toString() : "unknown");
    }

    /**
     * 투자자 데이터 과거 히스토리 일괄 적재 (최초 1회 실행).
     * POST /internal/backfill-investor-flows?days=1500
     * 백그라운드 스레드에서 실행 — 즉시 202 반환, 진행 상황은 로그로 확인.
     */
    @PostMapping("/backfill-investor-flows")
    public Map<String, Object> backfillInvestorFlows(
            @RequestParam(defaultValue = "1500") int days) {
        List<String> tickers = stockScreenerService.getScreenedTickers();
        log.info("[Backfill] 투자자 히스토리 적재 시작 — tickers: {}개, days: {}", tickers.size(), days);

        new Thread(() -> {
            int success = 0;
            int fail    = 0;
            for (String ticker : tickers) {
                try {
                    investorFlowService.getFlows(ticker, days);
                    success++;
                    if (success % 50 == 0) {
                        log.info("[Backfill] 진행 중 — {}/{}", success + fail, tickers.size());
                    }
                    Thread.sleep(200); // KIS API rate limit 방어
                } catch (Exception e) {
                    log.warn("[Backfill] 실패 - ticker: {}, error: {}", ticker, e.getMessage());
                    fail++;
                }
            }
            log.info("[Backfill] 완료 — success: {}, fail: {}", success, fail);
        }, "investor-backfill").start();

        return Map.of(
                "status", "started",
                "tickers", tickers.size(),
                "days", days,
                "message", "백그라운드 적재 시작 — 로그에서 진행 상황 확인"
        );
    }
}
