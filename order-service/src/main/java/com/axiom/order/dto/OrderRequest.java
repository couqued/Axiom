package com.axiom.order.dto;

import com.axiom.order.entity.TradeOrder.OrderType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class OrderRequest {
    private String ticker;
    private String stockName;
    private OrderType orderType;  // BUY or SELL
    private Integer quantity;
    private BigDecimal price;     // 지정가 (null이면 시장가)
    private String strategyName;
    private String marketState;
    private String closeReason;
}
