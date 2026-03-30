package com.axiom.strategy.admin;

public record AdminConfigDto(
        String strategyMode,     // "market-based" | "all-strategies"
        Integer investAmountKrw,
        Integer maxPositions,
        Double trailingStopPct,
        Integer timeCutDays,
        Double indexDropBlockPct,
        Integer volatilityBreakoutDailyLimit,
        Integer goldenCrossDailyLimit,
        Integer bollingerDailyLimit
) {}
