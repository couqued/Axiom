package com.axiom.order.service;

import com.axiom.order.config.KisApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisTokenService {

    private final KisApiConfig kisApiConfig;

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
        return cachedToken == null || Instant.now().isAfter(tokenExpiry.minus(30, ChronoUnit.MINUTES));
    }

    private synchronized void refreshToken() {
        if (!needsRefresh()) return;

        log.info("[KIS] market-service에서 Access Token 조회 (mode: {})", kisApiConfig.getMode());

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
        log.info("[KIS] Access Token 수신 완료 (market-service 위임)");
    }
}
