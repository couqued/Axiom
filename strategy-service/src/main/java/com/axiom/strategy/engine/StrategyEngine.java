package com.axiom.strategy.engine;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.dto.StockPriceDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.service.MarketState;
import com.axiom.strategy.service.MarketStateService;
import com.axiom.strategy.service.TimeCutService;
import com.axiom.strategy.service.TrailingStopService;
import com.axiom.strategy.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {

    private final StrategyConfig strategyConfig;
    private final MarketClient marketClient;
    private final OrderClient orderClient;
    private final PortfolioClient portfolioClient;
    private final SlackNotifier slackNotifier;
    private final MarketStateService marketStateService;
    private final TrailingStopService trailingStopService;
    private final TimeCutService timeCutService;
    private final List<TradingStrategy> strategies; // Spring이 TradingStrategy 구현체를 자동 주입

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
    public void run() {
        MarketState marketState = marketStateService.getCurrentState();
        List<String> tickers   = strategyConfig.getWatchTickers();
        List<String> activeStrategyNames = getActiveStrategyNames(marketState);
        int candleDays = strategyConfig.getCandleDays();

        log.info("[Engine] 전략 실행 시작 — 시장: {}, tickers: {}, 전략: {}",
                marketState, tickers, activeStrategyNames);

        // 보유 포지션 한 번 조회 (트레일링 스탑 + 타임 컷 공용)
        List<PortfolioItemDto> positions = portfolioClient.getPositions();

        for (String ticker : tickers) {
            try {
                runForTicker(ticker, candleDays, activeStrategyNames, positions);
            } catch (Exception e) {
                log.error("[Engine] 종목 처리 오류 — ticker: {}, error: {}", ticker, e.getMessage());
            }
        }

        log.info("[Engine] 전략 실행 완료");
    }

    private void runForTicker(String ticker, int candleDays,
                               List<String> activeStrategyNames,
                               List<PortfolioItemDto> positions) {
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
                SignalDto signal = strategy.evaluate(ticker, allCandles);
                log.info("[Engine] 신호 — ticker: {}, strategy: {}, action: {}, reason: {}",
                        ticker, strategy.getName(), signal.getAction(), signal.getReason());

                if (signal.isTradeSignal()) {
                    handleSignal(signal);
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

    private void handleSignal(SignalDto signal) {
        slackNotifier.sendSignal(signal);

        OrderRequest orderRequest = OrderRequest.builder()
                .ticker(signal.getTicker())
                .quantity(strategyConfig.getOrderQuantity())
                .price(signal.getPrice())
                .build();

        boolean success = switch (signal.getAction()) {
            case BUY  -> {
                boolean result = orderClient.buy(orderRequest);
                if (result) {
                    // 타임 컷 매수 기록
                    timeCutService.recordBuy(signal.getTicker(), signal.getStrategyName());
                }
                yield result;
            }
            case SELL -> {
                boolean result = orderClient.sell(orderRequest);
                if (result) {
                    // 매도 시 타임 컷 기록 제거
                    timeCutService.clearBuy(signal.getTicker());
                }
                yield result;
            }
            default -> false;
        };

        slackNotifier.sendOrderFilled(signal, success);
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
}
