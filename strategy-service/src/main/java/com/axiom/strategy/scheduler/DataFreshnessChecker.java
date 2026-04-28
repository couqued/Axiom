package com.axiom.strategy.scheduler;

import com.axiom.strategy.client.MlClient;
import com.axiom.strategy.notification.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 매일 09:00 KST 데이터 신선도 체크.
 * yfinance 글로벌 데이터(NASDAQ/S&P/USD/KRW)와 KIS 캔들 데이터가
 * 5캘린더일(≈3거래일) 이상 오래됐으면 Slack 경고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataFreshnessChecker {

    private static final int STALE_THRESHOLD_DAYS = 5;

    private final MlClient      mlClient;
    private final SlackNotifier slackNotifier;

    @Value("${market-service.url:http://localhost:8081}")
    private String marketServiceUrl;

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul")
    public void checkFreshness() {
        log.info("[DataFreshnessChecker] 데이터 신선도 체크 시작");
        List<String> staleItems = new ArrayList<>();

        checkCandleFreshness(staleItems);
        checkGlobalDataFreshness(staleItems);

        if (!staleItems.isEmpty()) {
            log.warn("[DataFreshnessChecker] 오래된 데이터 감지: {}", staleItems);
            slackNotifier.sendDataFreshnessAlert(staleItems);
        } else {
            log.info("[DataFreshnessChecker] 모든 데이터 최신 상태");
        }
    }

    private void checkCandleFreshness(List<String> staleItems) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> status = WebClient.builder()
                    .baseUrl(marketServiceUrl)
                    .build()
                    .get()
                    .uri("/internal/data-status")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (status == null) {
                staleItems.add("KIS 캔들 데이터 (market-service 응답 없음)");
                return;
            }
            String latestStr = (String) status.get("latestCandleDate");
            if ("unknown".equals(latestStr) || latestStr == null) {
                staleItems.add("KIS 캔들 데이터 (DB에 데이터 없음)");
                return;
            }
            LocalDate latest = LocalDate.parse(latestStr);
            long daysAgo = LocalDate.now().toEpochDay() - latest.toEpochDay();
            if (daysAgo > STALE_THRESHOLD_DAYS) {
                staleItems.add(String.format("KIS 캔들 데이터 (최신: %s, %d일 경과)", latestStr, daysAgo));
            }
        } catch (Exception e) {
            log.warn("[DataFreshnessChecker] 캔들 신선도 체크 실패: {}", e.getMessage());
            staleItems.add("KIS 캔들 데이터 (체크 실패: " + e.getMessage() + ")");
        }
    }

    private void checkGlobalDataFreshness(List<String> staleItems) {
        try {
            MlClient.ModelStatusDto status = mlClient.getModelStatus();
            if (status == null || status.globalDataFreshness() == null || status.globalDataFreshness().isEmpty()) {
                staleItems.add("글로벌 데이터 (ml-service 응답 없음 또는 캐시 비어 있음)");
                return;
            }
            Map<String, String> nameMap = Map.of(
                    "nasdaq",  "NASDAQ",
                    "sp500",   "S&P500",
                    "usdkrw",  "USD/KRW 환율"
            );
            status.globalDataFreshness().forEach((key, dateStr) -> {
                String label = nameMap.getOrDefault(key, key);
                if (dateStr == null) {
                    staleItems.add(label + " (데이터 없음)");
                    return;
                }
                try {
                    LocalDate latest = LocalDate.parse(dateStr);
                    long daysAgo = LocalDate.now().toEpochDay() - latest.toEpochDay();
                    if (daysAgo > STALE_THRESHOLD_DAYS) {
                        staleItems.add(String.format("%s (최신: %s, %d일 경과)", label, dateStr, daysAgo));
                    }
                } catch (Exception ex) {
                    staleItems.add(label + " (날짜 파싱 실패: " + dateStr + ")");
                }
            });
        } catch (Exception e) {
            log.warn("[DataFreshnessChecker] 글로벌 데이터 신선도 체크 실패: {}", e.getMessage());
            staleItems.add("글로벌 데이터 (체크 실패: " + e.getMessage() + ")");
        }
    }
}
