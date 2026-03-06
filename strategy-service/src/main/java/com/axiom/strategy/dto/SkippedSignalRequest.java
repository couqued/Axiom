package com.axiom.strategy.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class SkippedSignalRequest {
    private String ticker;
    private String stockName;
    private BigDecimal price;
    private String strategyName;
    private String marketState;
    private String skipReason;  // BUDGET_INSUFFICIENT | MAX_POSITIONS | MARKET_WARN
}
