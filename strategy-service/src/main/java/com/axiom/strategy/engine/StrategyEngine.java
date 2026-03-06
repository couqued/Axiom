package com.axiom.strategy.engine;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.dto.SkippedSignalRequest;
import com.axiom.strategy.dto.StockPriceDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.service.MarketState;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.service.TimeCutService;
import com.axiom.strategy.service.TrailingStopService;
import com.axiom.strategy.strategy.TradingStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private final TrailingStopService trailingStopService;
    private final TimeCutService timeCutService;
    private final List<TradingStrategy> strategies; // Spring이 TradingStrategy 구현체를 자동 주입

    /** 감시 종목 목록. 08:30 MarketStateScheduler가 market-service에서 갱신. fallback: yml watch-tickers */
    private volatile List<String> watchTickers = List.of();

    @PostConstruct
    public void init() {
        watchTickers = strategyConfig.getWatchTickers();
        log.info("[Engine] 초기 감시 종목 로드 — yml fallback {}개", watchTickers.size());
    }

    /** MarketStateScheduler(08:30)에서 호출하여 감시 종목을 동적으로 교체한다. */
    public void updateWatchTickers(List<String> tickers) {
        watchTickers = tickers;
    }

    /**
     * 모든 감시 종목에 대해 활성화된 전략을 실행한다.
     *
     * <ol>
     *   <li>시장 상태(BULLISH/SIDEWAYS)에 따라 실행할 전략 목록 선택</li>
     *   <li>각 종목의 현재가 + 역사적 캔들 조회 → 오늘 라이브 캔들 생성</li>
     *   <li>전략 평가 → BUY/SELL 신호 발생 시 주문 실행</li>
     *   <li>트레일링 스탑 + 타임 컷 체크</li>
     * </ol>
     */
    public record RunResult(int evaluated, int bought, int sold, boolean paused) {}

    public RunResult run() {
        if (adminConfigStore.isPaused()) {
            log.info("[Engine] 매매 중단 상태 — 전략 실행 스킵");
            return new RunResult(0, 0, 0, true);
        }

        MarketState marketState = marketStateService.getCurrentState();
        List<String> tickers = watchTickers;
        List<String> activeStrategyNames = getActiveStrategyNames(marketState);
        int candleDays = strategyConfig.getCandleDays();
        int maxPositions = adminConfigStore.getMaxPositions();

        // 보유 포지션 한 번 조회 (BUY 가드 + 트레일링 스탑 + 타임 컷 공용)
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        int[] boughtThisRun = {0};
        int[] soldThisRun   = {0};

        log.info("[Engine] 전략 실행 시작 — 시장: {}, tickers: {}개, 전략: {}, 보유: {}개/{}개",
                marketState, tickers.size(), activeStrategyNames, positions.size(), maxPositions);

        for (String ticker : tickers) {
            try {
                runForTicker(ticker, candleDays, activeStrategyNames, positions, boughtThisRun, soldThisRun, maxPositions, marketState);
                Thread.sleep(200); // KIS API Rate Limit 대응 (초당 5회 이하)
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[Engine] 종목 처리 오류 — ticker: {}, error: {}", ticker, e.getMessage());
            }
        }

        log.info("[Engine] 전략 실행 완료 — 평가: {}개, 매수: {}건, 매도: {}건",
                tickers.size(), boughtThisRun[0], soldThisRun[0]);
        return new RunResult(tickers.size(), boughtThisRun[0], soldThisRun[0], false);
    }

    private void runForTicker(String ticker, int candleDays,
                               List<String> activeStrategyNames,
                               List<PortfolioItemDto> positions,
                               int[] boughtThisRun,
                               int[] soldThisRun,
                               int maxPositions,
                               MarketState marketState) {
        // 현재가 조회 (시가, 고가, 저가, 현재가 포함)
        StockPriceDto priceData = marketClient.getCurrentPrice(ticker);
        if (priceData == null || priceData.getCurrentPrice() == null) {
            log.warn("[Engine] 현재가 조회 실패 — ticker: {}", ticker);
            return;
        }

        // 역사적 캔들 조회
        List<CandleDto> historical = marketClient.getCandles(ticker, candleDays);
        if (historical.isEmpty()) {
            log.warn("[Engine] 캔들 데이터 없음 — ticker: {}", ticker);
            return;
        }

        // 오늘 라이브 캔들 생성 (현재가를 종가로, 시가/고가/저가는 당일 실시간값)
        CandleDto liveCandle = CandleDto.builder()
                .tradeDate(LocalDate.now())
                .openPrice(priceData.getOpenPrice())
                .highPrice(priceData.getHighPrice())
                .lowPrice(priceData.getLowPrice())
                .closePrice(priceData.getCurrentPrice())
                .volume(priceData.getVolume())
                .build();

        List<CandleDto> allCandles = new ArrayList<>(historical);
        allCandles.add(liveCandle); // 오늘 라이브 캔들을 마지막에 추가

        // 전략 실행
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

                if (signal.isTradeSignal()) {
                    if (signal.getAction() == SignalDto.Action.BUY) {
                        // ① 시장경보 종목 BUY 스킵
                        if (!priceData.isSafe()) {
                            log.warn("[Engine] 시장경보 종목 — BUY 스킵 ticker: {}, warnCode: {}",
                                    ticker, priceData.getMarketWarnCode());
                            recordSkipped(signal, marketState, "MARKET_WARN");
                            continue;
                        }
                        // ② 이미 보유 중이면 BUY 스킵
                        boolean alreadyHolding = positions.stream()
                                .anyMatch(p -> p.getTicker().equals(ticker));
                        if (alreadyHolding) {
                            log.info("[Engine] 이미 보유 중 — BUY 스킵 ticker: {}", ticker);
                            continue;
                        }
                        // ③ 최대 보유 종목 수 도달 시 BUY 스킵
                        int effectivePositions = positions.size() + boughtThisRun[0];
                        if (effectivePositions >= maxPositions) {
                            log.info("[Engine] 최대 보유 종목 수 도달 ({}/{}) — BUY 스킵 ticker: {}",
                                    effectivePositions, maxPositions, ticker);
                            recordSkipped(signal, marketState, "MAX_POSITIONS");
                            continue;
                        }
                    }

                    boolean traded = handleSignal(signal, positions, marketState.name());
                    if (traded && signal.getAction() == SignalDto.Action.BUY) {
                        boughtThisRun[0]++;
                    } else if (traded && signal.getAction() == SignalDto.Action.SELL) {
                        soldThisRun[0]++;
                    }
                }
            } catch (Exception e) {
                log.error("[Engine] 전략 실행 오류 — ticker: {}, strategy: {}, error: {}",
                        ticker, strategy.getName(), e.getMessage());
                slackNotifier.sendError(String.format(
                        "ticker=%s, strategy=%s, %s", ticker, strategy.getName(), e.getMessage()));
            }
        }

        // 트레일링 스탑 체크 (보유 종목의 고점 대비 7% 하락 시 청산)
        trailingStopService.check(ticker, priceData.getCurrentPrice(), positions);

        // 타임 컷 체크 (RSI+볼린저 전략 3거래일 미반등 시 청산)
        timeCutService.checkAndCut(ticker, priceData.getCurrentPrice(), positions);
    }

    /**
     * 매수/매도 신호를 처리한다.
     *
     * @return 주문 성공 여부 (BUY 성공 시 boughtThisRun 카운트 증가용)
     */
    private boolean handleSignal(SignalDto signal, List<PortfolioItemDto> positions, String marketStateName) {
        // ── 수량 결정 (먼저 검증 — 실행 불가 시 Slack 발송 없이 스킵) ──────
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
        } else { // SELL → portfolio에서 보유 수량 전량 조회
            Optional<PortfolioItemDto> position = positions.stream()
                    .filter(p -> p.getTicker().equals(signal.getTicker()))
                    .findFirst();
            if (position.isEmpty()) {
                log.info("[Engine] 보유 포지션 없음 — SELL 스킵 ticker: {}", signal.getTicker());
                return false;
            }
            quantity = position.get().getQuantity();
        }

        // ── 주문 실행 ──────────────────────────────────────────────────────
        OrderRequest orderRequest = OrderRequest.builder()
                .ticker(signal.getTicker())
                .stockName(signal.getStockName())
                .quantity(quantity)
                .price(signal.getPrice())
                .strategyName(signal.getStrategyName())
                .marketState(marketStateName)
                .closeReason("SIGNAL")
                .build();

        boolean success = switch (signal.getAction()) {
            case BUY -> {
                boolean result = orderClient.buy(orderRequest);
                if (result) timeCutService.recordBuy(signal.getTicker(), signal.getStrategyName());
                yield result;
            }
            case SELL -> {
                boolean result = orderClient.sell(orderRequest);
                if (result) timeCutService.clearBuy(signal.getTicker());
                yield result;
            }
            default -> false;
        };

        slackNotifier.sendTradeResult(signal, success);
        return success;
    }

    /**
     * 시장 상태에 따라 실행할 전략 이름 목록을 반환한다.
     *
     * <ul>
     *   <li>BULLISH: 변동성 돌파 + 골든크로스</li>
     *   <li>SIDEWAYS: RSI + 볼린저밴드</li>
     *   <li>필터 비활성화: 모든 enabled 전략</li>
     * </ul>
     */
    private List<String> getActiveStrategyNames(MarketState state) {
        List<String> enabled = strategyConfig.getEnabledStrategies();

        if (!strategyConfig.getMarketFilter().isEnabled()) {
            return enabled;
        }

        return switch (state) {
            case BULLISH  -> enabled.stream()
                    .filter(s -> List.of("volatility-breakout", "golden-cross").contains(s))
                    .toList();
            case SIDEWAYS -> enabled.stream()
                    .filter(s -> List.of("rsi-bollinger").contains(s))
                    .toList();
        };
    }

    /** 스킵된 BUY 신호를 order-service에 비동기 기록 (실패 시 경고 로그만) */
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
