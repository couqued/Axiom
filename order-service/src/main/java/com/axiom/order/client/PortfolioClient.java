package com.axiom.order.client;

import com.axiom.order.dto.PortfolioItemDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Slf4j
@Component
public class PortfolioClient {

    @Value("${portfolio-service.url}")
    private String portfolioServiceUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(portfolioServiceUrl).build();
    }

    public List<PortfolioItemDto> getPositions() {
        try {
            return webClient.get()
                    .uri("/api/portfolio")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<PortfolioItemDto>>() {})
                    .block();
        } catch (Exception e) {
            log.error("[PortfolioClient] 포지션 조회 실패 - error: {}", e.getMessage());
            return List.of();
        }
    }
}
