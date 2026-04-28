package com.axiom.strategy.engine;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.MlClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.dto.SignalGapDto;
import com.axiom.strategy.dto.SkippedSignalRequest;
import com.axiom.strategy.dto.StockPriceDto;
import com.axiom.strategy.dto.TradePlanDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.service.BollingerReserveService;
import com.axiom.strategy.service.DailySellBlockService;
import com.axiom.strategy.service.EntryQualityEvaluator;
import com.axiom.strategy.service.MarketState;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.service.TimeCutService;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.strategy.TradingStrategy;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import com.axiom.strategy.util.TradingCalendar;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {

    private final StrategyConfig strategyConfig;
    private final AdminConfigStore adminConfigStore;
    private final MarketClient marketClient;
    private final OrderClient orderClient;
    private final PortfolioClient portfolioClient;
    private final SlackNotifier slackNotifier;
    private final MarketStateService marketStateService;
    private final TimeCutService timeCutService;
    private final StrategyStateStore strategyStateStore;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final DailySellBlockService dailySellBlockService;
    private final BollingerReserveService bollingerReserveService;
    private final List<TradingStrategy> strategies;
    private final com.axiom.strategy.service.MlPositionStore mlPositionStore;
    private final com.axiom.strategy.service.MlExitService mlExitService;
    private final com.axiom.strategy.service.MlDeferTracker mlDeferTracker;
    private final MlClient mlClient;
    private final EntryQualityEvaluator entryQualityEvaluator;

    private volatile List<String> watchTickers = List.of();
    private volatile List<SignalGapDto> signalGapCache = List.of();
    private volatile LocalDateTime signalGapComputedAt = null;
    private volatile boolean signalGapRunning = false;
    private volatile List<EvalRankEntry> lastBuyRanking = List.of();
    private volatile LocalDateTime lastEvalAt;
    private final java.util.concurrent.CopyOnWriteArrayList<RunRecord> todayRuns = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile LocalDate lastRunDate = null;
    /** 수동 즉시 실행 상태 */
    private volatile boolean manualRunning = false;
    private volatile String lastManualRunMessage = null;
    /** RSI 과매수 홀드 Slack 알림 발송 이력: ticker → 발송일 */
    private final Map<String, LocalDate> rsiOverboughtHoldNotifiedDate = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        List<String> yml = strategyConfig.getWatchTickers();
        watchTickers = (yml != null) ? yml : List.of();
        log.info("[Engine] 초기 감시 종목 — yml fallback {}개 (기동 후 screened-tickers로 교체 예정)", watchTickers.size());
    }

    public void updateWatchTickers(List<String> tickers) {
        watchTickers = tickers;
    }

    public List<EvalRankEntry> getLastBuyRanking() {
        return lastBuyRanking;
    }

    public LocalDateTime getLastEvalAt() { return lastEvalAt; }

    public boolean isManualRunning() { return manualRunning; }
    public String getLastManualRunMessage() { return lastManualRunMessage; }

    /** 수동 실행 — 즉시 반환, 백그라운드에서 run() 실행 */
    public void runAsync() {
        if (manualRunning) return;
        manualRunning = true;
        lastManualRunMessage = null;
        CompletableFuture.runAsync(() -> {
            try {
                RunResult result = run();
                lastManualRunMessage = result.paused()
                        ? "매매 중단 상태 — 전략 실행 스킵"
                        : String.format("종목 %d개 평가 완료 — 매수 %d건, 매도 %d건",
                                result.evaluated(), result.bought(), result.sold());
            } catch (Exception e) {
                lastManualRunMessage = "실행 오류: " + e.getMessage();
                log.error("[Engine] 수동 실행 오류", e);
            } finally {
                manualRunning = false;
            }
        });
    }

    public List<RunRecord> getTodayRuns() {
        return List.copyOf(todayRuns);
    }

    public int getWatchTickerCount() {
        return watchTickers.size();
    }

    public List<PortfolioItemDto> getEnrichedPortfolio() {
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        Map<String, Integer> stages = strategyStateStore.loadAllBuyStages();
        Map<String, String> tags = strategyStateStore.loadAllEntryTags();
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        positions.forEach(p -> {
            Integer s = stages.get(p.getTicker());
            if (s != null) p.withBuyStage(s);
            String tag = tags.get(p.getTicker());
            if (tag != null) p.withEntryTag(tag);
            if ("ml-prediction".equals(tag)) {
                mlPositionStore.getActive(p.getTicker()).ifPresent(ap -> {
                    com.axiom.strategy.dto.TradePlanDto plan = ap.plan();
                    int elapsed   = TradingCalendar.tradingDaysBetween(ap.entryDate(), today);
                    int remaining = plan.expectedDays() - elapsed;
                    p.withMlPlan(plan.confidence(), plan.mlScore(),
                            plan.takeProfitPrice(), plan.stopLossPrice(),
                            plan.expectedDays(), remaining);
                });
            }
        });
        return positions;
    }

    public record TradeRecord(String ticker, String stockName, BigDecimal price, String strategyName) {}
    public record SkipRecord(String ticker, String stockName, String reason) {}
    private record BuyCandidate(SignalDto signal, List<CandleDto> allCandles, StockPriceDto priceData) {
        double score() { return signal.getScore(); }
    }
    public record RunRecord(LocalDateTime runAt, int evaluated, int bought, int sold,
                            int errors, int skippedMarketWarn, int skippedMaxPositions,
                            List<TradeRecord> boughtList, List<TradeRecord> soldList,
                            List<SkipRecord> skippedList) {}

    public record EvalRankEntry(
            int rank,
            String ticker,
            String stockName,
            String strategyName,
            double score,
            String reason,
            String result    // "매수" | "한도초과" | "예산부족" | "이미보유"
    ) {}

    public record RunResult(int evaluated, int bought, int sold, boolean paused,
                            int errors, int skippedMarketWarn, int skippedMaxPositions,
                            List<TradeRecord> boughtList, List<TradeRecord> soldList,
                            List<SkipRecord> skippedList) {}

    public RunResult run() {
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        MarketState marketState = marketStateService.getCurrentState();
        List<String> tickers = watchTickers;
        List<String> activeStrategyNames = getActiveStrategyNames(marketState);
        int candleDays = strategyConfig.getCandleDays();
        int maxPositions = adminConfigStore.getMaxPositions();

        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        Map<String, Integer> stages = strategyStateStore.loadAllBuyStages();
        Map<String, String> entryTags = strategyStateStore.loadAllEntryTags();
        positions.forEach(p -> {
            Integer s = stages.get(p.getTicker());
            if (s != null) p.withBuyStage(s);
            String tag = entryTags.get(p.getTicker());
            if (tag != null) p.withEntryTag(tag);
        });

        int[] boughtThisRun           = {0};
        Map<String, Integer> boughtThisRunByStrategy = new java.util.HashMap<>();
        int[] soldThisRun             = {0};
        int[] errorsThisRun           = {0};
        int[] warnSkipsThisRun        = {0};
        int[] maxPosSkipsThisRun      = {0};
        List<TradeRecord> boughtList  = new ArrayList<>();
        List<TradeRecord> soldList    = new ArrayList<>();
        List<SkipRecord>  skippedList = new ArrayList<>();

        log.info("[Engine] 전략 실행 시작 — 시장: {}, tickers: {}개, 전략: {}, 보유: {}개/{}개",
                marketState, tickers.size(), activeStrategyNames, positions.size(), maxPositions);

        // ── Phase 1: 전체 종목 평가 ──────────────────────────────────────────
        // 09:02 이전 BUY 전면 스킵 가드는 ML 도입과 함께 제거됨 (09:00 부터 매수 허용).
        // 시초가 급변은 ML EntryQuality 의 3축 multiplier + 갭업/FOMO 가드가 담당.
        boolean isEarlyMorning = false;  // legacy 파라미터 — 항상 false, 추후 제거 가능
        boolean indexBlocked = marketStateService.isIndexDropBlockedToday() && adminConfigStore.getIndexDropBlockPct() > 0;

        List<BuyCandidate> buyQueue = new ArrayList<>();
        int[] breadthStats = {0, 0}; // [0]=상승, [1]=전체
        for (String ticker : tickers) {
            try {
                Optional<BuyCandidate> candidate = collectBuyCandidate(
                        ticker, candleDays, activeStrategyNames, positions,
                        soldThisRun, errorsThisRun, warnSkipsThisRun,
                        soldList, skippedList, marketState, isEarlyMorning, indexBlocked,
                        breadthStats);
                candidate.ifPresent(buyQueue::add);
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[Engine] 종목 처리 오류 — ticker: {}, error: {}", ticker, e.getMessage());
                errorsThisRun[0]++;
            }
        }

        // market_breadth 갱신 (다음 사이클부터 ML 추론에 반영)
        if (breadthStats[1] > 0) {
            marketStateService.setMarketBreadth((double) breadthStats[0] / breadthStats[1]);
        }

        // 오늘 장 초기 지수 캡처
        try {
            List<CandleDto> idxCandles = marketClient.getIndexCandles(
                    strategyConfig.getMarketFilter().getIndexCode(), 2);
            if (!idxCandles.isEmpty()) {
                marketStateService.captureTodayOpenIndex(
                        idxCandles.get(idxCandles.size() - 1).getClosePrice());
            }
        } catch (Exception e) {
            log.warn("[Engine] 지수 캡처 실패: {}", e.getMessage());
        }

        // ── Phase 1.5: 지수 하락률 체크 플래그 갱신 (이미 9:07 이후이고 체크 안했다면) ──
        if (!isEarlyMorning && !marketStateService.isIndexDropCheckedToday()) {
            try {
                List<CandleDto> idxCandles = marketClient.getIndexCandles(
                        strategyConfig.getMarketFilter().getIndexCode(), 2);
                if (!idxCandles.isEmpty()) {
                    BigDecimal currentIndex = idxCandles.get(idxCandles.size() - 1).getClosePrice();
                    marketStateService.checkAndSetIndexDropBlock(
                            currentIndex, adminConfigStore.getIndexDropBlockPct());
                }
            } catch (Exception e) {
                log.warn("[Engine] 지수 하락률 체크 실패: {}", e.getMessage());
            }
        }

        // ── Phase 2: BUY 후보 score 내림차순 정렬 → 상위 maxPositions개만 매수 ──
        buyQueue.sort(Comparator.comparingDouble(BuyCandidate::score).reversed());
        if (!buyQueue.isEmpty()) {
            log.info("[Engine] BUY 후보 {}개 수집 → score 기준 상위 {}개 실행 — {}",
                    buyQueue.size(), maxPositions,
                    buyQueue.stream()
                            .map(c -> String.format("%s(%.1f)", c.signal().getTicker(), c.score()))
                            .toList());
        }

        Map<String, String> candidateResultMap = new java.util.LinkedHashMap<>();

        for (BuyCandidate candidate : buyQueue) {
            String candidateTicker = candidate.signal().getTicker();
            boolean isMlCandidate = "ml-prediction".equals(candidate.signal().getStrategyName());
            // 전략별 매수 중단 체크
            if (isMlCandidate && adminConfigStore.isMlPaused()) {
                log.info("[Engine] ML 매수 중단 상태 — BUY 스킵 ticker: {}", candidateTicker);
                candidateResultMap.put(candidateTicker, "중단");
                continue;
            }
            if (!isMlCandidate && adminConfigStore.isPaused()) {
                log.info("[Engine] 매매 중단 상태 — BUY 스킵 ticker: {}", candidateTicker);
                candidateResultMap.put(candidateTicker, "중단");
                continue;
            }
            {
            // 2차 매수(물타기) 여부 확인 — stage=1 보유 중 + 신호 stage=2
            int newStage = candidate.signal().getBuyStage() != null ? candidate.signal().getBuyStage() : 2;
            boolean isSecondaryBuy = newStage == 2 && positions.stream()
                    .anyMatch(p -> p.getTicker().equals(candidateTicker)
                            && (p.getBuyStage() != null ? p.getBuyStage() : 2) == 1);

            if (!isSecondaryBuy) {
                int effectivePositions = positions.size() + boughtThisRun[0];
                if (effectivePositions >= maxPositions) {
                    log.info("[Engine] 최대 보유 종목 수 도달 ({}/{}) — BUY 스킵 ticker: {}",
                            effectivePositions, maxPositions, candidateTicker);
                    recordSkipped(candidate.signal(), marketState, "MAX_POSITIONS");
                    maxPosSkipsThisRun[0]++;
                    skippedList.add(new SkipRecord(candidateTicker, candidate.signal().getStockName(), "최대보유"));
                    candidateResultMap.put(candidateTicker, "한도초과");
                    continue;
                }

            } else {
                log.info("[Engine] 2차 매수(물타기) — maxPositions 제한 스킵 ticker: {}", candidateTicker);
            }

            // 전략별 보유한도 체크 (2차 매수 제외 — 기존 종목 추가매수라 보유 수 불변)
            if (!isSecondaryBuy) {
                String strategyName = candidate.signal().getStrategyName();
                int currentHeld = (int) positions.stream()
                        .filter(p -> strategyName.equals(p.getEntryTag()))
                        .count();
                int pendingThisRun = boughtThisRunByStrategy.getOrDefault(strategyName, 0);
                int holdingLimit = getDailyLimitForStrategy(strategyName);
                if (currentHeld + pendingThisRun >= holdingLimit) {
                    String skipMsg = (holdingLimit == 0) ? "전략비활성화" : "보유한도";
                    log.info("[Engine] 전략 보유한도 ({}/{}) — 스킵 ticker: {}, strategy: {}",
                            currentHeld + pendingThisRun, holdingLimit, candidateTicker, strategyName);
                    skippedList.add(new SkipRecord(candidateTicker, candidate.signal().getStockName(), skipMsg));
                    candidateResultMap.put(candidateTicker, "한도초과");
                    continue;
                }
            }

            boolean traded = handleSignal(candidate.signal(), positions, marketState.name());
            if (traded) {
                if (!isSecondaryBuy) {
                    boughtThisRun[0]++;
                    boughtThisRunByStrategy.merge(candidate.signal().getStrategyName(), 1, Integer::sum);
                }
                // entryTag 항상 전략명으로 저장
                strategyStateStore.saveEntryTag(candidateTicker, candidate.signal().getStrategyName());
                // ML 전략 매수 체결 시 staged → active 승격 + 전용 Slack 알림
                if ("ml-prediction".equals(candidate.signal().getStrategyName())) {
                    mlPositionStore.activate(candidateTicker, candidate.signal().getPrice());
                    mlDeferTracker.clear(candidateTicker);
                    mlPositionStore.getActive(candidateTicker).ifPresent(ap -> {
                        TradePlanDto plan = ap.plan();
                        slackNotifier.sendMlBuy(
                                candidateTicker, candidate.signal().getStockName(),
                                ap.actualEntryPrice(),
                                plan.confidence(), candidate.signal().getScore(),
                                plan.takeProfitPrice(), plan.stopLossPrice(),
                                plan.expectedDays(), plan.maxDays());
                    });
                }
                boughtList.add(new TradeRecord(
                        candidateTicker,
                        candidate.signal().getStockName(),
                        candidate.signal().getPrice(),
                        candidate.signal().getStrategyName()));
                candidateResultMap.put(candidateTicker, "매수");
            } else {
                candidateResultMap.put(candidateTicker, "예산부족");
            }
        } } // end buyQueue loop

        // ML 전략 BUY 후보 중 매수 미선정된 종목 → DEFER 카운트
        // 한도초과·예산부족·중단은 신호 품질과 무관한 외부 제약이므로 제외
        for (BuyCandidate candidate : buyQueue) {
            String t = candidate.signal().getTicker();
            String strat = candidate.signal().getStrategyName();
            if (!"ml-prediction".equals(strat)) continue;
            String outcome = candidateResultMap.getOrDefault(t, "대기");
            if ("매수".equals(outcome) || "한도초과".equals(outcome)
                    || "예산부족".equals(outcome) || "중단".equals(outcome)) continue;
            boolean countForBlacklist = mlPositionStore.getStaged(t)
                    .map(staged -> staged.multiplier() >= 0.90)
                    .orElse(true);
            mlDeferTracker.recordDefer(t, countForBlacklist);
            mlPositionStore.clearStaged(t);
        }

        // ML 활성 포지션 TP/SL/maxDays 청산 검사 — 당 사이클 매수 반영을 위해 포지션 재조회
        try {
            mlExitService.check(portfolioClient.getPositions());
        } catch (Exception ex) {
            log.warn("[Engine] MlExitService 체크 실패: {}", ex.getMessage());
        }

        // BUY 랭킹 저장
        int[] rank = {1};
        lastBuyRanking = buyQueue.stream()
                .limit(30)
                .map(c -> new EvalRankEntry(
                        rank[0]++,
                        c.signal().getTicker(),
                        c.signal().getStockName(),
                        c.signal().getStrategyName(),
                        c.score(),
                        c.signal().getReason(),
                        candidateResultMap.getOrDefault(c.signal().getTicker(), "대기")))
                .collect(java.util.stream.Collectors.toList());
        lastEvalAt = LocalDateTime.now(TradingCalendar.KST);

        // 당일 실행 이력 저장 (자정 기준 초기화)
        RunResult result = new RunResult(tickers.size(), boughtThisRun[0], soldThisRun[0], adminConfigStore.isPaused(),
                errorsThisRun[0], warnSkipsThisRun[0], maxPosSkipsThisRun[0],
                List.copyOf(boughtList), List.copyOf(soldList), List.copyOf(skippedList));
        if (!today.equals(lastRunDate)) {
            todayRuns.clear();
            lastRunDate = today;
        }
        todayRuns.add(new RunRecord(lastEvalAt, result.evaluated(), result.bought(), result.sold(),
                result.errors(), result.skippedMarketWarn(), result.skippedMaxPositions(),
                result.boughtList(), result.soldList(), result.skippedList()));

        log.info("[Engine] 전략 실행 완료 — 평가: {}개, BUY후보: {}개, 매수: {}건, 매도: {}건",
                tickers.size(), buyQueue.size(), boughtThisRun[0], soldThisRun[0]);
        return result;
    }

    private Optional<BuyCandidate> collectBuyCandidate(
            String ticker, int candleDays,
            List<String> activeStrategyNames,
            List<PortfolioItemDto> positions,
            int[] soldThisRun,
            int[] errorsThisRun,
            int[] warnSkipsThisRun,
            List<TradeRecord> soldList,
            List<SkipRecord> skippedList,
            MarketState marketState,
            boolean isEarlyMorning,
            boolean indexBlocked,
            int[] breadthStats) {
        StockPriceDto priceData = marketClient.getCurrentPrice(ticker);
        if (priceData == null || priceData.getCurrentPrice() == null) {
            log.warn("[Engine] 현재가 조회 실패 — ticker: {}", ticker);
            return Optional.empty();
        }

        List<CandleDto> historical = marketClient.getCandles(ticker, candleDays);
        if (historical.isEmpty()) {
            log.warn("[Engine] 캔들 데이터 없음 — ticker: {}", ticker);
            skippedList.add(new SkipRecord(ticker, priceData.getStockName(), "캔들없음"));
            return Optional.empty();
        }

        // market_breadth 누적: 전일 종가 대비 현재가 상승 여부 추적
        if (breadthStats != null) {
            java.math.BigDecimal prevClose = historical.get(historical.size() - 1).getClosePrice();
            java.math.BigDecimal currPrice = priceData.getCurrentPrice();
            if (prevClose != null && prevClose.compareTo(java.math.BigDecimal.ZERO) > 0) {
                breadthStats[1]++;
                if (currPrice.compareTo(prevClose) > 0) breadthStats[0]++;
            }
        }

        CandleDto liveCandle = CandleDto.builder()
                .tradeDate(LocalDate.now(TradingCalendar.KST))
                .openPrice(priceData.getOpenPrice())
                .highPrice(priceData.getHighPrice())
                .lowPrice(priceData.getLowPrice())
                .closePrice(priceData.getCurrentPrice())
                .volume(priceData.getVolume())
                .build();

        List<CandleDto> allCandles = new ArrayList<>(historical);
        LocalDate todayDate = LocalDate.now(TradingCalendar.KST);
        if (!allCandles.isEmpty() && todayDate.equals(allCandles.get(allCandles.size() - 1).getTradeDate())) {
            allCandles.set(allCandles.size() - 1, liveCandle); // 오늘 캔들이 이미 있으면 교체
        } else {
            allCandles.add(liveCandle); // 없으면 추가
        }

        BuyCandidate bestBuy = null;

        for (TradingStrategy strategy : strategies) {
            if (!activeStrategyNames.contains(strategy.getName())) continue;
            if (allCandles.size() < strategy.minimumCandles()) {
                log.warn("[Engine] 캔들 부족 — strategy: {}, 필요: {}, 실제: {}",
                        strategy.getName(), strategy.minimumCandles(), allCandles.size());
                if (skippedList.stream().noneMatch(s -> s.ticker().equals(ticker) && "캔들부족".equals(s.reason()))) {
                    skippedList.add(new SkipRecord(ticker, priceData.getStockName(), "캔들부족"));
                }
                continue;
            }

            try {
                SignalDto signal = strategy.evaluate(ticker, allCandles)
                        .toBuilder().stockName(priceData.getStockName()).build();
                
                // SELL 신호는 시장 상태 무관하게 즉시 처리
                // 단, entryTag가 있는 포지션은 매수한 전략만 SELL 처리 (전략 간 간섭 방지)
                if (signal.getAction() == SignalDto.Action.SELL) {
                    if (adminConfigStore.isSellPaused()) {
                        log.info("[Engine] 매도 중지 상태 — SELL 스킵 ticker: {}", ticker);
                        continue;
                    }
                    Optional<PortfolioItemDto> heldPos = positions.stream()
                            .filter(p -> p.getTicker().equals(ticker)).findFirst();
                    if (heldPos.isPresent() && heldPos.get().getEntryTag() != null
                            && !heldPos.get().getEntryTag().equals(strategy.getName())) {
                        log.debug("[Engine] SELL 스킵 — 매수전략({}) ≠ 현재전략({}) ticker: {}",
                                heldPos.get().getEntryTag(), strategy.getName(), ticker);
                        continue;
                    }
                    boolean traded = handleSignal(signal, positions, marketState.name());
                    if (traded) {
                        soldThisRun[0]++;
                        soldList.add(new TradeRecord(signal.getTicker(), signal.getStockName(), signal.getPrice(), signal.getStrategyName()));
                        rsiOverboughtHoldNotifiedDate.remove(ticker);
                    }
                    continue;
                }

                // RSI 과매수 홀드 — 보유 중인 종목에 최초 1회 Slack 알림
                if (signal.getAction() == SignalDto.Action.HOLD
                        && signal.getReason() != null
                        && signal.getReason().startsWith("RSI 과매수 홀드")) {
                    boolean isHeld = positions.stream().anyMatch(p -> p.getTicker().equals(ticker));
                    if (isHeld) {
                        LocalDate today = LocalDate.now(TradingCalendar.KST);
                        if (!today.equals(rsiOverboughtHoldNotifiedDate.get(ticker))) {
                            slackNotifier.sendRsiOverboughtHold(signal);
                            rsiOverboughtHoldNotifiedDate.put(ticker, today);
                        }
                    }
                }

                if (signal.getAction() != SignalDto.Action.BUY) continue;

                // ── BUY 신호 처리 ──
                
                // 1. 시장 경보 체크
                if (!priceData.isSafe()) {
                    log.warn("[Engine] 시장경보 종목 — BUY 스킵 ticker: {}, warnCode: {}",
                            ticker, priceData.getMarketWarnCode());
                    recordSkipped(signal, marketState, "MARKET_WARN");
                    warnSkipsThisRun[0]++;
                    skippedList.add(new SkipRecord(signal.getTicker(), signal.getStockName(), "시장경보"));
                    continue;
                }

                // 2. 어드민 지수 하락 차단 설정 시 매수 전면 차단
                if (indexBlocked) {
                    log.info("[Engine] 지수 하락 차단 — BUY 스킵 ticker: {}", ticker);
                    continue;
                }

                // 4. 당일 매도 종목 재매수 차단
                if (dailySellBlockService.isSoldToday(ticker)) {
                    log.info("[Engine] 당일 매도 종목 재매수 차단 — ticker: {}", ticker);
                    skippedList.add(new SkipRecord(signal.getTicker(), signal.getStockName(), "당일매도"));
                    continue;
                }

                // 5. 중복 보유 및 분할 매수 체크
                Optional<PortfolioItemDto> existing = positions.stream()
                        .filter(p -> p.getTicker().equals(ticker))
                        .findFirst();
                
                if (existing.isPresent()) {
                    PortfolioItemDto p = existing.get();
                    int currentStage = p.getBuyStage() != null ? p.getBuyStage() : 2; // 정보 없으면 완료로 간주
                    int newStage = signal.getBuyStage() != null ? signal.getBuyStage() : 2;

                    if (currentStage == 1 && newStage == 2) {
                        // 2차 매수는 평단가 하향이 목적 → 현재가 >= 1차 평단가이면 스킵
                        BigDecimal avgPrice = p.getAvgPrice();
                        if (avgPrice != null && signal.getPrice() != null
                                && signal.getPrice().compareTo(avgPrice) >= 0) {
                            log.info("[Engine] 2차 매수 스킵 — 현재가({}) >= 평단가({}) ticker: {}",
                                    signal.getPrice(), avgPrice, ticker);
                            skippedList.add(new SkipRecord(signal.getTicker(), signal.getStockName(), "평단가상향"));
                            continue;
                        }
                        log.info("[Engine] 2차 매수(물타기) 신호 포착 — ticker: {}, score: {}", ticker, signal.getScore());
                        // 통과
                    } else {
                        log.info("[Engine] 이미 보유 중(Stage {}) — BUY 스킵 ticker: {}", currentStage, ticker);
                        skippedList.add(new SkipRecord(signal.getTicker(), signal.getStockName(), "이미보유"));
                        continue;
                    }
                }

                // stage 2 신호인데 stage 1 포지션이 없으면 스킵 (RSI 단독 신규 매수 방지)
                if (signal.getBuyStage() != null && signal.getBuyStage() == 2 && existing.isEmpty()) {
                    log.info("[Engine] 2차 매수 신호이나 1차 포지션 없음 — BUY 스킵 ticker: {}", ticker);
                    skippedList.add(new SkipRecord(signal.getTicker(), signal.getStockName(), "2차신호"));
                    continue;
                }

                if (bestBuy == null || signal.getScore() > bestBuy.score()) {
                    bestBuy = new BuyCandidate(signal, allCandles, priceData);
                }
            } catch (Exception e) {
                log.error("[Engine] 전략 실행 오류 — ticker: {}, strategy: {}, error: {}",
                        ticker, strategy.getName(), e.getMessage());
                slackNotifier.sendError(String.format(
                        "ticker=%s, strategy=%s, %s", ticker, strategy.getName(), e.getMessage()));
                errorsThisRun[0]++;
            }
        }

        // MA5 계산 (타임컷용)
        BigDecimal ma5 = null;
        if (allCandles.size() >= 5) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = allCandles.size() - 5; i < allCandles.size(); i++) {
                sum = sum.add(allCandles.get(i).getClosePrice());
            }
            ma5 = sum.divide(BigDecimal.valueOf(5), 2, java.math.RoundingMode.HALF_UP);
        }

        timeCutService.checkAndCut(ticker, priceData.getCurrentPrice(), positions, ma5);

        return Optional.ofNullable(bestBuy);
    }

    private boolean handleSignal(SignalDto signal, List<PortfolioItemDto> positions, String marketStateName) {
        int quantity;
        int investKrw = 0;
        BigDecimal avgBuyPrice = null;
        if (signal.getAction() == SignalDto.Action.BUY) {
            int totalInvest = adminConfigStore.getInvestAmountKrw();
            int stage = signal.getBuyStage() != null ? signal.getBuyStage() : 2;
            if ("rsi-bollinger".equals(signal.getStrategyName())) {
                if (stage == 1) {
                    investKrw = totalInvest / 2;  // 1차: 절반 매수
                } else {
                    int reserved = bollingerReserveService.getReserved(signal.getTicker());
                    investKrw = reserved > 0 ? reserved : totalInvest / 2;  // 2차: 예약 금액 사용
                }
            } else {
                investKrw = totalInvest;
            }
            quantity = (int) (investKrw / signal.getPrice().doubleValue());
            if (quantity < 1) {
                log.warn("[Engine] 투자금액 부족 — BUY 스킵 ticker: {}, price: {}, budget: {}원",
                        signal.getTicker(), signal.getPrice(), investKrw);
                orderClient.recordSkipped(SkippedSignalRequest.builder()
                        .ticker(signal.getTicker())
                        .stockName(signal.getStockName())
                        .price(signal.getPrice())
                        .strategyName(signal.getStrategyName())
                        .marketState(marketStateName)
                        .skipReason("BUDGET_INSUFFICIENT")
                        .build());
                return false;
            }
        } else {
            Optional<PortfolioItemDto> position = positions.stream()
                    .filter(p -> p.getTicker().equals(signal.getTicker()))
                    .findFirst();
            if (position.isEmpty()) {
                log.info("[Engine] 보유 포지션 없음 — SELL 스킵 ticker: {}", signal.getTicker());
                return false;
            }
            quantity = position.get().getQuantity();
            avgBuyPrice = position.get().getAvgPrice();
        }

        OrderRequest orderRequest = OrderRequest.builder()
                .ticker(signal.getTicker())
                .stockName(signal.getStockName())
                .quantity(quantity)
                .price(signal.getPrice())
                .strategyName(signal.getStrategyName())
                .marketState(marketStateName)
                .closeReason("SIGNAL")
                .build();

        OrderResult result = switch (signal.getAction()) {
            case BUY -> {
                OrderResult r = orderClient.buy(orderRequest);
                if (r.success()) {
                    int stage = signal.getBuyStage() != null ? signal.getBuyStage() : 2;

                    timeCutService.recordBuy(signal.getTicker(), signal.getStrategyName());
                    strategyStateStore.saveBuyStage(signal.getTicker(), stage);

                    if ("volatility-breakout".equals(signal.getStrategyName())) {
                        volatilityBreakoutStrategy.markBought(signal.getTicker());
                    }

                    // 볼린저 분할 매수: 1차 성공 시 나머지 절반 예약, 2차 성공 시 예약 해제
                    if ("rsi-bollinger".equals(signal.getStrategyName())) {
                        int totalInvest = adminConfigStore.getInvestAmountKrw();
                        if (stage == 1) {
                            bollingerReserveService.reserve(signal.getTicker(), signal.getStockName(), totalInvest - investKrw);
                        } else {
                            bollingerReserveService.clear(signal.getTicker());
                        }
                    }
                }
                yield r;
            }
            case SELL -> {
                OrderResult r = orderClient.sell(orderRequest);
                if (r.success()) {
                    dailySellBlockService.markSoldToday(signal.getTicker());
                    timeCutService.clearBuy(signal.getTicker());
                    strategyStateStore.removeBuyStage(signal.getTicker());
                    strategyStateStore.removeEntryTag(signal.getTicker());
                    bollingerReserveService.clear(signal.getTicker());
                }
                yield r;
            }
            default -> OrderResult.fail("알 수 없는 액션");
        };

        // ML 매수 성공 알림은 activate() 후 sendMlBuy()로 처리
        if (!(result.success() && "ml-prediction".equals(signal.getStrategyName())
                && signal.getAction() == SignalDto.Action.BUY)) {
            slackNotifier.sendTradeResult(signal, result.success(), result.errorMsg(), avgBuyPrice);
        }
        return result.success();
    }

    private List<String> getActiveStrategyNames(MarketState state) {
        List<String> enabled = strategyConfig.getEnabledStrategies();
        if ("all-strategies".equals(adminConfigStore.getStrategyMode())) return enabled;
        if (!strategyConfig.getMarketFilter().isEnabled()) return enabled;
        // ml-prediction 은 시장 regime 피처를 자체 학습하므로 모든 상태에서 실행
        return switch (state) {
            case BULLISH  -> enabled.stream()
                    .filter(s -> List.of("volatility-breakout", "golden-cross", "ml-prediction").contains(s))
                    .toList();
            case SIDEWAYS, BEARISH -> enabled.stream()
                    .filter(s -> List.of("rsi-bollinger", "ml-prediction").contains(s))
                    .toList();
        };
    }

    private int getDailyLimitForStrategy(String strategyName) {
        return switch (strategyName) {
            case "volatility-breakout" -> adminConfigStore.getVolatilityBreakoutDailyLimit();
            case "golden-cross"        -> adminConfigStore.getGoldenCrossDailyLimit();
            case "rsi-bollinger"       -> adminConfigStore.getBollingerDailyLimit();
            case "ml-prediction"       -> adminConfigStore.getMlDailyLimit();
            default                    -> adminConfigStore.getMaxPositions();
        };
    }

    private void recordSkipped(SignalDto signal, MarketState marketState, String skipReason) {
        orderClient.recordSkipped(SkippedSignalRequest.builder()
                .ticker(signal.getTicker())
                .stockName(signal.getStockName())
                .price(signal.getPrice())
                .strategyName(signal.getStrategyName())
                .marketState(marketState.name())
                .skipReason(skipReason)
                .build());
    }

    // ── Signal Gap 조회 ───────────────────────────────────────────────────────

    public void triggerSignalGapRefresh(int topN) {
        if (signalGapRunning) return;
        signalGapRunning = true;
        CompletableFuture.runAsync(() -> {
            try {
                signalGapCache = calcSignalGapsInternal(topN);
                signalGapComputedAt = LocalDateTime.now(TradingCalendar.KST);
            } catch (Exception e) {
                log.error("[SignalGap] 계산 오류: {}", e.getMessage());
            } finally {
                signalGapRunning = false;
            }
        });
    }

    public List<SignalGapDto> getSignalGapCache()  { return signalGapCache; }
    public LocalDateTime getSignalGapComputedAt()  { return signalGapComputedAt; }
    public boolean isSignalGapRunning()            { return signalGapRunning; }

    private List<SignalGapDto> calcSignalGapsInternal(int topN) {
        MarketState state = marketStateService.getCurrentState();
        int candleDays = strategyConfig.getCandleDays();
        List<SignalGapDto> gaps = new ArrayList<>();

        for (String ticker : watchTickers) {
            try {
                StockPriceDto price = marketClient.getCurrentPrice(ticker);
                if (price == null || price.getCurrentPrice() == null) continue;

                List<CandleDto> candles = marketClient.getCandles(ticker, candleDays);
                if (candles == null || candles.isEmpty()) continue;

                CandleDto liveCandle = CandleDto.builder()
                        .tradeDate(LocalDate.now(TradingCalendar.KST))
                        .openPrice(price.getOpenPrice())
                        .highPrice(price.getHighPrice())
                        .lowPrice(price.getLowPrice())
                        .closePrice(price.getCurrentPrice())
                        .volume(price.getVolume())
                        .build();
                List<CandleDto> all = new ArrayList<>(candles);
                LocalDate todayDate = LocalDate.now(TradingCalendar.KST);
                if (!all.isEmpty() && todayDate.equals(all.get(all.size() - 1).getTradeDate())) {
                    all.set(all.size() - 1, liveCandle); // 오늘 캔들이 이미 있으면 교체
                } else {
                    all.add(liveCandle); // 없으면 추가
                }

                if ("all-strategies".equals(adminConfigStore.getStrategyMode())) {
                    SignalGapDto vg = calcVolBreakoutGap(ticker, price.getStockName(), all);
                    if (vg != null) gaps.add(vg);
                    SignalGapDto gg = calcGoldenCrossGap(ticker, price.getStockName(), all);
                    if (gg != null) gaps.add(gg);
                    SignalGapDto rg = calcRsiBollingerGap(ticker, price.getStockName(), all);
                    if (rg != null) gaps.add(rg);
                } else if (state == MarketState.SIDEWAYS || state == MarketState.BEARISH) {
                    SignalGapDto gap = calcRsiBollingerGap(ticker, price.getStockName(), all);
                    if (gap != null) gaps.add(gap);
                } else {
                    // BULLISH: 변동성돌파 + 골든크로스
                    SignalGapDto volGap = calcVolBreakoutGap(ticker, price.getStockName(), all);
                    if (volGap != null) gaps.add(volGap);
                    SignalGapDto gcGap = calcGoldenCrossGap(ticker, price.getStockName(), all);
                    if (gcGap != null) gaps.add(gcGap);
                }
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[SignalGap] {} 처리 오류: {}", ticker, e.getMessage());
            }
        }

        gaps.sort(Comparator.comparingDouble(SignalGapDto::score).reversed()
                .thenComparingDouble(SignalGapDto::gapPct));
        List<SignalGapDto> result = new ArrayList<>();
        int rank = 1;
        for (int i = 0; i < gaps.size() && result.size() < topN; i++) {
            SignalGapDto g = gaps.get(i);
            result.add(new SignalGapDto(rank++, g.ticker(), g.stockName(), g.strategy(),
                    g.currentPrice(), g.threshold(), g.gapPct(), g.rsi(), g.bbUpper(), g.score(), g.detail()));
        }
        return result;
    }

    private SignalGapDto calcRsiBollingerGap(String ticker, String stockName, List<CandleDto> candles) {
        if (candles.size() < 21) return null; // BB_PERIOD(20) + 라이브
        int endIdx = candles.size() - 1;
        double currentPrice = candles.get(endIdx).getClosePrice().doubleValue();

        // 볼린저밴드 하단/상단 계산 (BB_PERIOD=20, 2σ)
        int bbStart = endIdx - 20 + 1;
        double sum = 0;
        for (int i = bbStart; i <= endIdx; i++) sum += candles.get(i).getClosePrice().doubleValue();
        double middle = sum / 20;
        double variance = 0;
        for (int i = bbStart; i <= endIdx; i++) {
            double diff = candles.get(i).getClosePrice().doubleValue() - middle;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / 20);
        double lowerBand = middle - 2.0 * stdDev;
        double upperBand = middle + 2.0 * stdDev;

        // RSI(14) 계산
        double rsi = calcRsiForGap(candles, endIdx);

        // 예상 점수 계산 (V2 로직 적용)
        double bbScore = 0;
        if (currentPrice < lowerBand) {
            double bandGapPct = (lowerBand - currentPrice) / lowerBand * 100;
            bbScore = Math.min(bandGapPct / 5.0, 1.0) * 50;
        }
        double rsiScore = 0;
        if (rsi < 30.0) {
            rsiScore = Math.min((30.0 - rsi) / 30.0, 1.0) * 50;
        }

        double gapPct;
        if (currentPrice < lowerBand && rsi >= 30.0) {
            // 1차 만족 상태: RSI 30까지 남은 %를 gapPct로 사용 (정렬을 위해)
            gapPct = (rsi - 30.0) / rsi * 100;
        } else {
            // 기본 상태: BB 하단까지 남은 %
            gapPct = (currentPrice - lowerBand) / currentPrice * 100;
        }

        double expectedScore;
        if (bbScore > 0) {
            // 하단밴드 이탈 상태 — 베이스 50점 추가로 접근 중 종목과 구간 분리 (최소 51점, 최대 150점)
            expectedScore = 50 + bbScore + rsiScore;
        } else {
            // 하단밴드 접근 중 — 20% 이내인 경우에만 근접도 점수 부여 (최대 50점)
            double approachPct = gapPct; // currentPrice >= lowerBand 구간에서 gapPct와 동일
            if (approachPct <= 20.0) {
                double proximityScore = (20.0 - approachPct) / 20.0 * 30;  // 최대 30점
                double rsiProxScore = rsi < 50.0 ? (50.0 - rsi) / 50.0 * 20 : 0;  // 최대 20점
                expectedScore = proximityScore + rsiProxScore;
            } else {
                expectedScore = 0;  // 20% 초과 이격은 제외
            }
        }

        String detail = String.format("RSI=%.1f, 하단밴드=%.0f, 상단밴드=%.0f", rsi, lowerBand, upperBand);
        return new SignalGapDto(0, ticker, stockName, "rsi-bollinger",
                currentPrice, lowerBand, gapPct, rsi, upperBand, expectedScore, detail);
    }

    private SignalGapDto calcGoldenCrossGap(String ticker, String stockName, List<CandleDto> candles) {
        if (candles.size() < 21) return null;
        int endIdx = candles.size() - 1;

        double ma5Sum = 0, ma20Sum = 0;
        for (int i = endIdx - 4; i <= endIdx; i++)
            ma5Sum += candles.get(i).getClosePrice().doubleValue();
        for (int i = endIdx - 19; i <= endIdx; i++)
            ma20Sum += candles.get(i).getClosePrice().doubleValue();
        double ma5  = ma5Sum / 5;
        double ma20 = ma20Sum / 20;

        double gapPct = (ma20 - ma5) / ma20 * 100;
        double currentPrice = candles.get(endIdx).getClosePrice().doubleValue();

        // 진입점수 = MA5/MA20 이격 강도(50점) + 거래량 비율(50점)
        double maGapPct = (ma5 - ma20) / ma20 * 100; // 양수 = MA5가 MA20 상회
        double maScore = maGapPct > 0 ? Math.min(maGapPct / 2.0, 1.0) * 50 : 0; // 2% 이격 = 50점
        double avgVol = calcAvgVolume(candles, 20);
        double volRatio = avgVol > 0 ? (double) candles.get(endIdx).getVolume() / avgVol : 0;
        double volScore = Math.min(volRatio / 3.0, 1.0) * 50;
        double score = maScore + volScore;

        String detail = String.format("MA5=%.0f, MA20=%.0f, 이격=%.2f%%", ma5, ma20, maGapPct);
        return new SignalGapDto(0, ticker, stockName, "golden-cross",
                currentPrice, ma20, gapPct, -1, 0, score, detail);
    }

    private SignalGapDto calcVolBreakoutGap(String ticker, String stockName, List<CandleDto> candles) {
        if (candles.size() < 2) return null;
        CandleDto today     = candles.get(candles.size() - 1);
        CandleDto yesterday = candles.get(candles.size() - 2);

        double range       = yesterday.getHighPrice().subtract(yesterday.getLowPrice()).doubleValue();
        double targetPrice = today.getOpenPrice().doubleValue() + range * 0.5;
        double currentPrice = today.getClosePrice().doubleValue();

        double gapPct = (targetPrice - currentPrice) / currentPrice * 100;

        // 진입점수 = 거래량 비율(50점) + 목표가 근접도(50점)
        double avgVol = calcAvgVolume(candles, 20);
        double volRatio = avgVol > 0 ? (double) today.getVolume() / avgVol : 0;
        double volScore = Math.min(volRatio / 3.0, 1.0) * 50;          // 3배 거래량 = 50점
        double proximity;
        if (gapPct < 0) {
            // 이미 돌파: 돌파 강도로 점수 부여 (2% 돌파 = 50점)
            double breakoutPct = -gapPct;
            proximity = Math.min(breakoutPct / 2.0, 1.0) * 50;
        } else {
            // 미달: 목표가까지 남은 거리로 점수 부여 (5% 이내 = 0~50점)
            proximity = Math.max(0, (5.0 - gapPct) / 5.0) * 50;
        }
        double score = volScore + proximity;

        String detail = String.format("목표가=%.0f, Range=%.0f, 거래량비율=%.1fx", targetPrice, range, volRatio);
        return new SignalGapDto(0, ticker, stockName, "volatility-breakout",
                currentPrice, targetPrice, gapPct, -1, 0, score, detail);
    }

    // ── ML 드라이런 ────────────────────────────────────────────────────────────

    public record MlDryRunResult(
            String ticker, String stockName,
            double confidence,        // raw ML 확신도 (0~1)
            double mlScore,           // confidence × 100 (EntryQuality 곱셈 기반)
            double entryMultiplier,   // EntryQuality 배수 (0.5~1.1; DEFER이면 0)
            double effectiveScore,    // mlScore × entryMultiplier (실제 경쟁에 쓰이는 점수)
            boolean aboveThreshold,   // confidence >= mlBuyThreshold
            boolean deferred,         // DEFER 블랙리스트 또는 EntryQuality 강제 DEFER
            boolean alreadyHeld,      // 이미 ML 보유 포지션
            String reason
    ) {}

    /** 실제 주문 없이 watch-tickers 전체에 ML 예측을 실행하고 실제 매수 경쟁 점수(effectiveScore) 순으로 반환. */
    public List<MlDryRunResult> runMlDryRun() {
        int candleDays = strategyConfig.getCandleDays();
        List<CandleDto> indexCandles = marketStateService.getKospiCandlesCached();
        double threshold = adminConfigStore.getMlBuyThreshold();
        double marketBreadth = marketStateService.getMarketBreadth();
        boolean entryTimingEnabled = adminConfigStore.isMlEntryTimingEnabled();
        Map<String, String> tickerNames = marketClient.getTickerNames();
        List<MlDryRunResult> results = new ArrayList<>();

        for (String ticker : watchTickers) {
            try {
                List<CandleDto> candles = marketClient.getCandles(ticker, candleDays);
                if (candles == null || candles.isEmpty()) continue;
                TradePlanDto plan = mlClient.predict(ticker, candles, indexCandles, marketBreadth);
                if (plan == null) continue;

                String name = tickerNames.getOrDefault(ticker, ticker);
                boolean aboveThreshold = plan.confidence() >= threshold;
                boolean alreadyHeld = mlPositionStore.isActive(ticker);
                boolean deferred = mlDeferTracker.isBlacklisted(ticker);

                double multiplier = 1.0;
                if (!deferred && aboveThreshold && entryTimingEnabled) {
                    multiplier = entryQualityEvaluator.evaluate(ticker, plan.entryPrice(), candles);
                    if (multiplier <= 0.0) {
                        deferred = true;
                        multiplier = 0.0;
                    }
                } else if (!entryTimingEnabled) {
                    multiplier = 1.0;
                }

                double effectiveScore = deferred ? 0.0 : plan.mlScore() * multiplier;

                results.add(new MlDryRunResult(
                        ticker, name,
                        plan.confidence(), plan.mlScore(),
                        deferred ? 0.0 : multiplier,
                        effectiveScore,
                        aboveThreshold, deferred, alreadyHeld,
                        plan.reason()
                ));
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[MlDryRun] 오류 — ticker: {}, error: {}", ticker, e.getMessage());
            }
        }

        // 정렬: 매수후보(임계값 이상·비보유·비DEFER) → 보유중 → 임계값미달 → DEFER
        results.sort(Comparator
                .comparingInt((MlDryRunResult r) -> {
                    if (!r.deferred() && r.aboveThreshold() && !r.alreadyHeld()) return 0;
                    if (r.alreadyHeld()) return 1;
                    if (!r.aboveThreshold()) return 2;
                    return 3;
                })
                .thenComparingDouble(r -> -r.effectiveScore())
        );
        return results;
    }

    private double calcAvgVolume(List<CandleDto> candles, int period) {
        int end = candles.size() - 1;
        int start = Math.max(0, end - period);
        double sum = 0;
        int count = 0;
        for (int i = start; i < end; i++) { // 당일 제외
            sum += candles.get(i).getVolume();
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    /** Wilder's Smoothed RSI(14) — StrategyEngine 내부용 */
    private double calcRsiForGap(List<CandleDto> candles, int endIndex) {
        int rsiPeriod = 14;
        int start = endIndex - rsiPeriod;
        if (start < 0) return 50.0;

        double avgGain = 0, avgLoss = 0;
        for (int i = start + 1; i <= start + rsiPeriod; i++) {
            double change = candles.get(i).getClosePrice()
                    .subtract(candles.get(i - 1).getClosePrice()).doubleValue();
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= rsiPeriod;
        avgLoss /= rsiPeriod;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
