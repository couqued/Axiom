package com.axiom.strategy.admin;

public record AdminStatusDto(
        String strategyMode,
        ModeSettingsDto settings,
        boolean indexDropBlockedToday,
        boolean indexDropCheckedToday,
        java.util.Map<String, com.axiom.strategy.service.BollingerReserveService.ReservationEntry> bollingerReservations
) {
    public record ModeSettingsDto(
            boolean paused,
            boolean sellPaused,
            int investAmountKrw,
            int maxPositions,
            double trailingStopPct,
            int timeCutDays,
            double indexDropBlockPct,
            int volatilityBreakoutDailyLimit,
            int goldenCrossDailyLimit,
            int bollingerDailyLimit,
            double profitTakePct,
            int mlDailyLimit,
            double mlBuyThreshold,
            boolean mlEntryTimingEnabled,
            boolean mlPaused,
            boolean mlSellPaused
    ) {}

    // Backward-compat accessors
    public boolean isPaused()            { return settings().paused(); }
    public int getInvestAmountKrw()      { return settings().investAmountKrw(); }
    public int getMaxPositions()         { return settings().maxPositions(); }
    public double getTrailingStopPct()   { return settings().trailingStopPct(); }
    public int getTimeCutDays()          { return settings().timeCutDays(); }
    public double getIndexDropBlockPct() { return settings().indexDropBlockPct(); }
}
