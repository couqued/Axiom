package com.axiom.market.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "watch_tickers", schema = "market")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchTicker {

    public enum Status { ACTIVE, EXCLUDED_AUTO, EXCLUDED_MANUAL, REMOVED }

    @Id
    @Column(length = 10)
    private String ticker;

    @Column(name = "stock_name", nullable = false)
    private String stockName;

    @Column(name = "market_index", nullable = false, length = 10)
    private String marketIndex;     // KOSPI200 | KOSDAQ150

    @Column(name = "market_cap", precision = 20, scale = 0)
    private BigDecimal marketCap;

    @Column(name = "avg_turnover_20d", precision = 20, scale = 0)
    private BigDecimal avgTurnover20d;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "removal_reason", length = 200)
    private String removalReason;

    @Column(name = "pending_removal", nullable = false)
    private boolean pendingRemoval;
}
