package com.axiom.strategy.controller;

import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.service.MarketState;
import com.axiom.strategy.service.MarketStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.axiom.strategy.util.TradingCalendar;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyEngine strategyEngine;
    private final MarketStateService marketStateService;

    /** 전략 즉시 실행 (테스트 / 수동 트리거용) */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> run() {
        StrategyEngine.RunResult result = strategyEngine.run();
        String message = result.paused()
                ? "매매 중단 상태 — 전략 실행 스킵"
                : String.format("종목 %d개 평가 완료 — 매수 %d건, 매도 %d건",
                        result.evaluated(), result.bought(), result.sold());
        return ResponseEntity.ok(Map.of("result", message));
    }

    /** 현재 시장 상태 + 지수 스냅샷 조회 */
    @GetMapping("/market-state")
    public ResponseEntity<Map<String, Object>> getMarketState() {
        MarketState state = marketStateService.getCurrentState();
        MarketStateService.IndexSnapshot snap = marketStateService.getIndexSnapshot();
        Map<String, Object> body = new HashMap<>();
        body.put("state", state.name());
        body.put("yesterdayClose", snap.yesterdayClose());
        body.put("ma20", snap.ma20());
        body.put("todayOpenIndex", snap.todayOpenIndex());
        return ResponseEntity.ok(body);
    }

    /** 시장 상태 수동 갱신 (테스트용) */
    @PostMapping("/refresh-market-state")
    public ResponseEntity<Map<String, Object>> refreshMarketState() {
        marketStateService.refresh();
        MarketState state = marketStateService.getCurrentState();
        MarketStateService.IndexSnapshot snap = marketStateService.getIndexSnapshot();
        Map<String, Object> body = new HashMap<>();
        body.put("state", state.name());
        body.put("yesterdayClose", snap.yesterdayClose());
        body.put("ma20", snap.ma20());
        body.put("todayOpenIndex", snap.todayOpenIndex());
        body.put("result", "시장 상태 갱신 완료");
        return ResponseEntity.ok(body);
    }

    /** 당일 시간별 실행 이력 (메모리, 서버 재시작 시 초기화) */
    @GetMapping("/run-history")
    public ResponseEntity<Map<String, Object>> getRunHistory() {
        List<StrategyEngine.RunRecord> runs = strategyEngine.getTodayRuns();

        Map<Integer, List<StrategyEngine.RunRecord>> byHour = runs.stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> r.runAt().getHour()));

        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 9; h <= 15; h++) {
            List<StrategyEngine.RunRecord> hrRuns = byHour.getOrDefault(h, List.of());
            if (hrRuns.isEmpty()) continue;

            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("hour", h);
            entry.put("runCount", hrRuns.size());
            entry.put("evaluated", hrRuns.stream().mapToInt(StrategyEngine.RunRecord::evaluated).sum());
            entry.put("bought", hrRuns.stream().mapToInt(StrategyEngine.RunRecord::bought).sum());
            entry.put("sold", hrRuns.stream().mapToInt(StrategyEngine.RunRecord::sold).sum());
            entry.put("errors", hrRuns.stream().mapToInt(StrategyEngine.RunRecord::errors).sum());
            entry.put("boughtTickers", hrRuns.stream()
                    .flatMap(r -> r.boughtList().stream())
                    .map(t -> t.stockName() + "(" + t.ticker() + ")")
                    .distinct().collect(java.util.stream.Collectors.toList()));
            entry.put("skippedList", hrRuns.stream()
                    .flatMap(r -> r.skippedList().stream())
                    .map(s -> Map.of("ticker", s.ticker(), "stockName", s.stockName(), "reason", s.reason()))
                    .distinct().collect(java.util.stream.Collectors.toList()));
            entry.put("noSignalCount",
                    hrRuns.stream().filter(r -> r.bought() == 0 && r.skippedList().isEmpty()).count());
            hours.add(entry);
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("date", LocalDate.now(TradingCalendar.KST).toString());
        body.put("hours", hours);
        return ResponseEntity.ok(body);
    }

    /** 매수 신호까지의 근접도 — 캐시 즉시 반환 */
    @GetMapping("/signal-gap")
    public ResponseEntity<Map<String, Object>> getSignalGap() {
        Map<String, Object> body = new HashMap<>();
        body.put("items", strategyEngine.getSignalGapCache());
        body.put("computedAt", strategyEngine.getSignalGapComputedAt());
        body.put("running", strategyEngine.isSignalGapRunning());
        return ResponseEntity.ok(body);
    }

    /** 매수 신호 근접도 백그라운드 계산 트리거 */
    @PostMapping("/signal-gap/refresh")
    public ResponseEntity<Map<String, Object>> refreshSignalGap(
            @RequestParam(defaultValue = "10") int top) {
        strategyEngine.triggerSignalGapRefresh(top);
        boolean alreadyRunning = strategyEngine.isSignalGapRunning();
        return ResponseEntity.ok(Map.of("result", alreadyRunning ? "계산 중" : "계산 시작"));
    }

    /** 마지막 전략 실행 BUY 랭킹 조회 (score 내림차순 상위 30개) */
    @GetMapping("/eval-ranking")
    public ResponseEntity<Map<String, Object>> getEvalRanking() {
        List<StrategyEngine.EvalRankEntry> ranking = strategyEngine.getLastBuyRanking();
        LocalDateTime evaluatedAt = strategyEngine.getLastEvalAt();
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatedAt", evaluatedAt != null ? evaluatedAt.toString() : null);
        body.put("items", ranking);
        return ResponseEntity.ok(body);
    }

}
