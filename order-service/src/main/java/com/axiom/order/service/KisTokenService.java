package com.axiom.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * order-service 전용 토큰 조회 클라이언트.
 * 토큰 캐시·갱신은 market-service가 전담하므로 여기서는 매 요청마다 위임한다.
 */
@Slf4j
@Service
public class KisTokenService {

    @Value("${market-service.url}")
    private String marketServiceUrl;

    public String getAccessToken() {
        Map<?, ?> response = WebClient.builder()
                .baseUrl(marketServiceUrl)
                .build()
                .get()
                .uri("/internal/token")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        String token = (String) response.get("token");
        log.debug("[KIS] Access Token 조회 완료");
        return token;
    }

    /** market-service 토큰 캐시를 강제 무효화하고 새 토큰을 발급받아 반환한다. */
    public String forceRefreshAndGetToken() {
        log.info("[KIS] 토큰 강제 갱신 요청");
        Map<?, ?> response = WebClient.builder()
                .baseUrl(marketServiceUrl)
                .build()
                .post()
                .uri("/internal/token/refresh")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        String token = (String) response.get("token");
        log.info("[KIS] 토큰 강제 갱신 완료");
        return token;
    }
}
