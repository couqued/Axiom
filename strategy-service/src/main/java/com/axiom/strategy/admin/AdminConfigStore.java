package com.axiom.strategy.admin;

import com.axiom.strategy.config.StrategyConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final String CONFIG_FILE = "admin-config.json";

    @PostConstruct
    void init() {
        investAmountKrw = strategyConfig.getPositionSizing().getInvestAmountKrw();
        maxPositions    = strategyConfig.getPositionSizing().getMaxPositions();
        loadFromFile();
    }

    public boolean isPaused()        { return paused; }
    public int getInvestAmountKrw()  { return investAmountKrw; }
    public int getMaxPositions()     { return maxPositions; }

    public void setPaused(boolean paused) {
        this.paused = paused;
        saveToFile();
    }

    public void setConfig(int investAmountKrw, int maxPositions) {
        this.investAmountKrw = investAmountKrw;
        this.maxPositions    = maxPositions;
        saveToFile();
    }

    private void saveToFile() {
        try {
            Snapshot snapshot = new Snapshot(paused, investAmountKrw, maxPositions);
            objectMapper.writeValue(new File(CONFIG_FILE), snapshot);
            log.info("[AdminConfig] 설정 저장 — paused={}, investAmountKrw={}, maxPositions={}",
                    paused, investAmountKrw, maxPositions);
        } catch (IOException e) {
            log.error("[AdminConfig] 설정 저장 실패: {}", e.getMessage());
        }
    }

    private void loadFromFile() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return;
        try {
            Snapshot snapshot = objectMapper.readValue(file, Snapshot.class);
            this.paused          = snapshot.paused();
            this.investAmountKrw = snapshot.investAmountKrw();
            this.maxPositions    = snapshot.maxPositions();
            log.info("[AdminConfig] 설정 로드 — paused={}, investAmountKrw={}, maxPositions={}",
                    paused, investAmountKrw, maxPositions);
        } catch (IOException e) {
            log.warn("[AdminConfig] 설정 로드 실패 (기본값 사용): {}", e.getMessage());
        }
    }

    public record Snapshot(boolean paused, int investAmountKrw, int maxPositions) {}
}
