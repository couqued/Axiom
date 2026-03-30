package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisTokenService {

    private final WebClient kisWebClient;
    private final KisApiConfig kisApiConfig;

    private String cachedToken;
    private Instant tokenExpiry;

    public String getAccessToken() {
        if (needsRefresh()) {
            refreshToken();
        }
        return cachedToken;
    }

    private boolean needsRefresh() {
        return cachedToken == null || Instant.now().isAfter(tokenExpiry.minus(30, ChronoUnit.MINUTES));
    }

    /** 토큰 캐시를 강제로 무효화하고 즉시 재발급한다. */
    public synchronized void forceRefresh() {
        cachedToken = null;
        tokenExpiry = null;
        refreshToken();
    }

    private Instant parseExpiry(String expiryStr) {
        if (expiryStr != null && !expiryStr.isBlank()) {
            try {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                return LocalDateTime.parse(expiryStr, fmt)
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toInstant();
            } catch (Exception e) {
                log.warn("[KIS] access_token_token_expired 파싱 실패 — 24h 폴백: {}", expiryStr);
            }
        }
        return Instant.now().plus(86400, ChronoUnit.SECONDS);
    }

    private synchronized void refreshToken() {
        if (!needsRefresh()) return;

        KisApiConfig.ModeConfig config = kisApiConfig.getReal();
        log.info("[KIS] Access Token 발급 요청");

        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey",     config.getAppKey(),
                "appsecret",  config.getAppSecret()
        );

        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            try {
                Map<?, ?> response = kisWebClient.post()
                        .uri("/oauth2/tokenP")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                cachedToken = (String) response.get("access_token");
                tokenExpiry = parseExpiry((String) response.get("access_token_token_expired"));
                log.info("[KIS] Access Token 발급 완료 (만료: {})", tokenExpiry);
                return;
            } catch (Exception e) {
                if (i < maxRetry - 1) {
                    log.warn("[KIS] Access Token 발급 실패 ({}회), 3초 후 재시도: {}", i + 1, e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("[KIS] Access Token 발급 최종 실패: {}", e.getMessage());
                    throw new RuntimeException("KIS Access Token 발급 실패", e);
                }
            }
        }
    }
}
