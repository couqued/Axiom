package com.axiom.strategy.admin;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.MlClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.TradePlanDto;
import com.axiom.strategy.ml.MlPerformanceService;
import com.axiom.strategy.service.MlDeferTracker;
import com.axiom.strategy.service.MlPositionStore;
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
import com.axiom.strategy.service.VolBreakoutExitService;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import reactor.core.scheduler.Schedulers;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/strategy/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminConfigStore adminConfigStore;
    private final TrailingStopService trailingStopService;
    private final VolBreakoutExitService volBreakoutExitService;
    private final TimeCutService timeCutService;
    private final MarketStateService marketStateService;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final PortfolioClient portfolioClient;
    private final OrderClient orderClient;
    private final DailySellBlockService dailySellBlockService;
    private final BollingerReserveService bollingerReserveService;
    private final StrategyStateStore strategyStateStore;
    private final StrategyEngine strategyEngine;
    private final MlClient mlClient;
    private final MlPerformanceService mlPerformanceService;
    private final MlDeferTracker mlDeferTracker;
    private final MlPositionStore mlPositionStore;
    private final MarketClient marketClient;

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
                || dto.mlDailyLimit() != null || dto.mlBuyThreshold() != null || dto.mlEntryTimingEnabled() != null
                || dto.volBreakoutTakeProfitPct() != null || dto.volBreakoutStopLossPct() != null
                || dto.volBreakoutIntradayTrailingPct() != null;
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
            double newVolTp       = dto.volBreakoutTakeProfitPct()       != null ? dto.volBreakoutTakeProfitPct()       : current.volBreakoutTakeProfitPct();
            double newVolSl       = dto.volBreakoutStopLossPct()         != null ? dto.volBreakoutStopLossPct()         : current.volBreakoutStopLossPct();
            double newVolTrailing = dto.volBreakoutIntradayTrailingPct() != null ? dto.volBreakoutIntradayTrailingPct() : current.volBreakoutIntradayTrailingPct();
            adminConfigStore.setConfig(newInvest, newMaxPos, newTs, newTc, newIdx,
                    newVolDaily, newGcDaily, newBollDaily, newPtPct,
                    newMlDaily, newMlThr, newMlEntry,
                    newVolTp, newVolSl, newVolTrailing);
        }

        return ResponseEntity.ok(currentStatus());
    }

    /** ML 보유 포지션 플랜 복구 — mlPositionStore에 데이터 없는 ml-prediction 포지션을 재예측해 저장 */
    @PostMapping("/ml/recover-positions")
    public ResponseEntity<Map<String, String>> recoverMlPositions() {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> tags = strategyStateStore.loadAllEntryTags();
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        List<CandleDto> indexCandles = marketStateService.getKospiCandlesCached();

        for (PortfolioItemDto pos : positions) {
            String ticker = pos.getTicker();
            if (!"ml-prediction".equals(tags.get(ticker))) continue;
            if (mlPositionStore.isActive(ticker)) {
                result.put(ticker, "SKIP (이미 있음)");
                continue;
            }
            try {
                List<CandleDto> candles = marketClient.getCandles(ticker, 60);
                TradePlanDto plan = mlClient.predict(ticker, candles, indexCandles, 0.5,
                        Collections.emptyList(), null);
                if (plan == null || !plan.isBuy()) {
                    result.put(ticker, "SKIP (ML HOLD 또는 실패)");
                    continue;
                }
                mlPositionStore.activateDirect(ticker, plan, LocalDate.now(), pos.getAvgPrice());
                result.put(ticker, String.format("OK — conf=%.1f%%, TP=%s, SL=%s",
                        plan.confidence() * 100, plan.takeProfitPrice(), plan.stopLossPrice()));
            } catch (Exception e) {
                result.put(ticker, "ERROR: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    /** ML DEFER/블랙리스트 전체 초기화 */
    @PostMapping("/ml/defer/clear")
    public ResponseEntity<Map<String, String>> clearMlDefer() {
        mlDeferTracker.clearAll();
        return ResponseEntity.ok(Map.of("status", "ML DEFER 초기화 완료"));
    }

    /** ML 예측 드라이런 — 실제 주문 없이 watch-tickers 전체 예측 결과 조회 (확신도 순) */
    @GetMapping("/ml/dry-run")
    public ResponseEntity<java.util.List<StrategyEngine.MlDryRunResult>> mlDryRun() {
        return ResponseEntity.ok(strategyEngine.runMlDryRun());
    }

    /** ML 재학습 트리거 — 완료 후 DB 스냅샷 즉시 저장 */
    @PostMapping("/ml/retrain")
    public ResponseEntity<Map<String, String>> mlRetrain() {
        mlClient.startTrainAsync()
                .publishOn(Schedulers.boundedElastic())  // blocking 허용 스레드로 전환
                .doOnSuccess(v -> {
                    log.info("[Admin] /train 완료 — 스냅샷 저장 시도");
                    try {
                        MlClient.ModelStatusDto status = mlClient.getModelStatus();
                        if (status != null && status.trainedAt() != null) {
                            mlPerformanceService.saveSnapshotIfNew(
                                    status.trainedAt(), status.samples(),
                                    status.valAuc(), status.valMaeRet(), status.valMaeDays());
                            log.info("[Admin] 스냅샷 저장 완료 — trainedAt: {}", status.trainedAt());
                        }
                    } catch (Exception e) {
                        log.warn("[Admin] 스냅샷 저장 실패: {}", e.getMessage());
                    }
                })
                .doOnError(e -> log.warn("[Admin] /train 실패: {}", e.getMessage()))
                .subscribe();
        log.info("[Admin] ML 재학습 트리거 — 백그라운드 시작");
        return ResponseEntity.ok(Map.of("status", "학습 시작됨. 완료까지 수분 소요됩니다."));
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
                volBreakoutExitService.clearPeak(ticker);
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
                volBreakoutExitService.clearPeak(ticker);
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
            String strategyName = strategyStateStore.loadAllEntryTags().get(position.getTicker());
            OrderRequest sellOrder = OrderRequest.builder()
                    .ticker(position.getTicker())
                    .quantity(position.getQuantity())
                    .price(null)
                    .strategyName(strategyName)
                    .closeReason("MANUAL_EXIT")
                    .build();
            OrderResult orderResult = orderClient.sellWithRetry(sellOrder);
            result.put(position.getTicker(), orderResult.success());
            if (orderResult.success()) {
                volatilityBreakoutStrategy.removeTodayBought(position.getTicker());
                dailySellBlockService.markSoldToday(position.getTicker());
                timeCutService.clearBuy(position.getTicker());
                trailingStopService.removePeak(position.getTicker());
                volBreakoutExitService.clearPeak(position.getTicker());
                bollingerReserveService.clear(position.getTicker());
                strategyStateStore.removeBuyStage(position.getTicker());
                strategyStateStore.removeEntryTag(position.getTicker());
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
                        s.mlPaused(), s.mlSellPaused(),
                        s.volBreakoutTakeProfitPct(), s.volBreakoutStopLossPct(),
                        s.volBreakoutIntradayTrailingPct()),
                marketStateService.isIndexDropBlockedToday(),
                marketStateService.isIndexDropCheckedToday(),
                bollingerReserveService.getAllReservations()
        );
    }
}
