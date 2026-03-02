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

    public String getAccessToken() {
        if (needsRefresh()) {
            refreshToken();
        }
        return cachedToken;
    }

    private boolean needsRefresh() {
        return cachedToken == null || Instant.now().isAfter(tokenExpiry.minus(30, ChronoUnit.MINUTES));
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
