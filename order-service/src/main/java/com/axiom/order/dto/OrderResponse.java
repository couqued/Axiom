package com.axiom.order.dto;

import com.axiom.order.entity.TradeOrder;
import com.axiom.order.entity.TradeOrder.OrderStatus;
import com.axiom.order.entity.TradeOrder.OrderType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class OrderResponse {
    private Long id;
    private String ticker;
    private String stockName;
    private OrderType orderType;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal totalAmount;
    private OrderStatus status;
    private String kisOrderId;
    private String strategyName;
    private String marketState;
    private String closeReason;
    private LocalDateTime createdAt;
    private LocalDateTime filledAt;
    private boolean mock;

    public static OrderResponse from(TradeOrder order) {
        return OrderResponse.builder()
                .id(order.getId())
                .ticker(order.getTicker())
                .stockName(order.getStockName())
                .orderType(order.getOrderType())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .kisOrderId(order.getKisOrderId())
                .strategyName(order.getStrategyName())
                .marketState(order.getMarketState())
                .closeReason(order.getCloseReason())
                .createdAt(order.getCreatedAt())
                .filledAt(order.getFilledAt())
                .build();
    }
}
