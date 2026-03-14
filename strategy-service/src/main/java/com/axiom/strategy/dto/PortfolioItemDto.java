package com.axiom.strategy.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * portfolio-service의 보유 포지션 DTO.
 */
@Getter
@NoArgsConstructor
public class PortfolioItemDto {
    private String ticker;
    private String stockName;
    private Integer quantity;
    private BigDecimal avgPrice;
    private BigDecimal totalInvest;
    private Integer buyStage; // 1: 1차매수, 2: 2차/통합매수

    public PortfolioItemDto withBuyStage(Integer stage) {
        this.buyStage = stage;
        return this;
    }
}
