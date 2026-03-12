package com.axiom.portfolio.service;

import com.axiom.portfolio.store.TradingModeStore;
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

    private final TradingModeStore tradingModeStore;

    @Value("${market-service.url}")
    private String marketServiceUrl;

    private String cachedToken;
    private Instant tokenExpiry;
    private String lastMode;

    public String getAccessToken() {
        String currentMode = tradingModeStore.getMode();
        if (!currentMode.equals(lastMode)) {
            cachedToken = null;
        }
        if (needsRefresh()) {
            refreshToken(currentMode);
        }
        return cachedToken;
    }

    private boolean needsRefresh() {
        return cachedToken == null || Instant.now().isAfter(tokenExpiry.minus(30, ChronoUnit.MINUTES));
    }

    private synchronized void refreshToken(String mode) {
        if (!needsRefresh() && mode.equals(lastMode)) return;

        log.info("[KIS] market-service에서 Access Token 조회 (mode: {})", mode);

        Map<?, ?> response = WebClient.builder()
                .baseUrl(marketServiceUrl)
                .build()
                .get()
                .uri(uriBuilder -> uriBuilder.path("/internal/token").queryParam("mode", mode).build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        cachedToken = (String) response.get("token");
        tokenExpiry = Instant.now().plus(86400, ChronoUnit.SECONDS);
        lastMode = mode;
        log.info("[KIS] Access Token 수신 완료 (mode: {}, market-service 위임)", mode);
    }
}
