package com.axiom.strategy.admin;

public record AdminConfigDto(
        String tradingMode,      // "paper" | "real" — 모드 전환 시 사용
        String targetMode,       // 어떤 모드의 설정을 변경할지 ("paper"|"real"), null이면 active 모드
        Integer investAmountKrw,
        Integer maxPositions,
        Double trailingStopPct,
        Integer timeCutDays,
        Double indexDropBlockPct,
        Integer bollingerMaxPositions
) {}
