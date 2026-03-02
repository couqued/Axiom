package com.axiom.strategy.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class OrderRequest {
    private String ticker;
    private Integer quantity;
    private BigDecimal price;
}
