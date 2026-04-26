package com.axiom.market.controller;

import com.axiom.market.dto.CandleDto;
import com.axiom.market.dto.MinuteCandleDto;
import com.axiom.market.dto.StockInfoDto;
import com.axiom.market.dto.StockPriceDto;
import com.axiom.market.service.CandleService;
import com.axiom.market.service.IndexCandleService;
import com.axiom.market.service.KisMarketApiService;
import com.axiom.market.service.MinuteCandleService;
import com.axiom.market.service.StockSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StockController {

    private final KisMarketApiService kisMarketApiService;
    private final StockSearchService stockSearchService;
    private final CandleService candleService;
    private final IndexCandleService indexCandleService;
    private final MinuteCandleService minuteCandleService;

    // 현재가 조회: GET /api/market/stocks/{ticker}/price → 게이트웨이가 /api/stocks/{ticker}/price 로 라우팅
    @GetMapping("/stocks/{ticker}/price")
    public ResponseEntity<StockPriceDto> getPrice(@PathVariable String ticker) {
        return ResponseEntity.ok(kisMarketApiService.getCurrentPrice(ticker));
    }

    // 종목 검색: GET /api/market/stocks/search?query=삼성
    @GetMapping("/stocks/search")
    public ResponseEntity<List<StockInfoDto>> search(@RequestParam String query) {
        return ResponseEntity.ok(stockSearchService.search(query));
    }

    // 종목 상세 조회: GET /api/market/stocks/{ticker}
    @GetMapping("/stocks/{ticker}")
    public ResponseEntity<StockInfoDto> getStock(@PathVariable String ticker) {
        return ResponseEntity.ok(stockSearchService.findByTicker(ticker));
    }

    // 일봉 조회: GET /api/market/stocks/{ticker}/candles?days=60
    @GetMapping("/stocks/{ticker}/candles")
    public ResponseEntity<List<CandleDto>> getCandles(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "60") int days) {
        return ResponseEntity.ok(candleService.getCandles(ticker, days));
    }

    // 분봉 조회: GET /api/market/stocks/{ticker}/minute-candles?count=10
    // ML Entry Quality Multiplier 계산용 — 오래된 순 정렬, 30초 TTL 캐시
    @GetMapping("/stocks/{ticker}/minute-candles")
    public ResponseEntity<List<MinuteCandleDto>> getMinuteCandles(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "10") int count) {
        return ResponseEntity.ok(minuteCandleService.getMinuteCandles(ticker, count));
    }

    // 지수 일봉 조회: GET /api/market/index/{code}/candles?days=25
    // 예: /api/market/index/0001/candles?days=25 (코스피 20일 MA 계산용)
    @GetMapping("/index/{code}/candles")
    public ResponseEntity<List<CandleDto>> getIndexCandles(
            @PathVariable String code,
            @RequestParam(defaultValue = "25") int days) {
        return ResponseEntity.ok(indexCandleService.getIndexCandles(code, days));
    }
}
