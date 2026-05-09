package com.axiom.market.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * market-service 전용 Slack 알림.
 * 워치리스트 변경 (자동 제외/복귀, 분기 리밸런싱)에만 사용.
 * strategy-service의 SlackNotifier와 webhook URL을 공유한다.
 */
@Slf4j
@Component
public class MarketSlackNotifier {

    @Value("${slack.webhook-url}")
    private String webhookUrl;

    @Value("${slack.enabled:false}")
    private boolean enabled;

    public record Change(String ticker, String stockName, String reason) {}

    /**
     * 일일 워치리스트 자동 평가 결과 알림.
     */
    public void sendDailyReview(boolean dryRun, List<Change> excluded, List<Change> restored) {
        if (excluded.isEmpty() && restored.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "🧪 *[워치리스트 일일 점검 — DRY RUN]*\n"
                         : "📋 *[워치리스트 일일 점검]*\n");
        if (!excluded.isEmpty()) {
            sb.append("> 자동 제외 ").append(excluded.size()).append("건");
            for (Change c : excluded) {
                sb.append("\n>  • ").append(formatStock(c.stockName(), c.ticker()))
                  .append(" — ").append(c.reason());
            }
        }
        if (!restored.isEmpty()) {
            sb.append(excluded.isEmpty() ? "" : "\n");
            sb.append("> 자동 복귀 ").append(restored.size()).append("건");
            for (Change c : restored) {
                sb.append("\n>  • ").append(formatStock(c.stockName(), c.ticker()))
                  .append(" — ").append(c.reason());
            }
        }
        send(sb.toString());
    }

    /**
     * 주간 시총/거래대금 리뷰 결과 알림.
     */
    public void sendWeeklyReview(boolean dryRun, List<Change> excluded, List<Change> restored) {
        if (excluded.isEmpty() && restored.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "🧪 *[워치리스트 주간 시총/거래대금 점검 — DRY RUN]*\n"
                         : "📊 *[워치리스트 주간 시총/거래대금 점검]*\n");
        if (!excluded.isEmpty()) {
            sb.append("> 임계 미달 제외 ").append(excluded.size()).append("건");
            for (Change c : excluded) {
                sb.append("\n>  • ").append(formatStock(c.stockName(), c.ticker()))
                  .append(" — ").append(c.reason());
            }
        }
        if (!restored.isEmpty()) {
            sb.append(excluded.isEmpty() ? "" : "\n");
            sb.append("> 임계 회복 복귀 ").append(restored.size()).append("건");
            for (Change c : restored) {
                sb.append("\n>  • ").append(formatStock(c.stockName(), c.ticker()))
                  .append(" — ").append(c.reason());
            }
        }
        send(sb.toString());
    }

    /**
     * 분기 KRX 리밸런싱 결과 알림 (Phase 3).
     */
    public void sendQuarterlyRebalance(boolean dryRun, List<Change> added, List<Change> removed, String error) {
        StringBuilder sb = new StringBuilder();
        if (error != null) {
            sb.append("❌ *[워치리스트 분기 리밸런싱 실패]*\n> ").append(error);
            send(sb.toString());
            return;
        }
        sb.append(dryRun ? "🧪 *[워치리스트 분기 리밸런싱 — DRY RUN]*\n"
                         : "🔄 *[워치리스트 분기 리밸런싱]*\n");
        sb.append("> 신규 편입 ").append(added.size())
          .append("건  ·  편출 ").append(removed.size()).append("건");
        if (!added.isEmpty()) {
            sb.append("\n> 편입:");
            for (Change c : added) {
                sb.append("\n>  + ").append(formatStock(c.stockName(), c.ticker()))
                  .append(c.reason() != null && !c.reason().isBlank() ? " — " + c.reason() : "");
            }
        }
        if (!removed.isEmpty()) {
            sb.append("\n> 편출:");
            for (Change c : removed) {
                sb.append("\n>  - ").append(formatStock(c.stockName(), c.ticker()))
                  .append(c.reason() != null && !c.reason().isBlank() ? " — " + c.reason() : "");
            }
        }
        send(sb.toString());
    }

    private String formatStock(String stockName, String ticker) {
        return (stockName != null && !stockName.isBlank())
                ? stockName + " (" + ticker + ")" : ticker;
    }

    private void send(String text) {
        if (!enabled) {
            log.info("[MarketSlack-DISABLED] {}", text);
            return;
        }
        if ("PLACEHOLDER".equals(webhookUrl)) {
            log.warn("[MarketSlack] webhook-url이 설정되지 않았습니다. application-secret.yml을 확인하세요.");
            return;
        }
        try {
            WebClient.builder().build()
                    .post()
                    .uri(webhookUrl)
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            log.debug("[MarketSlack] 발송 완료");
        } catch (Exception e) {
            log.error("[MarketSlack] 알림 발송 실패: {}", e.getMessage());
        }
    }
}
