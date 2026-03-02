package com.axiom.strategy.notification;

import com.axiom.strategy.dto.SignalDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Component
public class SlackNotifier {

    @Value("${slack.webhook-url}")
    private String webhookUrl;

    @Value("${slack.enabled:false}")
    private boolean enabled;

    /**
     * 매수/매도 신호 발생 알림.
     */
    public void sendSignal(SignalDto signal) {
        String emoji  = signal.getAction() == SignalDto.Action.BUY ? "🟢" : "🔴";
        String action = signal.getAction() == SignalDto.Action.BUY ? "매수" : "매도";
        String text = String.format(
                "%s *[%s 신호]* `%s`\n" +
                "> 전략: %s\n" +
                "> 가격: %s원\n" +
                "> 사유: %s",
                emoji, action, signal.getTicker(),
                signal.getStrategyName(),
                signal.getPrice() != null ? signal.getPrice().toPlainString() : "-",
                signal.getReason()
        );
        send(text);
    }

    /**
     * 주문 체결 완료 알림.
     */
    public void sendOrderFilled(SignalDto signal, boolean success) {
        String status = success ? "✅ 주문 체결" : "❌ 주문 실패";
        String action = signal.getAction() == SignalDto.Action.BUY ? "매수" : "매도";
        String text = String.format(
                "%s `%s` %s 완료\n> 전략: %s | 가격: %s원",
                status, signal.getTicker(), action,
                signal.getStrategyName(),
                signal.getPrice() != null ? signal.getPrice().toPlainString() : "-"
        );
        send(text);
    }

    /**
     * 오류 알림.
     */
    public void sendError(String message) {
        send("⚠️ *[전략 오류]* " + message);
    }

    private void send(String text) {
        if (!enabled) {
            log.info("[Slack-DISABLED] {}", text);
            return;
        }
        if ("PLACEHOLDER".equals(webhookUrl)) {
            log.warn("[Slack] webhook-url이 설정되지 않았습니다. application-secret.yml을 확인하세요.");
            return;
        }
        try {
            WebClient.builder().build()
                    .post()
                    .uri(webhookUrl)
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("[Slack] 알림 발송 실패: {}", e.getMessage());
        }
    }
}
