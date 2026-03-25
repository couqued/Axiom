package com.axiom.strategy.admin;

import com.axiom.strategy.client.ModeClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.service.BollingerReserveService;
import com.axiom.strategy.service.DailySellBlockService;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.service.TimeCutService;
import com.axiom.strategy.service.TrailingStopService;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/strategy/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminConfigStore adminConfigStore;
    private final TrailingStopService trailingStopService;
    private final TimeCutService timeCutService;
    private final MarketStateService marketStateService;
    private final ModeClient modeClient;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final PortfolioClient portfolioClient;
    private final OrderClient orderClient;
    private final DailySellBlockService dailySellBlockService;
    private final BollingerReserveService bollingerReserveService;
    private final StrategyStateStore strategyStateStore;

    /** 현재 관리자 설정 상태 조회 */
    @GetMapping("/status")
    public ResponseEntity<AdminStatusDto> getStatus() {
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 중단 — targetMode 파라미터로 특정 모드만 중단 가능 */
    @PostMapping("/pause")
    public ResponseEntity<AdminStatusDto> pause(@RequestParam(required = false) String targetMode) {
        adminConfigStore.setPaused(true, targetMode);
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 재개 — targetMode 파라미터로 특정 모드만 재개 가능 */
    @PostMapping("/resume")
    public ResponseEntity<AdminStatusDto> resume(@RequestParam(required = false) String targetMode) {
        adminConfigStore.setPaused(false, targetMode);
        return ResponseEntity.ok(currentStatus());
    }

    /** 투자 설정 변경 (부분 업데이트 허용) — tradingMode 필드로 모드 전환 가능 */
    @PatchMapping("/config")
    public ResponseEntity<AdminStatusDto> updateConfig(@RequestBody AdminConfigDto dto) {
        // 모드 전환 요청 처리
        if (dto.tradingMode() != null) {
            adminConfigStore.setTradingMode(dto.tradingMode());
            modeClient.propagateTradingMode(dto.tradingMode());
        }
        if (dto.strategyMode() != null) {
            adminConfigStore.setStrategyMode(dto.strategyMode());
        }

        // 설정 변경 요청 처리 (tradingMode만 있고 설정 필드가 없으면 스킵)
        boolean hasSettingFields = dto.investAmountKrw() != null || dto.maxPositions() != null
                || dto.trailingStopPct() != null || dto.timeCutDays() != null || dto.indexDropBlockPct() != null
                || dto.volatilityBreakoutDailyLimit() != null || dto.goldenCrossDailyLimit() != null || dto.bollingerDailyLimit() != null;
        if (hasSettingFields) {
            String target = dto.targetMode(); // null이면 active 모드
            AdminConfigStore.ModeSettings current = adminConfigStore.getSettings(
                    target != null ? target : adminConfigStore.getTradingMode());

            int    newInvest    = dto.investAmountKrw()                != null ? dto.investAmountKrw()                : current.investAmountKrw();
            int    newMaxPos    = dto.maxPositions()                   != null ? dto.maxPositions()                   : current.maxPositions();
            double newTs        = dto.trailingStopPct()                != null ? dto.trailingStopPct()                : current.trailingStopPct();
            int    newTc        = dto.timeCutDays()                    != null ? dto.timeCutDays()                    : current.timeCutDays();
            double newIdx       = dto.indexDropBlockPct()              != null ? dto.indexDropBlockPct()              : current.indexDropBlockPct();
            int    newVolDaily  = dto.volatilityBreakoutDailyLimit()   != null ? dto.volatilityBreakoutDailyLimit()   : current.volatilityBreakoutDailyLimit();
            int    newGcDaily   = dto.goldenCrossDailyLimit()          != null ? dto.goldenCrossDailyLimit()          : current.goldenCrossDailyLimit();
            int    newBollDaily = dto.bollingerDailyLimit()            != null ? dto.bollingerDailyLimit()            : current.bollingerDailyLimit();
            adminConfigStore.setConfig(target, newInvest, newMaxPos, newTs, newTc, newIdx, newVolDaily, newGcDaily, newBollDaily);
        }

        return ResponseEntity.ok(currentStatus());
    }

    /** 트레일링 스탑 현황 조회 — ticker별 고점/기준가 */
    @GetMapping("/trailing-stop-status")
    public ResponseEntity<Map<String, TrailingStopStatusDto>> getTrailingStopStatus() {
        return ResponseEntity.ok(trailingStopService.getStatus());
    }

    /** 타임 컷 현황 조회 — ticker별 매수일/경과/남은 거래일 */
    @GetMapping("/time-cut-status")
    public ResponseEntity<Map<String, TimeCutStatusDto>> getTimeCutStatus() {
        return ResponseEntity.ok(timeCutService.getStatus());
    }

    /** 미체결 매수 정리 — 매도 없이 인메모리+DB 상태만 초기화 */
    @PostMapping("/cleanup-tickers")
    public ResponseEntity<Map<String, String>> cleanupTickers(@RequestBody CleanupTickersRequest req) {
        String mode = req.mode() != null ? req.mode() : adminConfigStore.getTradingMode();
        Map<String, String> result = new LinkedHashMap<>();
        for (String ticker : req.tickers()) {
            try {
                volatilityBreakoutStrategy.removeTodayBought(ticker, mode);
                strategyStateStore.removeBuyStage(ticker, mode);
                strategyStateStore.removeEntryTag(ticker, mode);
                timeCutService.clearBuy(ticker, mode);
                trailingStopService.removePeak(ticker, mode);
                bollingerReserveService.clear(ticker);
                result.put(ticker, "OK");
            } catch (Exception e) {
                result.put(ticker, "ERROR: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    /** MTS/외부 경로로 이미 매도된 종목 — 포트폴리오 DB + 전략 상태 정리 (KIS 주문 없음) */
    @PostMapping("/mark-sold")
    public ResponseEntity<Map<String, String>> markSold(@RequestBody CleanupTickersRequest req) {
        String mode = req.mode() != null ? req.mode() : adminConfigStore.getTradingMode();
        Map<String, String> result = new LinkedHashMap<>();
        for (String ticker : req.tickers()) {
            try {
                portfolioClient.deletePosition(ticker, mode);
                volatilityBreakoutStrategy.removeTodayBought(ticker, mode);
                strategyStateStore.removeBuyStage(ticker, mode);
                strategyStateStore.removeEntryTag(ticker, mode);
                timeCutService.clearBuy(ticker, mode);
                trailingStopService.removePeak(ticker, mode);
                bollingerReserveService.clear(ticker);
                dailySellBlockService.markSoldToday(ticker);
                result.put(ticker, "OK");
            } catch (Exception e) {
                result.put(ticker, "ERROR: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    /** 수동 청산 — 지정 종목 즉시 시장가 매도 */
    @PostMapping("/manual-exit")
    public ResponseEntity<Map<String, Boolean>> manualExit(@RequestBody ManualExitRequest req) {
        Set<String> targets = new HashSet<>(req.tickers());
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        Map<String, Boolean> result = new LinkedHashMap<>();

        for (PortfolioItemDto position : positions) {
            if (!targets.contains(position.getTicker())) continue;
            OrderRequest sellOrder = OrderRequest.builder()
                    .ticker(position.getTicker())
                    .quantity(position.getQuantity())
                    .price(null)
                    .closeReason("MANUAL_EXIT")
                    .build();
            OrderResult orderResult = orderClient.sellWithRetry(sellOrder);
            result.put(position.getTicker(), orderResult.success());
            if (orderResult.success()) {
                String mode = adminConfigStore.getTradingMode();
                volatilityBreakoutStrategy.removeTodayBought(position.getTicker(), mode);
                dailySellBlockService.markSoldToday(position.getTicker());
                timeCutService.clearBuy(position.getTicker());
                trailingStopService.removePeak(position.getTicker(), mode);
                bollingerReserveService.clear(position.getTicker());
            }
        }

        return ResponseEntity.ok(result);
    }

    private AdminStatusDto currentStatus() {
        AdminConfigStore.ModeSettings paper = adminConfigStore.getPaperSettings();
        AdminConfigStore.ModeSettings real  = adminConfigStore.getRealSettings();
        return new AdminStatusDto(
                adminConfigStore.getTradingMode(),
                adminConfigStore.getStrategyMode(),
                new AdminStatusDto.ModeSettingsDto(
                        paper.paused(), paper.investAmountKrw(), paper.maxPositions(),
                        paper.trailingStopPct(), paper.timeCutDays(), paper.indexDropBlockPct(),
                        paper.volatilityBreakoutDailyLimit(), paper.goldenCrossDailyLimit(), paper.bollingerDailyLimit()),
                new AdminStatusDto.ModeSettingsDto(
                        real.paused(), real.investAmountKrw(), real.maxPositions(),
                        real.trailingStopPct(), real.timeCutDays(), real.indexDropBlockPct(),
                        real.volatilityBreakoutDailyLimit(), real.goldenCrossDailyLimit(), real.bollingerDailyLimit()),
                marketStateService.isIndexDropBlockedToday(),
                marketStateService.isIndexDropCheckedToday(),
                bollingerReserveService.getAllReservations()
        );
    }
}
