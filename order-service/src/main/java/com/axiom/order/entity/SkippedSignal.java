package com.axiom.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "skipped_signals",
    schema = "orders",
    uniqueConstraints = @UniqueConstraint(columnNames = {"trade_date", "ticker", "skip_reason"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkippedSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(length = 100)
    private String stockName;

    @Column(precision = 15, scale = 2)
    private BigDecimal price;

    @Column(length = 50)
    private String strategyName;    // "golden-cross", "rsi-bollinger", "volatility-breakout"

    @Column(length = 20)
    private String marketState;     // "BULLISH", "SIDEWAYS"

    @Column(name = "skip_reason", nullable = false, length = 50)
    private String skipReason;      // "BUDGET_INSUFFICIENT", "MAX_POSITIONS", "MARKET_WARN"

    @Column(nullable = false)
    private Integer skipCount;

    @Column(nullable = false)
    private LocalDateTime firstSkippedAt;

    @Column(nullable = false)
    private LocalDateTime lastSkippedAt;
}
