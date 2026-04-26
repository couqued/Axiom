package com.axiom.strategy.admin;

import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.engine.StrategyEngine;
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
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final PortfolioClient portfolioClient;
    private final OrderClient orderClient;
    private final DailySellBlockService dailySellBlockService;
    private final BollingerReserveService bollingerReserveService;
    private final StrategyStateStore strategyStateStore;
    private final StrategyEngine strategyEngine;

    /** 현재 관리자 설정 상태 조회 */
    @GetMapping("/status")
    public ResponseEntity<AdminStatusDto> getStatus() {
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 중단 */
    @PostMapping("/pause")
    public ResponseEntity<AdminStatusDto> pause() {
        adminConfigStore.setPaused(true);
        return ResponseEntity.ok(currentStatus());
    }

    /** 매매 재개 */
    @PostMapping("/resume")
    public ResponseEntity<AdminStatusDto> resume() {
        adminConfigStore.setPaused(false);
        return ResponseEntity.ok(currentStatus());
    }

    /** 매도 중지 */
    @PostMapping("/pause-sell")
    public ResponseEntity<AdminStatusDto> pauseSell() {
        adminConfigStore.setSellPaused(true);
        return ResponseEntity.ok(currentStatus());
    }

    /** 매도 재개 */
    @PostMapping("/resume-sell")
    public ResponseEntity<AdminStatusDto> resumeSell() {
        adminConfigStore.setSellPaused(false);
        return ResponseEntity.ok(currentStatus());
    }

    /** ML 매수 중단 */
    @PostMapping("/pause-ml")
    public ResponseEntity<AdminStatusDto> pauseMl() {
        adminConfigStore.setMlPaused(true);
        return ResponseEntity.ok(currentStatus());
    }

    /** ML 매수 재개 */
    @PostMapping("/resume-ml")
    public ResponseEntity<AdminStatusDto> resumeMl() {
        adminConfigStore.setMlPaused(false);
        return ResponseEntity.ok(currentStatus());
    }

    /** ML 매도 중지 */
    @PostMapping("/pause-ml-sell")
    public ResponseEntity<AdminStatusDto> pauseMlSell() {
        adminConfigStore.setMlSellPaused(true);
        return ResponseEntity.ok(currentStatus());
    }

    /** ML 매도 재개 */
    @PostMapping("/resume-ml-sell")
    public ResponseEntity<AdminStatusDto> resumeMlSell() {
        adminConfigStore.setMlSellPaused(false);
        return ResponseEntity.ok(currentStatus());
    }

    /** 투자 설정 변경 (부분 업데이트 허용) */
    @PatchMapping("/config")
    public ResponseEntity<AdminStatusDto> updateConfig(@RequestBody AdminConfigDto dto) {
        if (dto.strategyMode() != null) {
            adminConfigStore.setStrategyMode(dto.strategyMode());
        }

        if (dto.mlPaused() != null)     adminConfigStore.setMlPaused(dto.mlPaused());
        if (dto.mlSellPaused() != null) adminConfigStore.setMlSellPaused(dto.mlSellPaused());

        boolean hasSettingFields = dto.investAmountKrw() != null || dto.maxPositions() != null
                || dto.trailingStopPct() != null || dto.timeCutDays() != null || dto.indexDropBlockPct() != null
                || dto.volatilityBreakoutDailyLimit() != null || dto.goldenCrossDailyLimit() != null
                || dto.bollingerDailyLimit() != null || dto.profitTakePct() != null
                || dto.mlDailyLimit() != null || dto.mlBuyThreshold() != null || dto.mlEntryTimingEnabled() != null;
        if (hasSettingFields) {
            AdminConfigStore.ModeSettings current = adminConfigStore.getSettings();

            int    newInvest    = dto.investAmountKrw()                != null ? dto.investAmountKrw()                : current.investAmountKrw();
            int    newMaxPos    = dto.maxPositions()                   != null ? dto.maxPositions()                   : current.maxPositions();
            double newTs        = dto.trailingStopPct()                != null ? dto.trailingStopPct()                : current.trailingStopPct();
            int    newTc        = dto.timeCutDays()                    != null ? dto.timeCutDays()                    : current.timeCutDays();
            double newIdx       = dto.indexDropBlockPct()              != null ? dto.indexDropBlockPct()              : current.indexDropBlockPct();
            int    newVolDaily  = dto.volatilityBreakoutDailyLimit()   != null ? dto.volatilityBreakoutDailyLimit()   : current.volatilityBreakoutDailyLimit();
            int    newGcDaily   = dto.goldenCrossDailyLimit()          != null ? dto.goldenCrossDailyLimit()          : current.goldenCrossDailyLimit();
            int    newBollDaily = dto.bollingerDailyLimit()            != null ? dto.bollingerDailyLimit()            : current.bollingerDailyLimit();
            double newPtPct     = dto.profitTakePct()                  != null ? dto.profitTakePct()                  : current.profitTakePct();
            int     newMlDaily   = dto.mlDailyLimit()           != null ? dto.mlDailyLimit()           : current.mlDailyLimit();
            double  newMlThr     = dto.mlBuyThreshold()         != null ? dto.mlBuyThreshold()         : current.mlBuyThreshold();
            boolean newMlEntry   = dto.mlEntryTimingEnabled()   != null ? dto.mlEntryTimingEnabled()   : current.mlEntryTimingEnabled();
            adminConfigStore.setConfig(newInvest, newMaxPos, newTs, newTc, newIdx,
                    newVolDaily, newGcDaily, newBollDaily, newPtPct,
                    newMlDaily, newMlThr, newMlEntry);
        }

        return ResponseEntity.ok(currentStatus());
    }

    /** ML 예측 드라이런 — 실제 주문 없이 watch-tickers 전체 예측 결과 조회 (확신도 순) */
    @GetMapping("/ml/dry-run")
    public ResponseEntity<java.util.List<StrategyEngine.MlDryRunResult>> mlDryRun() {
        return ResponseEntity.ok(strategyEngine.runMlDryRun());
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
        Map<String, String> result = new LinkedHashMap<>();
        for (String ticker : req.tickers()) {
            try {
                volatilityBreakoutStrategy.removeTodayBought(ticker);
                strategyStateStore.removeBuyStage(ticker);
                strategyStateStore.removeEntryTag(ticker);
                timeCutService.clearBuy(ticker);
                trailingStopService.removePeak(ticker);
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
        Map<String, String> result = new LinkedHashMap<>();
        for (String ticker : req.tickers()) {
            try {
                portfolioClient.deletePosition(ticker);
                volatilityBreakoutStrategy.removeTodayBought(ticker);
                strategyStateStore.removeBuyStage(ticker);
                strategyStateStore.removeEntryTag(ticker);
                timeCutService.clearBuy(ticker);
                trailingStopService.removePeak(ticker);
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
                volatilityBreakoutStrategy.removeTodayBought(position.getTicker());
                dailySellBlockService.markSoldToday(position.getTicker());
                timeCutService.clearBuy(position.getTicker());
                trailingStopService.removePeak(position.getTicker());
                bollingerReserveService.clear(position.getTicker());
            }
        }

        return ResponseEntity.ok(result);
    }

    private AdminStatusDto currentStatus() {
        AdminConfigStore.ModeSettings s = adminConfigStore.getSettings();
        return new AdminStatusDto(
                adminConfigStore.getStrategyMode(),
                new AdminStatusDto.ModeSettingsDto(
                        s.paused(), s.sellPaused(), s.investAmountKrw(), s.maxPositions(),
                        s.trailingStopPct(), s.timeCutDays(), s.indexDropBlockPct(),
                        s.volatilityBreakoutDailyLimit(), s.goldenCrossDailyLimit(), s.bollingerDailyLimit(),
                        s.profitTakePct(),
                        s.mlDailyLimit(), s.mlBuyThreshold(), s.mlEntryTimingEnabled(),
                        s.mlPaused(), s.mlSellPaused()),
                marketStateService.isIndexDropBlockedToday(),
                marketStateService.isIndexDropCheckedToday(),
                bollingerReserveService.getAllReservations()
        );
    }
}
