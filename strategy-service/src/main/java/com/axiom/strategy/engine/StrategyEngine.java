package com.axiom.strategy.engine;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
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
import com.axiom.strategy.notification.SlackNotifier;
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
    private final List<TradingStrategy> strategies;

    private volatile List<String> watchTickers = List.of();
    private volatile List<SignalGapDto> signalGapCache = List.of();
    private volatile LocalDateTime signalGapComputedAt = null;
    private volatile boolean signalGapRunning = false;
    /** 모드별 마지막 BUY 랭킹: "paper"|"real" → List<EvalRankEntry> */
    private final Map<String, List<EvalRankEntry>> lastBuyRankingByMode = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastEvalAt;
    /** 모드별 당일 실행 이력: "paper"|"real" → List<RunRecord> */
    private final Map<String, List<RunRecord>> todayRunsByMode = new ConcurrentHashMap<>();
    private volatile LocalDate lastRunDate = null;
    /** 수동 즉시 실행 상태 */
    private volatile boolean manualRunning = false;
    private volatile String lastManualRunMessage = null;

    @PostConstruct
    public void init() {
        watchTickers = strategyConfig.getWatchTickers();
        log.info("[Engine] 초기 감시 종목 로드 — yml fallback {}개", watchTickers.size());
    }

    public void updateWatchTickers(List<String> tickers) {
        watchTickers = tickers;
    }

    public List<EvalRankEntry> getLastBuyRanking() {
        String mode = adminConfigStore.getTradingMode();
        return lastBuyRankingByMode.getOrDefault(mode, List.of());
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
        String mode = adminConfigStore.getTradingMode();
        List<RunRecord> runs = todayRunsByMode.get(mode);
        return runs != null ? List.copyOf(runs) : List.of();
    }

    public int getWatchTickerCount() {
        return watchTickers.size();
    }

    public List<PortfolioItemDto> getEnrichedPortfolio() {
        String mode = adminConfigStore.getTradingMode();
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        Map<String, Integer> stages = strategyStateStore.loadAllBuyStages(mode);
        Map<String, String> tags = strategyStateStore.loadAllEntryTags(mode);
        positions.forEach(p -> {
            Integer s = stages.get(p.getTicker());
            if (s != null) p.withBuyStage(s);
            String tag = tags.get(p.getTicker());
            if (tag != null) p.withEntryTag(tag);
        });
        return positions;
    }

    public record TradeRecord(String ticker, String stockName, BigDecimal price) {}
    public record SkipRecord(String ticker, String stockName, String reason) {}
    private record BuyCandidate(SignalDto signal, List<CandleDto> allCandles, StockPriceDto priceData) {
        double score() { return signal.getScore(); }
    }
    public record RunRecord(LocalDateTime runAt, int evaluated, int bought, int sold,
                            int errors, int skippedMarketWarn, int skippedMaxPositions,
                            List<TradeRecord> boughtList, List<TradeRecord> soldList,
                            List<SkipRecord> skippedList, String tradingMode) {}

    public record EvalRankEntry(
            int rank,
            String ticker,
            String stockName,
            String strategyName,
            double score,
            String reason,
            String result,    // "매수" | "한도초과" | "예산부족" | "이미보유"
            String tradingMode
    ) {}

    public record RunResult(int evaluated, int bought, int sold, boolean paused,
                            int errors, int skippedMarketWarn, int skippedMaxPositions,
                            List<TradeRecord> boughtList, List<TradeRecord> soldList,
                            List<SkipRecord> skippedList) {}

    public RunResult run() {
        String tradingMode = adminConfigStore.getTradingMode();

        if (adminConfigStore.isPaused()) {
            log.info("[Engine][{}] 매매 중단 상태 — 전략 실행 스킵", tradingMode);
            return new RunResult(0, 0, 0, true, 0, 0, 0, List.of(), List.of(), List.of());
        }

        MarketState marketState = marketStateService.getCurrentState();
        List<String> tickers = watchTickers;
        List<String> activeStrategyNames = getActiveStrategyNames(marketState);
        int candleDays = strategyConfig.getCandleDays();
        int maxPositions = adminConfigStore.getMaxPositions();

        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        Map<String, Integer> stages = strategyStateStore.loadAllBuyStages(tradingMode);
        Map<String, String> entryTags = strategyStateStore.loadAllEntryTags(tradingMode);
        positions.forEach(p -> {
            Integer s = stages.get(p.getTicker());
            if (s != null) p.withBuyStage(s);
            String tag = entryTags.get(p.getTicker());
            if (tag != null) p.withEntryTag(tag);
        });

        int bollingerMaxPositions = adminConfigStore.getBollingerMaxPositions();
        int trendMaxPositions     = maxPositions - bollingerMaxPositions;

        long bollingerHeld = positions.stream()
                .filter(p -> "rsi-bollinger".equals(p.getEntryTag())).count();
        long trendHeld     = positions.stream()
                .filter(p -> p.getEntryTag() != null
                        && List.of("golden-cross", "volatility-breakout").contains(p.getEntryTag())).count();

        int[] boughtThisRun           = {0};
        int[] soldThisRun             = {0};
        int[] errorsThisRun           = {0};
        int[] warnSkipsThisRun        = {0};
        int[] maxPosSkipsThisRun      = {0};
        int[] extremeFearBuysThisRun  = {0};
        int[] boughtBollingerThisRun  = {0};
        int[] boughtTrendThisRun      = {0};
        final int EXTREME_FEAR_EXTRA_SLOTS = 2;
        List<TradeRecord> boughtList  = new ArrayList<>();
        List<TradeRecord> soldList    = new ArrayList<>();
        List<SkipRecord>  skippedList = new ArrayList<>();

        log.info("[Engine][{}] 전략 실행 시작 — 시장: {}, tickers: {}개, 전략: {}, 보유: {}개/{}개",
                tradingMode, marketState, tickers.size(), activeStrategyNames, positions.size(), maxPositions);

        // ── Phase 1: 전체 종목 평가 ──────────────────────────────────────────
        LocalTime nowKst = LocalTime.now(TradingCalendar.KST);
        boolean isEarlyMorning = nowKst.isBefore(LocalTime.of(9, 20));
        boolean indexBlocked = marketStateService.isIndexDropBlockedToday() && adminConfigStore.getIndexDropBlockPct() > 0;

        List<BuyCandidate> buyQueue = new ArrayList<>();
        for (String ticker : tickers) {
            try {
                Optional<BuyCandidate> candidate = collectBuyCandidate(
                        ticker, candleDays, activeStrategyNames, positions,
                        soldThisRun, errorsThisRun, warnSkipsThisRun,
                        soldList, skippedList, marketState, isEarlyMorning, indexBlocked);
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

        // ── Phase 1.5: 지수 하락률 체크 플래그 갱신 (이미 9:20 이후이고 체크 안했다면) ──
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
            // 2차 매수(물타기) 여부 확인 — stage=1 보유 중 + 신호 stage=2
            String candidateTicker = candidate.signal().getTicker();
            int newStage = candidate.signal().getBuyStage() != null ? candidate.signal().getBuyStage() : 2;
            boolean isSecondaryBuy = newStage == 2 && positions.stream()
                    .anyMatch(p -> p.getTicker().equals(candidateTicker)
                            && (p.getBuyStage() != null ? p.getBuyStage() : 2) == 1);

            boolean isExtremeFearBypass = candidate.signal().getScore() >= 80
                    && (marketState == MarketState.BEARISH || indexBlocked);

            if (!isSecondaryBuy) {
                int effectivePositions = positions.size() + boughtThisRun[0];
                if (effectivePositions >= maxPositions) {
                    if (isExtremeFearBypass && extremeFearBuysThisRun[0] < EXTREME_FEAR_EXTRA_SLOTS) {
                        log.info("[Engine] 극단공포 bypass — maxPositions({}) 초과 허용 ({}/{}슬롯) ticker: {}",
                                maxPositions, extremeFearBuysThisRun[0] + 1, EXTREME_FEAR_EXTRA_SLOTS, candidateTicker);
                    } else {
                        log.info("[Engine] 최대 보유 종목 수 도달 ({}/{}) — BUY 스킵 ticker: {}",
                                effectivePositions, maxPositions, candidateTicker);
                        recordSkipped(candidate.signal(), marketState, "MAX_POSITIONS");
                        maxPosSkipsThisRun[0]++;
                        skippedList.add(new SkipRecord(candidateTicker, candidate.signal().getStockName(), "최대보유"));
                        candidateResultMap.put(candidateTicker, "한도초과");
                        continue;
                    }
                }

                // 그룹별 슬롯 체크 (EXTREME_FEAR bypass 시 그룹 체크도 우회)
                if (!isExtremeFearBypass) {
                    String strategyName = candidate.signal().getStrategyName();
                    boolean isBollinger = "rsi-bollinger".equals(strategyName);
                    if (isBollinger) {
                        long bollingerUsed = bollingerHeld + boughtBollingerThisRun[0];
                        if (bollingerUsed >= bollingerMaxPositions) {
                            log.info("[Engine] 볼린저 그룹 슬롯 한도 도달 ({}/{}) — 스킵 ticker: {}",
                                    bollingerUsed, bollingerMaxPositions, candidateTicker);
                            recordSkipped(candidate.signal(), marketState, "GROUP_LIMIT");
                            maxPosSkipsThisRun[0]++;
                            skippedList.add(new SkipRecord(candidateTicker, candidate.signal().getStockName(), "그룹한도(볼린저)"));
                            candidateResultMap.put(candidateTicker, "한도초과");
                            continue;
                        }
                    } else {
                        long trendUsed = trendHeld + boughtTrendThisRun[0];
                        if (trendUsed >= trendMaxPositions) {
                            log.info("[Engine] 추세 그룹 슬롯 한도 도달 ({}/{}) — 스킵 ticker: {}",
                                    trendUsed, trendMaxPositions, candidateTicker);
                            recordSkipped(candidate.signal(), marketState, "GROUP_LIMIT");
                            maxPosSkipsThisRun[0]++;
                            skippedList.add(new SkipRecord(candidateTicker, candidate.signal().getStockName(), "그룹한도(추세)"));
                            candidateResultMap.put(candidateTicker, "한도초과");
                            continue;
                        }
                    }
                }
            } else {
                log.info("[Engine] 2차 매수(물타기) — maxPositions 제한 스킵 ticker: {}", candidateTicker);
            }

            boolean traded = handleSignal(candidate.signal(), positions, marketState.name());
            if (traded) {
                if (!isSecondaryBuy) {
                    boughtThisRun[0]++;
                    if (isExtremeFearBypass && (positions.size() + boughtThisRun[0] - 1) >= maxPositions) {
                        extremeFearBuysThisRun[0]++;
                    }
                    // 그룹 카운터 갱신
                    if ("rsi-bollinger".equals(candidate.signal().getStrategyName())) {
                        boughtBollingerThisRun[0]++;
                    } else {
                        boughtTrendThisRun[0]++;
                    }
                }
                // entryTag 항상 전략명으로 저장
                strategyStateStore.saveEntryTag(candidateTicker, candidate.signal().getStrategyName(), tradingMode);
                boughtList.add(new TradeRecord(
                        candidateTicker,
                        candidate.signal().getStockName(),
                        candidate.signal().getPrice()));
                candidateResultMap.put(candidateTicker, "매수");
            } else {
                candidateResultMap.put(candidateTicker, "예산부족");
            }
        }

        // BUY 랭킹 저장
        int[] rank = {1};
        List<EvalRankEntry> ranking = buyQueue.stream()
                .limit(30)
                .map(c -> new EvalRankEntry(
                        rank[0]++,
                        c.signal().getTicker(),
                        c.signal().getStockName(),
                        c.signal().getStrategyName(),
                        c.score(),
                        c.signal().getReason(),
                        candidateResultMap.getOrDefault(c.signal().getTicker(), "대기"),
                        tradingMode))
                .collect(java.util.stream.Collectors.toList());
        lastBuyRankingByMode.put(tradingMode, ranking);
        lastEvalAt = LocalDateTime.now(TradingCalendar.KST);

        // 당일 실행 이력 저장 (자정 기준 초기화)
        RunResult result = new RunResult(tickers.size(), boughtThisRun[0], soldThisRun[0], false,
                errorsThisRun[0], warnSkipsThisRun[0], maxPosSkipsThisRun[0],
                List.copyOf(boughtList), List.copyOf(soldList), List.copyOf(skippedList));
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        if (!today.equals(lastRunDate)) {
            todayRunsByMode.clear();
            lastRunDate = today;
        }
        todayRunsByMode.computeIfAbsent(tradingMode,
                k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new RunRecord(lastEvalAt, result.evaluated(), result.bought(), result.sold(),
                        result.errors(), result.skippedMarketWarn(), result.skippedMaxPositions(),
                        result.boughtList(), result.soldList(), result.skippedList(), tradingMode));

        log.info("[Engine][{}] 전략 실행 완료 — 평가: {}개, BUY후보: {}개, 매수: {}건, 매도: {}건",
                tradingMode, tickers.size(), buyQueue.size(), boughtThisRun[0], soldThisRun[0]);
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
            boolean indexBlocked) {
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

        CandleDto liveCandle = CandleDto.builder()
                .tradeDate(LocalDate.now(TradingCalendar.KST))
                .openPrice(priceData.getOpenPrice())
                .highPrice(priceData.getHighPrice())
                .lowPrice(priceData.getLowPrice())
                .closePrice(priceData.getCurrentPrice())
                .volume(priceData.getVolume())
                .build();

        List<CandleDto> allCandles = new ArrayList<>(historical);
        allCandles.add(liveCandle);

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
                if (signal.getAction() == SignalDto.Action.SELL) {
                    boolean traded = handleSignal(signal, positions, marketState.name());
                    if (traded) {
                        soldThisRun[0]++;
                        soldList.add(new TradeRecord(signal.getTicker(), signal.getStockName(), signal.getPrice()));
                    }
                    continue;
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

                // 2. Bypass 로직: 점수가 80점 이상이면 하락장/지수블락 무시 (단, 09:20 이전은 무조건 스킵)
                boolean isExtremeFear = signal.getScore() >= 80;
                if (isEarlyMorning) {
                    continue; // 09:20 이전은 무조건 스킵
                }
                
                if (!isExtremeFear) {
                    if (marketState == MarketState.BEARISH) {
                        log.info("[Engine] 하락장 — BUY 스킵 (점수 {} < 80)", signal.getScore());
                        continue;
                    }
                    if (indexBlocked) {
                        log.info("[Engine] 지수 하락 차단 — BUY 스킵 (점수 {} < 80)", signal.getScore());
                        continue;
                    }
                }

                // 3. 중복 보유 및 분할 매수 체크
                Optional<PortfolioItemDto> existing = positions.stream()
                        .filter(p -> p.getTicker().equals(ticker))
                        .findFirst();
                
                if (existing.isPresent()) {
                    PortfolioItemDto p = existing.get();
                    int currentStage = p.getBuyStage() != null ? p.getBuyStage() : 2; // 정보 없으면 완료로 간주
                    int newStage = signal.getBuyStage() != null ? signal.getBuyStage() : 2;

                    if (currentStage == 1 && newStage == 2) {
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
        if (signal.getAction() == SignalDto.Action.BUY) {
            int investKrw = adminConfigStore.getInvestAmountKrw();
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
                    String tradingMode = adminConfigStore.getTradingMode();
                    int stage = signal.getBuyStage() != null ? signal.getBuyStage() : 2;
                    
                    timeCutService.recordBuy(signal.getTicker(), signal.getStrategyName());
                    strategyStateStore.saveBuyStage(signal.getTicker(), stage, tradingMode);
                    
                    if ("volatility-breakout".equals(signal.getStrategyName())) {
                        volatilityBreakoutStrategy.markBought(signal.getTicker());
                    }
                }
                yield r;
            }
            case SELL -> {
                OrderResult r = orderClient.sell(orderRequest);
                if (r.success()) {
                    String tradingMode = adminConfigStore.getTradingMode();
                    timeCutService.clearBuy(signal.getTicker());
                    strategyStateStore.removeBuyStage(signal.getTicker(), tradingMode);
                    strategyStateStore.removeEntryTag(signal.getTicker(), tradingMode);
                }
                yield r;
            }
            default -> OrderResult.fail("알 수 없는 액션");
        };

        slackNotifier.sendTradeResult(signal, result.success(), result.errorMsg());
        return result.success();
    }

    private List<String> getActiveStrategyNames(MarketState state) {
        List<String> enabled = strategyConfig.getEnabledStrategies();
        if (!strategyConfig.getMarketFilter().isEnabled()) return enabled;
        return switch (state) {
            case BULLISH  -> enabled.stream()
                    .filter(s -> List.of("volatility-breakout", "golden-cross").contains(s))
                    .toList();
            case SIDEWAYS, BEARISH -> enabled.stream()
                    .filter(s -> List.of("rsi-bollinger").contains(s))
                    .toList();
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
                all.add(liveCandle);

                if (state == MarketState.SIDEWAYS || state == MarketState.BEARISH) {
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
        double proximity = Math.max(0, (5.0 - Math.max(gapPct, 0)) / 5.0) * 50; // 5% 이내 = 0~50점
        double score = volScore + proximity;

        String detail = String.format("목표가=%.0f, Range=%.0f, 거래량비율=%.1fx", targetPrice, range, volRatio);
        return new SignalGapDto(0, ticker, stockName, "volatility-breakout",
                currentPrice, targetPrice, gapPct, -1, 0, score, detail);
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
