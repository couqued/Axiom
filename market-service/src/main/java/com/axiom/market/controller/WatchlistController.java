package com.axiom.market.controller;

import com.axiom.market.dto.WatchTickerDto;
import com.axiom.market.entity.WatchTicker;
import com.axiom.market.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 워치리스트 관리 API.
 * GET    /api/watchlist                       - 전체 조회
 * GET    /api/watchlist/counts                - 상태별 카운트
 * POST   /api/watchlist/sync                  - 부트스트랩 강제 실행
 * POST   /api/watchlist/{ticker}/exclude      - 수동 제외 (body: {reason})
 * DELETE /api/watchlist/{ticker}/exclude      - 수동 제외 해제
 * POST   /api/watchlist/{ticker}/add          - 수동 추가 (body: {stockName, marketIndex})
 */
@Slf4j
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @GetMapping
    public ResponseEntity<List<WatchTickerDto>> list() {
        List<WatchTickerDto> all = watchlistService.findAll().stream()
                .map(WatchTickerDto::from)
                .toList();
        return ResponseEntity.ok(all);
    }

    @GetMapping("/counts")
    public ResponseEntity<Map<String, Long>> counts() {
        return ResponseEntity.ok(watchlistService.counts());
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Integer>> sync() {
        return ResponseEntity.ok(watchlistService.forceSync());
    }

    @PostMapping("/review/daily")
    public ResponseEntity<Map<String, Object>> dailyReview() {
        return ResponseEntity.ok(watchlistService.dailyReview());
    }

    @PostMapping("/review/weekly")
    public ResponseEntity<Map<String, Object>> weeklyReview() {
        return ResponseEntity.ok(watchlistService.weeklyMarketCapReview());
    }

    @PostMapping("/review/quarterly")
    public ResponseEntity<Map<String, Object>> quarterlyReview() {
        return ResponseEntity.ok(watchlistService.quarterlyRebalance());
    }

    public record ExcludeRequest(String reason) {}
    public record AddRequest(String stockName, String marketIndex) {}

    @PostMapping("/{ticker}/exclude")
    public ResponseEntity<WatchTickerDto> exclude(
            @PathVariable String ticker,
            @RequestBody(required = false) ExcludeRequest req) {
        String reason = req != null && req.reason() != null ? req.reason() : "사유 미기재";
        WatchTicker w = watchlistService.excludeManual(ticker, reason);
        return ResponseEntity.ok(WatchTickerDto.from(w));
    }

    @DeleteMapping("/{ticker}/exclude")
    public ResponseEntity<WatchTickerDto> restore(@PathVariable String ticker) {
        WatchTicker w = watchlistService.restoreManual(ticker);
        return ResponseEntity.ok(WatchTickerDto.from(w));
    }

    @PostMapping("/{ticker}/add")
    public ResponseEntity<WatchTickerDto> add(
            @PathVariable String ticker,
            @RequestBody(required = false) AddRequest req) {
        String name = req != null ? req.stockName() : null;
        String idx = req != null ? req.marketIndex() : "MANUAL";
        WatchTicker w = watchlistService.addManual(ticker, name, idx);
        return ResponseEntity.ok(WatchTickerDto.from(w));
    }
}
