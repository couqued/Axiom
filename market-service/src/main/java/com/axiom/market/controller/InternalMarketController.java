package com.axiom.market.controller;

import com.axiom.market.service.StockScreenerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 서비스 간 내부 통신용 market-service API.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMarketController {

    private final StockScreenerService stockScreenerService;

    /**
     * 코스피200 + 코스닥150 스크리닝 종목 목록 반환 (strategy-service 호출용).
     * GET /internal/screened-tickers
     */
    @GetMapping("/screened-tickers")
    public List<String> getScreenedTickers() {
        return stockScreenerService.getScreenedTickers();
    }
}
