package com.axiom.market.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Set;

/**
 * portfolio-service HTTP 클라이언트.
 * 활성 보유 포지션 ticker 조회 — 워치리스트 자동 제거 시 보유 종목 보호용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioClient {

    @Qualifier("portfolioWebClient")
    private final WebClient portfolioWebClient;

    /**
     * 현재 활성 보유 종목 ticker 집합 반환. 실패 시 빈 set 반환.
     */
    public Set<String> getActiveTickers() {
        try {
            Set<String> result = portfolioWebClient.get()
                    .uri("/internal/positions/tickers")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Set<String>>() {})
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return result != null ? result : Set.of();
        } catch (Exception e) {
            log.warn("[PortfolioClient] 활성 ticker 조회 실패: {}", e.getMessage());
            return Set.of();
        }
    }
}
