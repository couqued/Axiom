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

    private volatile String tradingMode = "paper";
    private volatile String strategyMode = "market-based"; // "market-based" | "all-strategies"
    private volatile ModeSettings paperSettings;
    private volatile ModeSettings realSettings;

    @Value("${admin.config-path:admin-config.json}")
    private String configFilePath;

    @PostConstruct
    void init() {
        int    defaultInvest    = strategyConfig.getPositionSizing().getInvestAmountKrw();
        int    defaultMaxPos    = strategyConfig.getPositionSizing().getMaxPositions();
        double defaultTs        = strategyConfig.getTrailingStop().getStopPercent();
        int    defaultTc        = strategyConfig.getTimeCut().getMaxHoldingDays();
        double defaultIdx       = 1.0;

        paperSettings = new ModeSettings(false, defaultInvest, defaultMaxPos, defaultTs, defaultTc, defaultIdx, defaultMaxPos, defaultMaxPos, defaultMaxPos);
        realSettings  = new ModeSettings(false, defaultInvest, defaultMaxPos, defaultTs, defaultTc, defaultIdx, defaultMaxPos, defaultMaxPos, defaultMaxPos);
        loadFromFile();
    }

    public record ModeSettings(boolean paused, int investAmountKrw, int maxPositions,
                               double trailingStopPct, int timeCutDays, double indexDropBlockPct,
                               int volatilityBreakoutDailyLimit,
                               int goldenCrossDailyLimit,
                               int bollingerDailyLimit) {}

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getTradingMode()        { return tradingMode; }
    public String getStrategyMode()       { return strategyMode; }
    public ModeSettings getPaperSettings() { return paperSettings; }
    public ModeSettings getRealSettings()  { return realSettings; }

    public ModeSettings getActiveSettings() {
        return "real".equals(tradingMode) ? realSettings : paperSettings;
    }

    public ModeSettings getSettings(String mode) {
        return "real".equals(mode) ? realSettings : paperSettings;
    }

    // Backward-compat delegation to active mode
    public boolean isPaused()                  { return getActiveSettings().paused(); }
    public int getInvestAmountKrw()            { return getActiveSettings().investAmountKrw(); }
    public int getMaxPositions()               { return getActiveSettings().maxPositions(); }
    public double getTrailingStopPct()         { return getActiveSettings().trailingStopPct(); }
    public int getTimeCutDays()                { return getActiveSettings().timeCutDays(); }
    public double getIndexDropBlockPct()       { return getActiveSettings().indexDropBlockPct(); }
    public int getVolatilityBreakoutDailyLimit()    { return getActiveSettings().volatilityBreakoutDailyLimit(); }
    public int getGoldenCrossDailyLimit()           { return getActiveSettings().goldenCrossDailyLimit(); }
    public int getBollingerDailyLimit()             { return getActiveSettings().bollingerDailyLimit(); }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setTradingMode(String mode) {
        this.tradingMode = "real".equals(mode) ? "real" : "paper";
        saveToFile();
        log.info("[AdminConfig] tradingMode 전환 → {}", this.tradingMode);
    }

    public void setStrategyMode(String mode) {
        this.strategyMode = "all-strategies".equals(mode) ? "all-strategies" : "market-based";
        saveToFile();
        log.info("[AdminConfig] strategyMode 변경 → {}", this.strategyMode);
    }

    /** targetMode == null 이면 현재 활성 모드의 paused 변경 */
    public void setPaused(boolean paused, String targetMode) {
        String mode = targetMode != null ? targetMode : tradingMode;
        ModeSettings s = getSettings(mode);
        updateSettings(mode, paused,
                s.investAmountKrw(), s.maxPositions(), s.trailingStopPct(), s.timeCutDays(),
                s.indexDropBlockPct(),
                s.volatilityBreakoutDailyLimit(), s.goldenCrossDailyLimit(), s.bollingerDailyLimit());
    }

    /** Backward-compat: active 모드 paused 변경 */
    public void setPaused(boolean paused) {
        setPaused(paused, null);
    }

    /** targetMode == null 이면 현재 활성 모드의 설정 변경 */
    public void setConfig(String targetMode,
                          int investAmountKrw, int maxPositions,
                          double trailingStopPct, int timeCutDays, double indexDropBlockPct,
                          int volatilityBreakoutDailyLimit, int goldenCrossDailyLimit, int bollingerDailyLimit) {
        String mode = targetMode != null ? targetMode : tradingMode;
        updateSettings(mode, getSettings(mode).paused(),
                investAmountKrw, maxPositions, trailingStopPct, timeCutDays, indexDropBlockPct,
                volatilityBreakoutDailyLimit, goldenCrossDailyLimit, bollingerDailyLimit);
    }

    /** Backward-compat: active 모드 설정 변경 */
    public void setConfig(int investAmountKrw, int maxPositions,
                          double trailingStopPct, int timeCutDays, double indexDropBlockPct) {
        ModeSettings s = getActiveSettings();
        setConfig(null, investAmountKrw, maxPositions, trailingStopPct, timeCutDays, indexDropBlockPct,
                s.volatilityBreakoutDailyLimit(), s.goldenCrossDailyLimit(), s.bollingerDailyLimit());
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void updateSettings(String mode, boolean paused,
                                int investAmountKrw, int maxPositions,
                                double trailingStopPct, int timeCutDays, double indexDropBlockPct,
                                int volatilityBreakoutDailyLimit, int goldenCrossDailyLimit, int bollingerDailyLimit) {
        ModeSettings updated = new ModeSettings(paused, investAmountKrw, maxPositions,
                trailingStopPct, timeCutDays, indexDropBlockPct,
                volatilityBreakoutDailyLimit, goldenCrossDailyLimit, bollingerDailyLimit);
        if ("real".equals(mode)) {
            realSettings = updated;
        } else {
            paperSettings = updated;
        }
        saveToFile();
    }

    private void saveToFile() {
        try {
            Snapshot snapshot = new Snapshot(tradingMode, strategyMode, paperSettings, realSettings);
            objectMapper.writeValue(new File(configFilePath), snapshot);
            log.info("[AdminConfig] 저장 — tradingMode={}", tradingMode);
        } catch (IOException e) {
            log.error("[AdminConfig] 저장 실패: {}", e.getMessage());
        }
    }

    private void loadFromFile() {
        File file = new File(configFilePath);
        if (!file.exists()) return;
        try {
            JsonNode node = objectMapper.readTree(file);
            if (node.has("tradingMode"))  this.tradingMode  = node.get("tradingMode").asText(this.tradingMode);
            if (node.has("strategyMode")) this.strategyMode = node.get("strategyMode").asText(this.strategyMode);

            if (node.has("paper")) {
                paperSettings = loadModeSettings(node.get("paper"), paperSettings);
            } else {
                // 레거시 flat 포맷 마이그레이션 (paper 설정으로 로드)
                paperSettings = loadModeSettings(node, paperSettings);
            }
            if (node.has("real")) {
                realSettings = loadModeSettings(node.get("real"), realSettings);
            }
            log.info("[AdminConfig] 로드 완료 — tradingMode={}, paper.paused={}, real.paused={}",
                    tradingMode, paperSettings.paused(), realSettings.paused());
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
        return new ModeSettings(paused, invest, maxPos, ts, tc, idx, volDaily, gcDaily, bollDaily);
    }

    public record Snapshot(String tradingMode, String strategyMode, ModeSettings paper, ModeSettings real) {}
}
