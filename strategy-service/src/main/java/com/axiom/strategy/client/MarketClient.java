package com.axiom.strategy.client;

import com.axiom.strategy.dto.CandleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketClient {

    @Qualifier("marketWebClient")
    private final WebClient marketWebClient;

    /**
     * market-service에서 일봉 데이터 조회.
     */
    public List<CandleDto> getCandles(String ticker, int days) {
        try {
            return marketWebClient.get()
                    .uri("/api/stocks/{ticker}/candles?days={days}", ticker, days)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<CandleDto>>() {})
                    .block();
        } catch (Exception e) {
            log.error("[MarketClient] 캔들 조회 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            return List.of();
        }
    }
}
