package com.axiom.strategy.notification;

import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.engine.StrategyEngine;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
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
    public void sendTradeResult(SignalDto signal, boolean success, String errorMsg, BigDecimal avgBuyPrice) {
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

        if (!isBuy && avgBuyPrice != null && avgBuyPrice.compareTo(BigDecimal.ZERO) > 0) {
            double roi = signal.getPrice().subtract(avgBuyPrice)
                    .divide(avgBuyPrice, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
            sb.append(String.format("\n> 매수평균가: %s원  |  수익률: %s%.2f%%",
                    formatPrice(avgBuyPrice), roi >= 0 ? "+" : "", roi));
        }

        if (!success && errorMsg != null) {
            sb.append("\n> 실패사유: ").append(errorMsg);
        }
        send(sb.toString());
    }

    /**
     * RSI 과매수이나 볼린저 중심선 미달로 홀드 유지 알림 (최초 1회).
     */
    public void sendRsiOverboughtHold(SignalDto signal) {
        String text = String.format(
                "⏸️ *[RSI 과매수 홀드]* %s\n> %s",
                formatStock(signal.getStockName(), signal.getTicker()),
                signal.getReason()
        );
        send(text);
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
     * 익절 청산 알림.
     */
    public void sendProfitTake(String ticker, String stockName,
                               BigDecimal currentPrice, BigDecimal avgPrice,
                               double targetPct, boolean success) {
        double roi = currentPrice.subtract(avgPrice)
                .divide(avgPrice, 4, RoundingMode.HALF_UP)
                .doubleValue() * 100;
        String text = String.format(
                "💰 *[전략 실행 | 익절]* %s\n" +
                "> 목표 +%.1f%% 도달 → 매도\n" +
                "> 매도가: %s원  |  수익률: +%.2f%%  |  주문: %s",
                formatStock(stockName, ticker), targetPct,
                formatPrice(currentPrice), roi, success ? "성공" : "실패"
        );
        send(text);
    }

    /**
     * VolBreakout 전용 매도 보강 (TP / SL / 일중 트레일링) 청산 알림.
     */
    public void sendVolBreakoutExit(String ticker, String stockName,
                                    BigDecimal avgPrice, BigDecimal currentPrice,
                                    String reasonText, boolean success) {
        double roi = 0;
        if (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
            roi = currentPrice.subtract(avgPrice)
                    .divide(avgPrice, 4, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
        }
        String emoji = reasonText.startsWith("익절") ? "💰"
                : reasonText.startsWith("손절") ? "🛑" : "📉";
        String text = String.format(
                "%s *[변동성돌파 | %s]* %s\n" +
                "> 매수가: %s원  |  매도가: %s원  |  수익률: %s%.2f%%\n" +
                "> 주문: %s",
                emoji, reasonText, formatStock(stockName, ticker),
                formatPrice(avgPrice), formatPrice(currentPrice),
                roi >= 0 ? "+" : "", roi,
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
     * 08:30 감시종목 갱신 결과 알림.
     */
    public void sendSchedulerScreenerRefresh(int tickerCount, String marketState) {
        String text = String.format(
                "📋 *[08:30 감시종목갱신]* 완료\n" +
                "> 감시 종목: %d개  |  시장 상태: %s",
                tickerCount, marketState
        );
        send(text);
    }

    /**
     * 09:05 오버나이트청산 결과 알림.
     */
    public void sendOvernightExitResult(boolean hasTarget, int exitCount, boolean anyFailed,
                                        List<OvernightExitItem> items) {
        String text;
        if (!hasTarget) {
            text = "📋 *[09:05 오버나이트청산]* 대상 없음";
        } else {
            StringBuilder sb = new StringBuilder(String.format(
                    "🔔 *[09:05 오버나이트청산]* 완료\n" +
                    "> 청산 종목: %d개  |  결과: %s",
                    exitCount, anyFailed ? "일부 실패 ❌" : "전체 성공 ✅"
            ));
            for (OvernightExitItem item : items) {
                sb.append(String.format("\n> %s | 매수가: %s원 | 매도 주문가: %s원 | %s",
                        formatStock(item.stockName(), item.ticker()),
                        formatPrice(item.avgPrice()),
                        formatPrice(item.orderPrice()),
                        item.success() ? "✅" : "❌"));
            }
            text = sb.toString();
        }
        send(text);
    }

    public record OvernightExitItem(String ticker, String stockName,
                                    BigDecimal avgPrice, BigDecimal orderPrice, boolean success) {}

    /**
     * 15:25 전략 일일 요약 알림.
     */
    public void sendDailyStrategySummary(int runs, int evaluated, int bought, int sold,
                                         int errors, int skippedMarketWarn, int skippedMaxPositions,
                                         List<StrategyEngine.TradeRecord> boughtList,
                                         List<StrategyEngine.TradeRecord> soldList,
                                         List<StrategyEngine.SkipRecord> skippedList) {
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        StringBuilder sb = new StringBuilder(String.format(
                "📊 *[전략 일일 요약]* %s\n" +
                "> 실행 횟수: %d회 (09:05 ~ 15:20)\n" +
                "> 평가 종목: %d개 × %d회\n" +
                "> 매수: %d건  |  매도: %d건  |  오류: %d건\n" +
                "> 스킵(시장경보): %d건  |  스킵(최대보유): %d건",
                date, runs, evaluated, runs, bought, sold, errors,
                skippedMarketWarn, skippedMaxPositions
        ));
        if (!boughtList.isEmpty()) {
            String names = boughtList.stream()
                    .map(r -> formatStock(r.stockName(), r.ticker()) + " " + formatPrice(r.price()) + "원")
                    .collect(Collectors.joining(", "));
            sb.append("\n> 매수 종목: ").append(names);
        }
        if (!soldList.isEmpty()) {
            String names = soldList.stream()
                    .map(r -> formatStock(r.stockName(), r.ticker()) + " " + formatPrice(r.price()) + "원")
                    .collect(Collectors.joining(", "));
            sb.append("\n> 매도 종목: ").append(names);
        }
        send(sb.toString());
    }

    /**
     * 시간별 전략 실행 요약 알림 (매 정각 발송).
     */
    public void sendHourlySummary(int hour, int runCount, int evaluated,
                                   int bought, int sold, int errors,
                                   List<String> boughtTickers, List<String> skipLines,
                                   long noSignalCount) {
        String boughtLine = boughtTickers.isEmpty() ? ""
                : "\n> 매수종목: " + String.join(", ", boughtTickers);
        String skipBlock = skipLines.isEmpty()
                ? (noSignalCount == runCount ? "\n> BUY 신호 없음" : "")
                : skipLines.stream()
                        .map(line -> "\n> " + line)
                        .collect(Collectors.joining());
        String text = String.format(
                "🕐 *[시간별 요약 %02d:00]* 실행 %d회  |  평가 %d개\n" +
                "> 매수: %d건  ·  매도: %d건  ·  오류: %d건" +
                "%s%s",
                hour, runCount, evaluated, bought, sold, errors,
                boughtLine, skipBlock
        );
        send(text);
    }

    /**
     * ML 전략 매수 체결 알림.
     */
    public void sendMlBuy(String ticker, String stockName, java.math.BigDecimal entryPrice,
                          double confidence, double effectiveScore,
                          java.math.BigDecimal tp, java.math.BigDecimal sl,
                          int expectedDays, int maxDays) {
        String text = String.format(
                "✅ *[ML 예측 매수]* %s\n" +
                "> confidence: %.0f%%  |  score: %.1f\n" +
                "> 매수가: %s원  |  TP: %s원  |  SL: %s원\n" +
                "> 예상 보유: %d일 (최대 %d일)",
                formatStock(stockName, ticker),
                confidence * 100, effectiveScore,
                formatPrice(entryPrice), formatPrice(tp), formatPrice(sl),
                expectedDays, maxDays);
        send(text);
    }

    /**
     * ML 전략 청산 알림 (TP / SL / 최대보유).
     */
    public void sendMlExit(String ticker, String stockName, String tag,
                           java.math.BigDecimal entryPrice, java.math.BigDecimal currentPrice,
                           int daysHeld, java.math.BigDecimal tp, java.math.BigDecimal sl,
                           int maxDays, boolean success) {
        double roi = 0;
        if (entryPrice != null && entryPrice.compareTo(java.math.BigDecimal.ZERO) > 0) {
            roi = currentPrice.subtract(entryPrice)
                    .divide(entryPrice, 4, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
        }
        String emoji = "ML TP".equals(tag) ? "💰" : "ML SL".equals(tag) ? "🛑" : "⏱️";
        String text = String.format(
                "%s *[ML 예측 %s]* %s\n" +
                "> 수익률: %s%.2f%%  |  %d거래일 보유\n" +
                "> 매수: %s원 → 매도: %s원\n" +
                "> TP: %s원  |  SL: %s원  |  최대: %d일  |  주문: %s",
                emoji, tag, formatStock(stockName, ticker),
                roi >= 0 ? "+" : "", roi, daysHeld,
                formatPrice(entryPrice), formatPrice(currentPrice),
                formatPrice(tp), formatPrice(sl), maxDays, success ? "성공" : "실패");
        send(text);
    }

    /**
     * ML 재평가 알림 — expectedDays 경과 후 재추론 결과.
     */
    public void sendMlReEval(String ticker, String stockName,
                              java.math.BigDecimal entryPrice, java.math.BigDecimal currentPrice,
                              double newConfidence, double threshold,
                              java.math.BigDecimal newTp, java.math.BigDecimal newSl,
                              int elapsed, int maxDays, boolean extended) {
        double roi = 0;
        if (entryPrice != null && entryPrice.compareTo(java.math.BigDecimal.ZERO) > 0) {
            roi = currentPrice.subtract(entryPrice)
                    .divide(entryPrice, 4, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
        }
        String emoji    = extended ? "🔄" : "⚠️";
        String decision = extended ? "홀딩 연장" : "청산 결정";
        String text = String.format(
                "%s *[ML 재평가 | %s]* %s\n" +
                "> confidence: %.0f%% (기준 %.0f%%) → %s\n" +
                "> 수익률: %s%.2f%%  |  %d거래일 보유\n" +
                "> 매수: %s원  |  현재: %s원\n" +
                "> 새 TP: %s원  |  새 SL: %s원  |  최대: %d일",
                emoji, decision, formatStock(stockName, ticker),
                newConfidence * 100, threshold * 100, extended ? "연장" : "매도",
                roi >= 0 ? "+" : "", roi, elapsed,
                formatPrice(entryPrice), formatPrice(currentPrice),
                formatPrice(newTp), formatPrice(newSl), maxDays);
        send(text);
    }

    /**
     * ML 전략 — 연속 DEFER {@code count}회로 당일 블랙리스트 등록.
     */
    public void sendMlBlacklist(String ticker, String stockName, int count) {
        String text = String.format(
                "⛔ *[ML 블랙리스트]* %s\n> 연속 DEFER %d회 → 당일 추가 평가 제외",
                formatStock(stockName, ticker), count);
        send(text);
    }

    /**
     * 데이터 신선도 경고 — 3거래일(5캘린더일) 이상 수신되지 않은 데이터 소스 목록.
     */
    public void sendDataFreshnessAlert(List<String> staleItems) {
        if (staleItems.isEmpty()) return;
        String lines = staleItems.stream()
                .map(s -> "> • " + s)
                .collect(Collectors.joining("\n"));
        send("⚠️ *[데이터 신선도 경고]* 다음 데이터가 오래됐습니다:\n" + lines
                + "\n> ML 추론 정확도에 영향을 줄 수 있습니다.");
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
                    .timeout(Duration.ofSeconds(10))
                    .block();
            log.debug("[Slack] 발송 완료");
        } catch (Exception e) {
            log.error("[Slack] 알림 발송 실패: {}", e.getMessage());
        }
    }
}
