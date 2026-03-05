package com.axiom.strategy.admin;

import com.axiom.strategy.config.StrategyConfig;
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

    private volatile boolean paused = false;
    private volatile int investAmountKrw;
    private volatile int maxPositions;
    private volatile double trailingStopPct;
    private volatile int timeCutDays;

    @Value("${admin.config-path:admin-config.json}")
    private String configFilePath;

    @PostConstruct
    void init() {
        investAmountKrw = strategyConfig.getPositionSizing().getInvestAmountKrw();
        maxPositions    = strategyConfig.getPositionSizing().getMaxPositions();
        trailingStopPct = strategyConfig.getTrailingStop().getStopPercent();
        timeCutDays     = strategyConfig.getTimeCut().getMaxHoldingDays();
        loadFromFile();
    }

    public boolean isPaused()          { return paused; }
    public int getInvestAmountKrw()    { return investAmountKrw; }
    public int getMaxPositions()       { return maxPositions; }
    public double getTrailingStopPct() { return trailingStopPct; }
    public int getTimeCutDays()        { return timeCutDays; }

    public void setPaused(boolean paused) {
        this.paused = paused;
        saveToFile();
    }

    public void setConfig(int investAmountKrw, int maxPositions,
                          double trailingStopPct, int timeCutDays) {
        this.investAmountKrw = investAmountKrw;
        this.maxPositions    = maxPositions;
        this.trailingStopPct = trailingStopPct;
        this.timeCutDays     = timeCutDays;
        saveToFile();
    }

    private void saveToFile() {
        try {
            Snapshot snapshot = new Snapshot(paused, investAmountKrw, maxPositions,
                    trailingStopPct, timeCutDays);
            objectMapper.writeValue(new File(configFilePath), snapshot);
            log.info("[AdminConfig] 설정 저장 — paused={}, investAmountKrw={}, maxPositions={}, trailingStopPct={}, timeCutDays={}",
                    paused, investAmountKrw, maxPositions, trailingStopPct, timeCutDays);
        } catch (IOException e) {
            log.error("[AdminConfig] 설정 저장 실패: {}", e.getMessage());
        }
    }

    private void loadFromFile() {
        File file = new File(configFilePath);
        if (!file.exists()) return;
        try {
            Snapshot snapshot = objectMapper.readValue(file, Snapshot.class);
            this.paused          = snapshot.paused();
            this.investAmountKrw = snapshot.investAmountKrw();
            this.maxPositions    = snapshot.maxPositions();
            this.trailingStopPct = snapshot.trailingStopPct();
            this.timeCutDays     = snapshot.timeCutDays();
            log.info("[AdminConfig] 설정 로드 — paused={}, investAmountKrw={}, maxPositions={}, trailingStopPct={}, timeCutDays={}",
                    paused, investAmountKrw, maxPositions, trailingStopPct, timeCutDays);
        } catch (IOException e) {
            log.warn("[AdminConfig] 설정 로드 실패 (기본값 사용): {}", e.getMessage());
        }
    }

    public record Snapshot(boolean paused, int investAmountKrw, int maxPositions,
                           double trailingStopPct, int timeCutDays) {}
}
