package com.axiom.strategy.client;

import com.axiom.strategy.dto.PortfolioItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * portfolio-service HTTP 클라이언트.
 * 보유 포지션 조회 (트레일링 스탑, 타임 컷, 강제 청산에 사용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioClient {

    @Qualifier("portfolioWebClient")
    private final WebClient portfolioWebClient;

    /**
     * 외부 매도(MTS 등) 후 포트폴리오 DB에서 포지션 삭제.
     */
    public void deletePosition(String ticker, String mode) {
        try {
            portfolioWebClient.delete()
                    .uri("/api/portfolio/admin/by-ticker?ticker={t}&mode={m}", ticker, mode)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.error("[PortfolioClient] 포지션 삭제 실패 - ticker: {}, error: {}", ticker, e.getMessage());
        }
    }

    /**
     * 현재 보유 중인 종목 목록 조회.
     */
    public List<PortfolioItemDto> getPositions() {
        try {
            return portfolioWebClient.get()
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
