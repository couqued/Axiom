package com.axiom.strategy.admin;

public record AdminConfigDto(Integer investAmountKrw, Integer maxPositions,
                             Double trailingStopPct, Integer timeCutDays) {}
