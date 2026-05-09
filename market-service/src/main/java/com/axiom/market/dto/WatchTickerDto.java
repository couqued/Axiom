package com.axiom.market.dto;

import com.axiom.market.entity.WatchTicker;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WatchTickerDto(
        String ticker,
        String stockName,
        String marketIndex,
        BigDecimal marketCap,
        BigDecimal avgTurnover20d,
        String status,
        LocalDateTime addedAt,
        LocalDateTime lastReviewedAt,
        String removalReason,
        boolean pendingRemoval
) {
    public static WatchTickerDto from(WatchTicker w) {
        return new WatchTickerDto(
                w.getTicker(),
                w.getStockName(),
                w.getMarketIndex(),
                w.getMarketCap(),
                w.getAvgTurnover20d(),
                w.getStatus().name(),
                w.getAddedAt(),
                w.getLastReviewedAt(),
                w.getRemovalReason(),
                w.isPendingRemoval()
        );
    }
}
