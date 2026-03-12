package com.axiom.portfolio.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "portfolio", schema = "portfolio",
    uniqueConstraints = @UniqueConstraint(name = "UK_ticker_mode", columnNames = {"ticker", "trading_mode"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(nullable = false, length = 50)
    private String stockName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal avgPrice;       // 평균 매수 단가

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalInvest;    // 총 투자금액

    @Column(name = "trading_mode", length = 10)
    private String tradingMode;        // "paper" | "real"

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
