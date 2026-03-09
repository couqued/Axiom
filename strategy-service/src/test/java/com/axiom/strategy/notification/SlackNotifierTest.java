package com.axiom.strategy.notification;

import com.axiom.strategy.dto.SignalDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * SlackNotifier 단위 테스트.
 * enabled=false 상태에서 각 알림 메서드가 예외 없이 동작함을 검증한다.
 * (프로덕션 코드에 있던 /test-slack 엔드포인트를 대체)
 */
class SlackNotifierTest {

    private SlackNotifier slackNotifier;

    @BeforeEach
    void setUp() {
        slackNotifier = new SlackNotifier();
        ReflectionTestUtils.setField(slackNotifier, "webhookUrl", "PLACEHOLDER");
        ReflectionTestUtils.setField(slackNotifier, "enabled", false);
    }

    @Test
    void sendTradeResult_buy_disabled_noException() {
        SignalDto signal = SignalDto.builder()
                .action(SignalDto.Action.BUY)
                .ticker("005930")
                .stockName("삼성전자")
                .price(new BigDecimal("75000"))
                .strategyName("golden-cross")
                .reason("골든크로스 — 테스트 신호")
                .signalAt(LocalDateTime.now())
                .build();

        assertThatNoException().isThrownBy(() ->
                slackNotifier.sendTradeResult(signal, true, null));
    }

    @Test
    void sendTradeResult_sell_failed_disabled_noException() {
        SignalDto signal = SignalDto.builder()
                .action(SignalDto.Action.SELL)
                .ticker("000660")
                .stockName("SK하이닉스")
                .price(new BigDecimal("185000"))
                .strategyName("golden-cross")
                .reason("데드크로스 — 테스트 신호")
                .signalAt(LocalDateTime.now())
                .build();

        assertThatNoException().isThrownBy(() ->
                slackNotifier.sendTradeResult(signal, false, "테스트 오류 메시지"));
    }

    @Test
    void sendTrailingStop_disabled_noException() {
        assertThatNoException().isThrownBy(() ->
                slackNotifier.sendTrailingStop("005930", "삼성전자",
                        new BigDecimal("73000"), 5.0, true));
    }

    @Test
    void sendTimeCut_disabled_noException() {
        assertThatNoException().isThrownBy(() ->
                slackNotifier.sendTimeCut("005930", "삼성전자",
                        new BigDecimal("74000"), 3, 3, true));
    }

    @Test
    void sendError_disabled_noException() {
        assertThatNoException().isThrownBy(() ->
                slackNotifier.sendError("테스트 오류 메시지"));
    }
}
