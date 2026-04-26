package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 익절(Profit-Take) 서비스.
 *
 * <p>보유 종목의 현재가가 매수 평균가 대비 설정 비율 이상 상승하면 매도 신호를 발생시킨다.
 * <p>profitTakePct = 0 이면 비활성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitTakeService {

    private final AdminConfigStore adminConfigStore;
    private final OrderClient orderClient;
    private final PortfolioClient portfolioClient;
    private final SlackNotifier slackNotifier;
    private final StrategyStateStore stateStore;
    private final DailySellBlockService dailySellBlockService;
    private final TimeCutService timeCutService;
    private final TrailingStopService trailingStopService;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final BollingerReserveService bollingerReserveService;

    public void check(String ticker, BigDecimal currentPrice, List<PortfolioItemDto> positions) {
        double pct = adminConfigStore.getProfitTakePct();
        if (pct <= 0) {
            log.info("[ProfitTake] {} | profitTakePct={} 비활성 — 스킵", ticker, pct);
            return;
        }
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst()
                .ifPresent(position -> {
                    if ("ml-prediction".equals(position.getEntryTag())) {
                        log.debug("[ProfitTake] {} | ML 포지션 — 익절 비율 적용 제외", ticker);
                        return;
                    }
                    BigDecimal avgPrice = position.getAvgPrice();
                    log.info("[ProfitTake] {} | 현재가: {} | avgPrice: {} | buyStage: {} | pct: {}%",
                            ticker, currentPrice, avgPrice, position.getBuyStage(), pct);
                    if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) <= 0) return;

                    // 1차 매수 상태이면 스킵 (TrailingStop과 동일한 보류 조건)
                    if (position.getBuyStage() != null && position.getBuyStage() == 1) {
                        log.debug("[ProfitTake] {} | 1차 매수 상태 — 익절 보류", ticker);
                        return;
                    }

                    BigDecimal targetPrice = avgPrice
                            .multiply(BigDecimal.valueOf(1.0 + pct / 100.0))
                            .setScale(0, RoundingMode.HALF_UP);

                    log.debug("[ProfitTake] {} | 현재가: {} | 목표가: {} (평균가: {} +{}%)",
                            ticker, currentPrice, targetPrice, avgPrice, pct);

                    if (currentPrice.compareTo(targetPrice) >= 0) {
                        log.info("[ProfitTake] 익절 발동 — {} | 현재가: {} | 목표가: {} (평균가: {} +{}%)",
                                ticker, currentPrice, targetPrice, avgPrice, pct);
                        executeSell(ticker, currentPrice, position, pct);
                    }
                });
    }

    private void executeSell(String ticker, BigDecimal currentPrice,
                             PortfolioItemDto position, double pct) {
        OrderRequest sellOrder = OrderRequest.builder()
                .ticker(ticker)
                .quantity(position.getQuantity())
                .price(currentPrice)
                .closeReason("PROFIT_TAKE")
                .build();
        OrderResult result = orderClient.sellWithRetry(sellOrder);
        log.info("[ProfitTake] 매도 주문 — ticker: {}, qty: {}, success: {}",
                ticker, position.getQuantity(), result.success());
        slackNotifier.sendProfitTake(ticker, position.getStockName(), currentPrice,
                position.getAvgPrice(), pct, result.success());

        if (result.success()) {
            dailySellBlockService.markSoldToday(ticker);
            timeCutService.clearBuy(ticker);
            trailingStopService.removePeak(ticker);
            stateStore.removeBuyStage(ticker);
            stateStore.removeEntryTag(ticker);
            volatilityBreakoutStrategy.removeTodayBought(ticker);
            bollingerReserveService.clear(ticker);
        }
    }
}
