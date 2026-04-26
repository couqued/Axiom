package com.axiom.strategy.ml;

public record MlSummaryDto(
        String  modelTrainedAt,
        Integer samples,
        Double  valAuc,
        Double  valMaeRet,
        Double  valMaeDays,
        int     totalTrades,
        int     winCount,
        int     lossCount,
        double  winRate,
        double  avgActualReturnPct
) {}
