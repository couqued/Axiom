package com.axiom.market.controller;

import com.axiom.market.repository.DailyCandleRepository;
import com.axiom.market.service.StockScreenerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 서비스 간 내부 통신용 market-service API.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMarketController {

    private final StockScreenerService   stockScreenerService;
    private final DailyCandleRepository  dailyCandleRepository;

    /**
     * 코스피200 + 코스닥150 스크리닝 종목 목록 반환 (strategy-service 호출용).
     * GET /internal/screened-tickers
     */
    @GetMapping("/screened-tickers")
    public List<String> getScreenedTickers() {
        return stockScreenerService.getScreenedTickers();
    }

    /**
     * 티커-종목명 맵 반환.
     * GET /internal/ticker-names
     */
    @GetMapping("/ticker-names")
    public Map<String, String> getTickerNames() {
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
}
