package com.axiom.portfolio.dto;

import com.axiom.portfolio.entity.Portfolio;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class PortfolioItemDto {
    private Long id;
    private String ticker;
    private String stockName;
    private Integer quantity;
    private BigDecimal avgPrice;
    private BigDecimal totalInvest;
    private LocalDateTime updatedAt;

    public static PortfolioItemDto from(Portfolio p) {
        return PortfolioItemDto.builder()
                .id(p.getId())
                .ticker(p.getTicker())
                .stockName(p.getStockName())
                .quantity(p.getQuantity())
                .avgPrice(p.getAvgPrice())
                .totalInvest(p.getTotalInvest())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
