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
        Integer bollingerDailyLimit,
        Double profitTakePct,
        Integer mlDailyLimit,
        Double mlBuyThreshold,
        Boolean mlEntryTimingEnabled,
        Boolean mlPaused,
        Boolean mlSellPaused,
        // VolBreakout 전용 매도 보강 설정 (0=비활성)
        Double volBreakoutTakeProfitPct,
        Double volBreakoutStopLossPct,
        Double volBreakoutIntradayTrailingPct
) {}
