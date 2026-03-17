package com.axiom.market.controller;

import com.axiom.market.store.TradingModeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * strategy-service → market-service 거래 모드 전파 수신.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalModeController {

    private final TradingModeStore tradingModeStore;

    /**
     * 거래 모드 변경 수신.
     * PATCH /internal/trading-mode  { "mode": "real" | "paper" }
     */
    @PatchMapping("/trading-mode")
    public void updateTradingMode(@RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        tradingModeStore.setMode(mode);
        log.info("[InternalModeController] 거래 모드 수신: {}", mode);
    }
}
