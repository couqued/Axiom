package com.axiom.strategy.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * order-service · portfolio-service에 거래 모드 변경을 전파한다.
 */
@Slf4j
@Component
public class ModeClient {

    @Value("${order-service.url}")
    private String orderServiceUrl;

    @Value("${portfolio-service.url}")
    private String portfolioServiceUrl;

    @Value("${market-service.url}")
    private String marketServiceUrl;

    public void propagateTradingMode(String mode) {
        propagateTo(orderServiceUrl, "order-service", mode);
        propagateTo(portfolioServiceUrl, "portfolio-service", mode);
        propagateTo(marketServiceUrl, "market-service", mode);
    }

    private void propagateTo(String baseUrl, String serviceName, String mode) {
        try {
            WebClient.builder().baseUrl(baseUrl).build()
                    .patch()
                    .uri("/internal/trading-mode")
                    .bodyValue(Map.of("mode", mode))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            log.info("[ModeClient] {} tradingMode 전파 완료 → {}", serviceName, mode);
        } catch (Exception e) {
            log.warn("[ModeClient] {} tradingMode 전파 실패 (계속 진행): {}", serviceName, e.getMessage());
        }
    }
}
