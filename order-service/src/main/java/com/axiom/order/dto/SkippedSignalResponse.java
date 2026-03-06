package com.axiom.order.dto;

import com.axiom.order.entity.SkippedSignal;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class SkippedSignalResponse {
    private Long id;
    private LocalDate tradeDate;
    private String ticker;
    private String stockName;
    private BigDecimal price;
    private String strategyName;
    private String marketState;
    private String skipReason;
    private Integer skipCount;
    private LocalDateTime firstSkippedAt;
    private LocalDateTime lastSkippedAt;

    public static SkippedSignalResponse from(SkippedSignal e) {
        return SkippedSignalResponse.builder()
                .id(e.getId())
                .tradeDate(e.getTradeDate())
                .ticker(e.getTicker())
                .stockName(e.getStockName())
                .price(e.getPrice())
                .strategyName(e.getStrategyName())
                .marketState(e.getMarketState())
                .skipReason(e.getSkipReason())
                .skipCount(e.getSkipCount())
                .firstSkippedAt(e.getFirstSkippedAt())
                .lastSkippedAt(e.getLastSkippedAt())
                .build();
    }
}
