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
    private String entryTag; // "EXTREME_FEAR" 등

    // ML 전략 전용 — entryTag === 'ml-prediction' 일 때만 설정
    private Double mlConfidence;
    private Double mlScore;
    private BigDecimal mlTakeProfitPrice;
    private BigDecimal mlStopLossPrice;
    private Integer mlExpectedDays;
    private Integer mlRemainingDays; // 예상 종료까지 남은 거래일 (0=오늘 재평가)

    public PortfolioItemDto withBuyStage(Integer stage) {
        this.buyStage = stage;
        return this;
    }

    public PortfolioItemDto withEntryTag(String tag) {
        this.entryTag = tag;
        return this;
    }

    public PortfolioItemDto withMlPlan(double confidence, double score,
                                       BigDecimal takeProfitPrice, BigDecimal stopLossPrice,
                                       int expectedDays, int remainingDays) {
        this.mlConfidence      = confidence;
        this.mlScore           = score;
        this.mlTakeProfitPrice = takeProfitPrice;
        this.mlStopLossPrice   = stopLossPrice;
        this.mlExpectedDays    = expectedDays;
        this.mlRemainingDays   = Math.max(0, remainingDays);
        return this;
    }
}
