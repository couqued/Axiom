package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
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

    private String cachedRealToken;
    private Instant realTokenExpiry;

    public String getAccessToken() {
        if (needsRefresh()) {
            refreshToken();
        }
        return cachedToken;
    }

    private boolean needsRefresh() {
        return cachedToken == null || Instant.now().isAfter(tokenExpiry.minus(30, ChronoUnit.MINUTES));
    }

    /**
     * Real 서버 전용 Access Token 반환.
     * paper 모드에서 지수 조회 등 real 서버가 필요한 경우에 사용한다.
     * real 키가 미설정(PLACEHOLDER)이면 null 반환.
     */
    public String getRealAccessToken() {
        KisApiConfig.ModeConfig realConfig = kisApiConfig.getReal();
        if ("PLACEHOLDER".equals(realConfig.getAppKey())) {
            log.warn("[KIS] real 서버 키 미설정 — getRealAccessToken() null 반환");
            return null;
        }
        if (needsRealRefresh()) {
            refreshRealToken();
        }
        return cachedRealToken;
    }

    private boolean needsRealRefresh() {
        return cachedRealToken == null
                || Instant.now().isAfter(realTokenExpiry.minus(30, ChronoUnit.MINUTES));
    }

    private synchronized void refreshRealToken() {
        if (!needsRealRefresh()) return;

        KisApiConfig.ModeConfig realConfig = kisApiConfig.getReal();
        log.info("[KIS] Real Access Token 발급 요청");

        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey",     realConfig.getAppKey(),
                "appsecret",  realConfig.getAppSecret()
        );

        WebClient realClient = WebClient.builder()
                .baseUrl(realConfig.getBaseUrl())
                .build();

        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            try {
                Map<?, ?> response = realClient.post()
                        .uri("/oauth2/tokenP")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                cachedRealToken = (String) response.get("access_token");
                realTokenExpiry = Instant.now().plus(86400, ChronoUnit.SECONDS);
                log.info("[KIS] Real Access Token 발급 완료 (만료: 24시간 후)");
                return;
            } catch (Exception e) {
                if (i < maxRetry - 1) {
                    log.warn("[KIS] Real Access Token 발급 실패 ({}회), 3초 후 재시도: {}", i + 1, e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("[KIS] Real Access Token 발급 최종 실패: {}", e.getMessage());
                }
            }
        }
    }

    private synchronized void refreshToken() {
        if (!needsRefresh()) return;

        KisApiConfig.ModeConfig active = kisApiConfig.getActive();
        log.info("[KIS] Access Token 발급 요청 (mode: {})", kisApiConfig.getMode());

        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey",     active.getAppKey(),
                "appsecret",  active.getAppSecret()
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
                tokenExpiry = Instant.now().plus(86400, ChronoUnit.SECONDS);
                log.info("[KIS] Access Token 발급 완료 (만료: 24시간 후)");
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
