package com.axiom.strategy.ml;

public record ConfidenceTierDto(
        String tier,
        int    tradeCount,
        int    winCount,
        double winRate,
        double avgReturnPct
) {}
