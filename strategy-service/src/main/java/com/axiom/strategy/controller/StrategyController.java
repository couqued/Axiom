package com.axiom.strategy.controller;

import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.service.MarketState;
import com.axiom.strategy.service.MarketStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
