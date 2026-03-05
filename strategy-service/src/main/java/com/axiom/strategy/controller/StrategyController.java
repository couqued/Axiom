package com.axiom.strategy.controller;

import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.service.MarketState;
import com.axiom.strategy.service.MarketStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyEngine strategyEngine;
    private final SlackNotifier slackNotifier;
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

    /** 현재 시장 상태 조회 */
    @GetMapping("/market-state")
    public ResponseEntity<Map<String, String>> getMarketState() {
        MarketState state = marketStateService.getCurrentState();
        return ResponseEntity.ok(Map.of("state", state.name()));
    }

    /** 시장 상태 수동 갱신 (테스트용) */
    @PostMapping("/refresh-market-state")
    public ResponseEntity<Map<String, String>> refreshMarketState() {
        marketStateService.refresh();
        MarketState state = marketStateService.getCurrentState();
        return ResponseEntity.ok(Map.of("state", state.name(), "result", "시장 상태 갱신 완료"));
    }

    /** Slack 알림 연결 테스트 */
    @PostMapping("/test-slack")
    public ResponseEntity<Map<String, String>> testSlack() {
        SignalDto testSignal = SignalDto.builder()
                .action(SignalDto.Action.BUY)
                .ticker("005930")
                .stockName("삼성전자")
                .price(new BigDecimal("216500"))
                .strategyName("golden-cross")
                .reason("테스트 — Slack 연동 확인용 신호")
                .signalAt(LocalDateTime.now())
                .build();
        slackNotifier.sendSignal(testSignal);
        slackNotifier.sendOrderFilled(testSignal, true);
        return ResponseEntity.ok(Map.of("result", "Slack 테스트 메시지 발송 완료"));
    }
}
