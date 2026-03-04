package com.axiom.strategy.client;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.StockPriceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketClient {

    @Qualifier("marketWebClient")
    private final WebClient marketWebClient;

    /**
     * market-service에서 종목 일봉 데이터 조회.
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

    /**
     * market-service에서 종목 현재가 조회 (시가, 고가, 저가, 현재가 포함).
     */
    public StockPriceDto getCurrentPrice(String ticker) {
        try {
            return marketWebClient.get()
                    .uri("/api/stocks/{ticker}/price", ticker)
                    .retrieve()
                    .bodyToMono(StockPriceDto.class)
                    .block();
        } catch (Exception e) {
            log.error("[MarketClient] 현재가 조회 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            return null;
        }
    }

    /**
     * market-service에서 스크리닝된 감시 종목 목록 조회.
     * 코스피200 + 코스닥150 유니버스 기반. 실패 시 빈 리스트 반환 → StrategyEngine이 yml fallback 사용.
     */
    public List<String> getScreenedTickers() {
        try {
            return marketWebClient.get()
                    .uri("/internal/screened-tickers")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                    .block();
        } catch (Exception e) {
            log.warn("[MarketClient] screened-tickers 조회 실패 — yml fallback 사용: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * market-service에서 지수 일봉 데이터 조회 (시장 상태 판별용).
     *
     * @param indexCode 지수 코드 ("0001" = 코스피, "1001" = 코스닥)
     */
    public List<CandleDto> getIndexCandles(String indexCode, int days) {
        try {
            return marketWebClient.get()
                    .uri("/api/index/{code}/candles?days={days}", indexCode, days)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<CandleDto>>() {})
                    .block();
        } catch (Exception e) {
            log.error("[MarketClient] 지수 캔들 조회 실패 - indexCode: {}, error: {}", indexCode, e.getMessage());
            return List.of();
        }
    }
}
