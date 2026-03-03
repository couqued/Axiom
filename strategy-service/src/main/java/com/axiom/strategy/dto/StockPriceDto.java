package com.axiom.strategy.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * market-service의 현재가 API 응답 DTO.
 */
@Getter
@NoArgsConstructor
public class StockPriceDto {
    private String ticker;
    private String stockName;
    private BigDecimal currentPrice;
    private BigDecimal highPrice;    // 당일 고가
    private BigDecimal lowPrice;     // 당일 저가
    private BigDecimal openPrice;    // 시가
    private Long volume;
    private boolean mock;
}
