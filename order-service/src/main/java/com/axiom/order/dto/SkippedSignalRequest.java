package com.axiom.order.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkippedSignalRequest {
    private String ticker;
    private String stockName;
    private BigDecimal price;
    private String strategyName;
    private String marketState;
    private String skipReason;  // BUDGET_INSUFFICIENT | MAX_POSITIONS | MARKET_WARN
}
