package com.axiom.portfolio.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
public class KisTokenService {

    @Value("${market-service.url}")
    private String marketServiceUrl;

    private String cachedToken;
    private Instant tokenExpiry;

    public String getAccessToken() {
        if (needsRefresh()) {
            refreshToken();
        }
        return cachedToken;
    }

    private boolean needsRefresh() {
        return cachedToken == null || tokenExpiry == null
                || Instant.now().isAfter(tokenExpiry.minus(30, ChronoUnit.MINUTES));
    }

    /** KIS에서 토큰 만료 오류 수신 시 강제 무효화 후 재발급 */
    public synchronized void forceRefresh() {
        log.info("[KIS] 토큰 만료 감지 — market-service 강제 재발급 요청");
        cachedToken = null;
        tokenExpiry = null;

        Map<?, ?> response = WebClient.builder()
                .baseUrl(marketServiceUrl)
                .build()
                .post()
                .uri("/internal/token/refresh")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        cachedToken = (String) response.get("token");
        tokenExpiry = Instant.now().plus(86400, ChronoUnit.SECONDS);
        log.info("[KIS] Access Token 강제 재발급 완료");
    }

    private synchronized void refreshToken() {
        if (!needsRefresh()) return;

        log.info("[KIS] market-service에서 Access Token 조회");

        Map<?, ?> response = WebClient.builder()
                .baseUrl(marketServiceUrl)
                .build()
                .get()
                .uri("/internal/token")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        cachedToken = (String) response.get("token");
        tokenExpiry = Instant.now().plus(86400, ChronoUnit.SECONDS);
        log.info("[KIS] Access Token 수신 완료");
    }
}
