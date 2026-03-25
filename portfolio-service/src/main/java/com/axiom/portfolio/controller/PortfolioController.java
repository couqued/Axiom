package com.axiom.portfolio.controller;

import com.axiom.portfolio.dto.PortfolioItemDto;
import com.axiom.portfolio.service.KisAccountApiService;
import com.axiom.portfolio.service.PortfolioService;
import com.axiom.portfolio.store.TradingModeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final KisAccountApiService kisAccountApiService;
    private final TradingModeStore tradingModeStore;

    // 보유 주식 현황: GET /api/portfolio?mode=paper|real (없으면 활성 모드)
    @GetMapping("/api/portfolio")
    public ResponseEntity<List<PortfolioItemDto>> getPortfolio(
            @RequestParam(required = false) String mode) {
        String targetMode = (mode != null && !mode.isBlank()) ? mode : tradingModeStore.getMode();
        return ResponseEntity.ok(portfolioService.getByMode(targetMode));
    }

    // 계좌 잔고: GET /api/portfolio/balance
    @GetMapping("/api/portfolio/balance")
    public ResponseEntity<Map<String, Object>> getBalance() {
        return ResponseEntity.ok(kisAccountApiService.getBalance());
    }

    // 관리자용 티커별 삭제: DELETE /api/portfolio/admin/by-ticker?ticker=006360&mode=real
    @DeleteMapping("/api/portfolio/admin/by-ticker")
    public ResponseEntity<Void> deleteByTicker(
            @RequestParam String ticker,
            @RequestParam String mode) {
        portfolioService.deletePosition(ticker, mode);
        return ResponseEntity.noContent().build();
    }

    // 내부 엔드포인트: 거래 모드 변경 전파
    @PatchMapping("/internal/trading-mode")
    public ResponseEntity<Void> updateTradingMode(@RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        if (mode != null) {
            tradingModeStore.setMode(mode);
        }
        return ResponseEntity.ok().build();
    }
}
