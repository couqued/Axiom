package com.axiom.strategy.admin;

import com.axiom.strategy.config.StrategyConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminConfigStore {

    private final StrategyConfig strategyConfig;
    private final ObjectMapper objectMapper;

    private volatile String strategyMode = "market-based"; // "market-based" | "all-strategies"
    private volatile ModeSettings settings;

    @Value("${admin.config-path:admin-config.json}")
    private String configFilePath;

    @PostConstruct
    void init() {
        int    defaultInvest    = strategyConfig.getPositionSizing().getInvestAmountKrw();
        int    defaultMaxPos    = strategyConfig.getPositionSizing().getMaxPositions();
        double defaultTs        = strategyConfig.getTrailingStop().getStopPercent();
        int    defaultTc        = strategyConfig.getTimeCut().getMaxHoldingDays();
        double defaultIdx       = 1.0;

        settings = new ModeSettings(false, defaultInvest, defaultMaxPos, defaultTs, defaultTc, defaultIdx, defaultMaxPos, defaultMaxPos, defaultMaxPos, 0.0);
        loadFromFile();
    }

    public record ModeSettings(boolean paused, int investAmountKrw, int maxPositions,
                               double trailingStopPct, int timeCutDays, double indexDropBlockPct,
                               int volatilityBreakoutDailyLimit,
                               int goldenCrossDailyLimit,
                               int bollingerDailyLimit,
                               double profitTakePct) {}

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getStrategyMode()        { return strategyMode; }
    public ModeSettings getSettings()      { return settings; }

    // Delegation to settings
    public boolean isPaused()                  { return settings.paused(); }
    public int getInvestAmountKrw()            { return settings.investAmountKrw(); }
    public int getMaxPositions()               { return settings.maxPositions(); }
    public double getTrailingStopPct()         { return settings.trailingStopPct(); }
    public int getTimeCutDays()                { return settings.timeCutDays(); }
    public double getIndexDropBlockPct()       { return settings.indexDropBlockPct(); }
    public int getVolatilityBreakoutDailyLimit()    { return settings.volatilityBreakoutDailyLimit(); }
    public int getGoldenCrossDailyLimit()           { return settings.goldenCrossDailyLimit(); }
    public int getBollingerDailyLimit()             { return settings.bollingerDailyLimit(); }
    public double getProfitTakePct()                { return settings.profitTakePct(); }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setStrategyMode(String mode) {
        this.strategyMode = "all-strategies".equals(mode) ? "all-strategies" : "market-based";
        saveToFile();
        log.info("[AdminConfig] strategyMode 변경 → {}", this.strategyMode);
    }

    public void setPaused(boolean paused) {
        ModeSettings s = settings;
        updateSettings(paused,
                s.investAmountKrw(), s.maxPositions(), s.trailingStopPct(), s.timeCutDays(),
                s.indexDropBlockPct(),
                s.volatilityBreakoutDailyLimit(), s.goldenCrossDailyLimit(), s.bollingerDailyLimit(),
                s.profitTakePct());
    }

    public void setConfig(int investAmountKrw, int maxPositions,
                          double trailingStopPct, int timeCutDays, double indexDropBlockPct,
                          int volatilityBreakoutDailyLimit, int goldenCrossDailyLimit, int bollingerDailyLimit,
                          double profitTakePct) {
        updateSettings(settings.paused(),
                investAmountKrw, maxPositions, trailingStopPct, timeCutDays, indexDropBlockPct,
                volatilityBreakoutDailyLimit, goldenCrossDailyLimit, bollingerDailyLimit, profitTakePct);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void updateSettings(boolean paused,
                                int investAmountKrw, int maxPositions,
                                double trailingStopPct, int timeCutDays, double indexDropBlockPct,
                                int volatilityBreakoutDailyLimit, int goldenCrossDailyLimit, int bollingerDailyLimit,
                                double profitTakePct) {
        settings = new ModeSettings(paused, investAmountKrw, maxPositions,
                trailingStopPct, timeCutDays, indexDropBlockPct,
                volatilityBreakoutDailyLimit, goldenCrossDailyLimit, bollingerDailyLimit, profitTakePct);
        saveToFile();
    }

    private void saveToFile() {
        try {
            Snapshot snapshot = new Snapshot(strategyMode, settings);
            objectMapper.writeValue(new File(configFilePath), snapshot);
            log.info("[AdminConfig] 저장 완료");
        } catch (IOException e) {
            log.error("[AdminConfig] 저장 실패: {}", e.getMessage());
        }
    }

    private void loadFromFile() {
        File file = new File(configFilePath);
        if (!file.exists()) return;
        try {
            JsonNode node = objectMapper.readTree(file);
            if (node.has("strategyMode")) this.strategyMode = node.get("strategyMode").asText(this.strategyMode);

            if (node.has("settings")) {
                settings = loadModeSettings(node.get("settings"), settings);
            } else {
                // 플랫 포맷: settings 노드 없이 최상위에 직접 필드가 있는 경우
                settings = loadModeSettings(node, settings);
                saveToFile(); // 새 포맷으로 재저장
                log.info("[AdminConfig] 플랫 포맷 마이그레이션 완료 → 새 포맷으로 재저장");
            }
            log.info("[AdminConfig] 로드 완료 — strategyMode={}, paused={}", strategyMode, settings.paused());
        } catch (IOException e) {
            log.warn("[AdminConfig] 로드 실패 (기본값 사용): {}", e.getMessage());
        }
    }

    private ModeSettings loadModeSettings(JsonNode node, ModeSettings def) {
        boolean paused   = node.has("paused")               ? node.get("paused").asBoolean(def.paused())                     : def.paused();
        int invest       = node.has("investAmountKrw")      ? node.get("investAmountKrw").asInt(def.investAmountKrw())        : def.investAmountKrw();
        int maxPos       = node.has("maxPositions")         ? node.get("maxPositions").asInt(def.maxPositions())              : def.maxPositions();
        double ts        = node.has("trailingStopPct")      ? node.get("trailingStopPct").asDouble(def.trailingStopPct())     : def.trailingStopPct();
        int tc           = node.has("timeCutDays")          ? node.get("timeCutDays").asInt(def.timeCutDays())                : def.timeCutDays();
        double idx       = node.has("indexDropBlockPct")    ? node.get("indexDropBlockPct").asDouble(def.indexDropBlockPct()) : def.indexDropBlockPct();
        int volDaily     = node.has("volatilityBreakoutDailyLimit")
                ? node.get("volatilityBreakoutDailyLimit").asInt(def.volatilityBreakoutDailyLimit())
                : maxPos;
        int gcDaily      = node.has("goldenCrossDailyLimit")
                ? node.get("goldenCrossDailyLimit").asInt(def.goldenCrossDailyLimit())
                : maxPos;
        int bollDaily    = node.has("bollingerDailyLimit")
                ? node.get("bollingerDailyLimit").asInt(def.bollingerDailyLimit())
                : maxPos;
        double profitTake = node.has("profitTakePct")
                ? node.get("profitTakePct").asDouble(def.profitTakePct())
                : def.profitTakePct();
        return new ModeSettings(paused, invest, maxPos, ts, tc, idx, volDaily, gcDaily, bollDaily, profitTake);
    }

    public record Snapshot(String strategyMode, ModeSettings settings) {}
}
