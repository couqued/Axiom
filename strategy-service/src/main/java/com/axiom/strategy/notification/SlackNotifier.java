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
     * 신호 발생 + 주문 결과를 단일 메시지로 발송.
     * success=true: ✅ 체결, false: ❌ KIS 주문 실패
     */
    public void sendTradeResult(SignalDto signal, boolean success) {
        boolean isBuy = signal.getAction() == SignalDto.Action.BUY;
        String actionKo = isBuy ? "매수" : "매도";
        String resultEmoji = success ? "✅" : "❌";
        String resultText  = success ? "체결" : "주문 실패";
        String text = String.format(
                "%s *[%s %s]* %s\n" +
                "> 전략: %s\n" +
                "> 가격: %s원\n" +
                "> 사유: %s",
                resultEmoji, actionKo, resultText,
                formatStock(signal.getStockName(), signal.getTicker()),
                signal.getStrategyName(),
                formatPrice(signal.getPrice()),
                signal.getReason()
        );
        send(text);
    }

    /**
     * 오류 알림.
     */
    public void sendError(String message) {
        send("⚠️ *[전략 오류]* " + message);
    }

    /**
     * 서비스 시작 알림.
     */
    public void sendServiceStarted() {
        String time = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        send("🟢 *strategy-service* 시작  (" + time + ")");
    }

    /**
     * 서비스 종료 알림.
     */
    public void sendServiceStopped() {
        String time = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        send("🔴 *strategy-service* 종료  (" + time + ")");
    }

    /** "삼성전자 (005930)" 형태로 포맷. stockName이 없으면 ticker만 반환. */
    private String formatStock(String stockName, String ticker) {
        return (stockName != null && !stockName.isBlank()) ? stockName + " (" + ticker + ")" : ticker;
    }

    /** 가격에 천 단위 콤마 적용. null이면 "-" 반환. */
    private String formatPrice(java.math.BigDecimal price) {
        return price != null ? String.format("%,.0f", price) : "-";
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
