package com.axiom.strategy.notification;

import com.axiom.strategy.dto.SignalDto;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
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
     * 전략 신호 + 주문 결과 단일 메시지.
     * success=true : ✅ 체결
     * success=false: ❌ 주문 실패 + 실패사유 포함
     */
    public void sendTradeResult(SignalDto signal, boolean success, String errorMsg) {
        boolean isBuy = signal.getAction() == SignalDto.Action.BUY;
        String actionKo    = isBuy ? "매수" : "매도";
        String resultEmoji = success ? "✅" : "❌";
        String resultText  = success ? "체결" : "주문 실패";

        StringBuilder sb = new StringBuilder(String.format(
                "%s *[%s %s]* %s\n" +
                "> 전략: %s\n" +
                "> 가격: %s원\n" +
                "> 신호: %s",
                resultEmoji, actionKo, resultText,
                formatStock(signal.getStockName(), signal.getTicker()),
                signal.getStrategyName(),
                formatPrice(signal.getPrice()),
                signal.getReason()
        ));

        if (!success && errorMsg != null) {
            sb.append("\n> 실패사유: ").append(errorMsg);
        }
        send(sb.toString());
    }

    /**
     * 트레일링 스탑 발동 알림.
     */
    public void sendTrailingStop(String ticker, String stockName,
                                 BigDecimal currentPrice, double stopPercent, boolean success) {
        String text = String.format(
                "🛑 *[전략 실행 | 트레일링 스탑]* %s\n" +
                "> 고점 대비 %.1f%% 하락 → 강제 매도\n" +
                "> 매도가: %s원  |  주문: %s",
                formatStock(stockName, ticker),
                stopPercent,
                formatPrice(currentPrice),
                success ? "성공" : "실패"
        );
        send(text);
    }

    /**
     * 타임컷 청산 알림.
     */
    public void sendTimeCut(String ticker, String stockName,
                            BigDecimal currentPrice, int elapsed, int maxDays, boolean success) {
        String text = String.format(
                "⏱️ *[전략 실행 | 타임컷]* %s\n" +
                "> %d거래일 경과 (기준: %d일) → 강제 매도\n" +
                "> 매도가: %s원  |  주문: %s",
                formatStock(stockName, ticker),
                elapsed, maxDays,
                formatPrice(currentPrice),
                success ? "성공" : "실패"
        );
        send(text);
    }

    /**
     * 마감청산 알림 (변동성 돌파 오버나이트 방지, 15:20).
     */
    public void sendForceExit(String ticker, String stockName,
                              int quantity, BigDecimal price, boolean success) {
        String text = String.format(
                "🔔 *[전략 실행 | 마감청산]* %s %d주\n" +
                "> 변동성 돌파 — 오버나이트 방지 (15:20)\n" +
                "> 매도가: %s원  |  주문: %s",
                formatStock(stockName, ticker),
                quantity,
                formatPrice(price),
                success ? "성공" : "실패"
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
