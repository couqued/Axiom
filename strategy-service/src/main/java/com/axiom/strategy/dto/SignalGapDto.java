package com.axiom.strategy.dto;

public record SignalGapDto(
        int rank,
        String ticker,
        String stockName,
        String strategy,      // "rsi-bollinger" | "volatility-breakout"
        double currentPrice,
        double threshold,     // 하단밴드 or 목표가
        double gapPct,        // (currentPrice - threshold) / currentPrice × 100
        double rsi,           // RSI+볼린저 시에만 유효, 나머지 -1
        double bbUpper,       // 볼린저 상단 (익절목표)
        double score,         // 예상 진입 점수
        String detail         // 사람이 읽을 설명
) {}
