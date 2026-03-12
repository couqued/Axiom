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
import com.axiom.strategy.dto.SkippedSignalRequest;
import com.axiom.strategy.dto.StockPriceDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.service.MarketState;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.service.TimeCutService;
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
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final List<TradingStrategy> strategies;

    private volatile List<String> watchTickers = List.of();
    /** 모드별 마지막 BUY 랭킹: "paper"|"real" → List<EvalRankEntry> */
    private final Map<String, List<EvalRankEntry>> lastBuyRankingByMode = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastEvalAt;
    /** 모드별 당일 실행 이력: "paper"|"real" → List<RunRecord> */
    private final Map<String, List<RunRecord>> todayRunsByMode = new ConcurrentHashMap<>();
    private volatile LocalDate lastRunDate = null;

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

    public List<RunRecord> getTodayRuns() {
        String mode = adminConfigStore.getTradingMode();
        List<RunRecord> runs = todayRunsByMode.get(mode);
        return runs != null ? List.copyOf(runs) : List.of();
    }

    public int getWatchTickerCount() {
        return watchTickers.size();
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
        int[] boughtThisRun      = {0};
        int[] soldThisRun        = {0};
        int[] errorsThisRun      = {0};
        int[] warnSkipsThisRun   = {0};
        int[] maxPosSkipsThisRun = {0};
        List<TradeRecord> boughtList  = new ArrayList<>();
        List<TradeRecord> soldList    = new ArrayList<>();
        List<SkipRecord>  skippedList = new ArrayList<>();

        log.info("[Engine][{}] 전략 실행 시작 — 시장: {}, tickers: {}개, 전략: {}, 보유: {}개/{}개",
                tradingMode, marketState, tickers.size(), activeStrategyNames, positions.size(), maxPositions);

        // ── Phase 1: 전체 종목 평가 ──────────────────────────────────────────
        List<BuyCandidate> buyQueue = new ArrayList<>();
        for (String ticker : tickers) {
            try {
                Optional<BuyCandidate> candidate = collectBuyCandidate(
                        ticker, candleDays, activeStrategyNames, positions,
                        soldThisRun, errorsThisRun, warnSkipsThisRun,
                        soldList, skippedList, marketState);
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

        // ── Phase 1.5: 09:20 이전 BUY 전체 스킵 / 09:20 이후 지수 하락 체크 ──
        LocalTime nowKst = LocalTime.now(TradingCalendar.KST);
        boolean before920 = nowKst.isBefore(LocalTime.of(9, 20));

        if (before920) {
            log.info("[Engine] 09:20 이전 — BUY {} 후보 스킵", buyQueue.size());
            buyQueue.clear();
        } else {
            if (!marketStateService.isIndexDropCheckedToday()) {
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

            if (marketStateService.isIndexDropBlockedToday()
                    && adminConfigStore.getIndexDropBlockPct() > 0) {
                log.warn("[Engine] 당일 매수 차단(지수하락) — BUY {} 후보 스킵", buyQueue.size());
                buyQueue.clear();
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
            int effectivePositions = positions.size() + boughtThisRun[0];
            if (effectivePositions >= maxPositions) {
                log.info("[Engine] 최대 보유 종목 수 도달 ({}/{}) — BUY 스킵 ticker: {}",
                        effectivePositions, maxPositions, candidate.signal().getTicker());
                recordSkipped(candidate.signal(), marketState, "MAX_POSITIONS");
                maxPosSkipsThisRun[0]++;
                skippedList.add(new SkipRecord(
                        candidate.signal().getTicker(), candidate.signal().getStockName(), "최대보유"));
                candidateResultMap.put(candidate.signal().getTicker(), "한도초과");
                continue;
            }

            boolean traded = handleSignal(candidate.signal(), positions, marketState.name());
            if (traded) {
                boughtThisRun[0]++;
                boughtList.add(new TradeRecord(
                        candidate.signal().getTicker(),
                        candidate.signal().getStockName(),
                        candidate.signal().getPrice()));
                candidateResultMap.put(candidate.signal().getTicker(), "매수");
            } else {
                candidateResultMap.put(candidate.signal().getTicker(), "예산부족");
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
            MarketState marketState) {
        StockPriceDto priceData = marketClient.getCurrentPrice(ticker);
        if (priceData == null || priceData.getCurrentPrice() == null) {
            log.warn("[Engine] 현재가 조회 실패 — ticker: {}", ticker);
            return Optional.empty();
        }

        List<CandleDto> historical = marketClient.getCandles(ticker, candleDays);
        if (historical.isEmpty()) {
            log.warn("[Engine] 캔들 데이터 없음 — ticker: {}", ticker);
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
                continue;
            }

            try {
                SignalDto signal = strategy.evaluate(ticker, allCandles)
                        .toBuilder().stockName(priceData.getStockName()).build();
                log.info("[Engine] 신호 — ticker: {}, strategy: {}, action: {}, reason: {}",
                        ticker, strategy.getName(), signal.getAction(), signal.getReason());

                if (!signal.isTradeSignal()) continue;

                if (signal.getAction() == SignalDto.Action.SELL) {
                    boolean traded = handleSignal(signal, positions, marketState.name());
                    if (traded) {
                        soldThisRun[0]++;
                        soldList.add(new TradeRecord(signal.getTicker(), signal.getStockName(), signal.getPrice()));
                    }
                } else { // BUY
                    if (!priceData.isSafe()) {
                        log.warn("[Engine] 시장경보 종목 — BUY 스킵 ticker: {}, warnCode: {}",
                                ticker, priceData.getMarketWarnCode());
                        recordSkipped(signal, marketState, "MARKET_WARN");
                        warnSkipsThisRun[0]++;
                        skippedList.add(new SkipRecord(signal.getTicker(), signal.getStockName(), "시장경보"));
                        continue;
                    }
                    boolean alreadyHolding = positions.stream()
                            .anyMatch(p -> p.getTicker().equals(ticker));
                    if (alreadyHolding) {
                        log.info("[Engine] 이미 보유 중 — BUY 스킵 ticker: {}", ticker);
                        continue;
                    }
                    if (bestBuy == null || signal.getScore() > bestBuy.score()) {
                        bestBuy = new BuyCandidate(signal, allCandles, priceData);
                    }
                }
            } catch (Exception e) {
                log.error("[Engine] 전략 실행 오류 — ticker: {}, strategy: {}, error: {}",
                        ticker, strategy.getName(), e.getMessage());
                slackNotifier.sendError(String.format(
                        "ticker=%s, strategy=%s, %s", ticker, strategy.getName(), e.getMessage()));
                errorsThisRun[0]++;
            }
        }

        timeCutService.checkAndCut(ticker, priceData.getCurrentPrice(), positions);

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
                    timeCutService.recordBuy(signal.getTicker(), signal.getStrategyName());
                    if ("volatility-breakout".equals(signal.getStrategyName())) {
                        volatilityBreakoutStrategy.markBought(signal.getTicker());
                    }
                }
                yield r;
            }
            case SELL -> {
                OrderResult r = orderClient.sell(orderRequest);
                if (r.success()) timeCutService.clearBuy(signal.getTicker());
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
            case SIDEWAYS -> enabled.stream()
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
}
