package com.axiom.market.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
    name = "daily_candles",
    schema = "market",
    uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "trade_date"})
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal openPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal highPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal lowPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal closePrice;

    @Column(nullable = false)
    private Long volume;
}
