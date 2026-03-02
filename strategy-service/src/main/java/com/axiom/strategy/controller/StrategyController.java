package com.axiom.strategy.controller;

import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.notification.SlackNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    /** 전략 즉시 실행 (테스트 / 수동 트리거용) */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> run() {
        strategyEngine.run();
        return ResponseEntity.ok(Map.of("result", "전략 실행 완료. 로그를 확인하세요."));
    }

    /** Slack 알림 연결 테스트 */
    @PostMapping("/test-slack")
    public ResponseEntity<Map<String, String>> testSlack() {
        SignalDto testSignal = SignalDto.builder()
                .action(SignalDto.Action.BUY)
                .ticker("005930")
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
