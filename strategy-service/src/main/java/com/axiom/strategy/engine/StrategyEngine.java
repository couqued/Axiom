package com.axiom.strategy.engine;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {

    private final StrategyConfig strategyConfig;
    private final MarketClient marketClient;
    private final OrderClient orderClient;
    private final SlackNotifier slackNotifier;
    private final List<TradingStrategy> strategies; // Spring이 TradingStrategy 구현체를 자동 주입

    /**
     * 모든 감시 종목에 대해 활성화된 전략을 실행한다.
     */
    public void run() {
        List<String> tickers   = strategyConfig.getWatchTickers();
        List<String> enabled   = strategyConfig.getEnabledStrategies();
        int          candleDays = strategyConfig.getCandleDays();

        log.info("[Engine] 전략 실행 시작 - tickers: {}, strategies: {}", tickers, enabled);

        for (String ticker : tickers) {
            List<CandleDto> candles = marketClient.getCandles(ticker, candleDays);
            if (candles.isEmpty()) {
                log.warn("[Engine] 캔들 데이터 없음 - ticker: {}", ticker);
                continue;
            }

            for (TradingStrategy strategy : strategies) {
                if (!enabled.contains(strategy.getName())) continue;
                if (candles.size() < strategy.minimumCandles()) {
                    log.warn("[Engine] 캔들 부족 - strategy: {}, need: {}, got: {}",
                            strategy.getName(), strategy.minimumCandles(), candles.size());
                    continue;
                }

                try {
                    SignalDto signal = strategy.evaluate(ticker, candles);
                    log.info("[Engine] 신호 - ticker: {}, strategy: {}, action: {}, reason: {}",
                            ticker, strategy.getName(), signal.getAction(), signal.getReason());

                    if (signal.isTradeSignal()) {
                        handleSignal(signal);
                    }
                } catch (Exception e) {
                    log.error("[Engine] 전략 실행 오류 - ticker: {}, strategy: {}, error: {}",
                            ticker, strategy.getName(), e.getMessage());
                    slackNotifier.sendError(
                            String.format("ticker=%s, strategy=%s, %s", ticker, strategy.getName(), e.getMessage()));
                }
            }
        }

        log.info("[Engine] 전략 실행 완료");
    }

    private void handleSignal(SignalDto signal) {
        slackNotifier.sendSignal(signal);

        OrderRequest orderRequest = OrderRequest.builder()
                .ticker(signal.getTicker())
                .quantity(strategyConfig.getOrderQuantity())
                .price(signal.getPrice())
                .build();

        boolean success = switch (signal.getAction()) {
            case BUY  -> orderClient.buy(orderRequest);
            case SELL -> orderClient.sell(orderRequest);
            default   -> false;
        };

        slackNotifier.sendOrderFilled(signal, success);
    }
}
