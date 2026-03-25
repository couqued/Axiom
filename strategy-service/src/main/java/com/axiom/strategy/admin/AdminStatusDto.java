package com.axiom.strategy.admin;

public record AdminStatusDto(
        String tradingMode,
        String strategyMode,
        ModeSettingsDto paper,
        ModeSettingsDto real,
        boolean indexDropBlockedToday,
        boolean indexDropCheckedToday,
        java.util.Map<String, com.axiom.strategy.service.BollingerReserveService.ReservationEntry> bollingerReservations
) {
    public record ModeSettingsDto(
            boolean paused,
            int investAmountKrw,
            int maxPositions,
            double trailingStopPct,
            int timeCutDays,
            double indexDropBlockPct,
            int volatilityBreakoutDailyLimit,
            int goldenCrossDailyLimit,
            int bollingerDailyLimit
    ) {}

    /** 현재 활성 모드의 설정 반환 */
    public ModeSettingsDto activeSettings() {
        return "real".equals(tradingMode) ? real : paper;
    }

    // Backward-compat accessors (active 모드 기준)
    public boolean isPaused()            { return activeSettings().paused(); }
    public int getInvestAmountKrw()      { return activeSettings().investAmountKrw(); }
    public int getMaxPositions()         { return activeSettings().maxPositions(); }
    public double getTrailingStopPct()   { return activeSettings().trailingStopPct(); }
    public int getTimeCutDays()          { return activeSettings().timeCutDays(); }
    public double getIndexDropBlockPct() { return activeSettings().indexDropBlockPct(); }
}
