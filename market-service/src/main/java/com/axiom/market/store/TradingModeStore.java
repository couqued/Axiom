package com.axiom.market.store;

import com.axiom.market.config.KisApiConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 런타임 거래 모드 저장소.
 * strategy-service에서 PATCH /internal/trading-mode 호출로 갱신됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingModeStore {

    private final KisApiConfig kisApiConfig;
    private volatile String mode;

    @PostConstruct
    void init() {
        this.mode = kisApiConfig.isMock() ? "mock" : kisApiConfig.getMode();
        log.info("[TradingModeStore] 초기 모드: {}", this.mode);
    }

    public String getMode() { return mode; }

    public void setMode(String mode) {
        this.mode = mode;
        log.info("[TradingModeStore] 거래 모드 변경: {}", mode);
    }

    public boolean isPaper() { return "paper".equals(mode); }
    public boolean isReal()  { return "real".equals(mode); }
    public boolean isMock()  { return "mock".equals(mode); }
}
