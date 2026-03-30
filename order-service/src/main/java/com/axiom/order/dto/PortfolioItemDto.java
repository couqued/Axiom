package com.axiom.order.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PortfolioItemDto {
    private String ticker;
    private String stockName;
    private Integer quantity;
}
