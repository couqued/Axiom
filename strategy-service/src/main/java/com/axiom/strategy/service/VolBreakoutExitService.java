package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 변동성 돌파 전략 전용 매도 보강 서비스.
 *
 * <p>15:19 일괄 마감 청산만 의존하던 기존 매도 룰을 보완해 다음 세 가지 조기 청산 조건을 추가한다:
 * <ul>
 *   <li>TP — 진입가 대비 +X% 도달 시 시장가 청산</li>
 *   <li>SL — 진입가 대비 -Y% 도달 시 시장가 청산</li>
 *   <li>일중 트레일링 — 보유 중 고점 대비 -Z% 후퇴 시 청산</li>
 * </ul>
 *
 * <p>설정값은 AdminConfigStore에서 종목/전략 무관 글로벌로 관리. 각 값이 0 이하면 비활성.
 * <p>대상 종목은 entryTag == "volatility-breakout" 인 포지션으로 한정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolBreakoutExitService {

    private static final String STRATEGY = "volatility-breakout";

    private final AdminConfigStore adminConfigStore;
    private final OrderClient orderClient;
    private final SlackNotifier slackNotifier;
    private final StrategyStateStore stateStore;
    private final DailySellBlockService dailySellBlockService;
    private final TimeCutService timeCutService;
    private final TrailingStopService trailingStopService;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final BollingerReserveService bollingerReserveService;

    /** ticker → 진입 후 일중 고점 (트레일링 기준) */
    private final Map<String, BigDecimal> intradayPeak = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[VolBreakoutExit] 매도 보강 서비스 활성화");
    }

    public void check(String ticker, BigDecimal currentPrice, List<PortfolioItemDto> positions) {
        if (adminConfigStore.isSellPaused()) return;
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        Map<String, String> tags = stateStore.loadAllEntryTags();
        if (!STRATEGY.equals(tags.get(ticker))) {
            intradayPeak.remove(ticker);
            return;
        }

        PortfolioItemDto position = positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst()
                .orElse(null);
        if (position == null) {
            intradayPeak.remove(ticker);
            return;
        }
        BigDecimal avgPrice = position.getAvgPrice();
        if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        double tpPct       = adminConfigStore.getVolBreakoutTakeProfitPct();
        double slPct       = adminConfigStore.getVolBreakoutStopLossPct();
        double trailingPct = adminConfigStore.getVolBreakoutIntradayTrailingPct();

        // 일중 고점 추적 (트레일링용)
        BigDecimal peak = intradayPeak.merge(ticker, currentPrice, (old, cur) ->
                cur.compareTo(old) > 0 ? cur : old);

        // 1. TP
        if (tpPct > 0) {
            BigDecimal tpPrice = avgPrice.multiply(BigDecimal.valueOf(1.0 + tpPct / 100.0))
                    .setScale(0, RoundingMode.HALF_UP);
            if (currentPrice.compareTo(tpPrice) >= 0) {
                log.info("[VolBreakoutExit] TP 발동 — {} | 현재가: {} | 목표가: {} (avg {} +{}%)",
                        ticker, currentPrice, tpPrice, avgPrice, tpPct);
                executeSell(ticker, currentPrice, position, "VOL_TP",
                        String.format("익절 +%.1f%% 도달", tpPct));
                return;
            }
        }

        // 2. SL
        if (slPct > 0) {
            BigDecimal slPrice = avgPrice.multiply(BigDecimal.valueOf(1.0 - slPct / 100.0))
                    .setScale(0, RoundingMode.HALF_UP);
            if (currentPrice.compareTo(slPrice) <= 0) {
                log.info("[VolBreakoutExit] SL 발동 — {} | 현재가: {} | 손절가: {} (avg {} -{}%)",
                        ticker, currentPrice, slPrice, avgPrice, slPct);
                executeSell(ticker, currentPrice, position, "VOL_SL",
                        String.format("손절 -%.1f%% 도달", slPct));
                return;
            }
        }

        // 3. 일중 트레일링 — 진입가 위에 있을 때만 발동(미실현 손익 보호)
        if (trailingPct > 0 && peak.compareTo(avgPrice) > 0) {
            BigDecimal trailLine = peak.multiply(BigDecimal.valueOf(1.0 - trailingPct / 100.0))
                    .setScale(0, RoundingMode.HALF_UP);
            if (currentPrice.compareTo(trailLine) <= 0) {
                log.info("[VolBreakoutExit] 일중 트레일링 발동 — {} | 현재가: {} | 고점: {} | 기준선: {} (-{}%)",
                        ticker, currentPrice, peak, trailLine, trailingPct);
                executeSell(ticker, currentPrice, position, "VOL_TRAIL",
                        String.format("일중 트레일링 -%.1f%% (고점 %s)", trailingPct, peak.toPlainString()));
                return;
            }
        }
    }

    public void clearPeak(String ticker) {
        intradayPeak.remove(ticker);
    }

    private void executeSell(String ticker, BigDecimal currentPrice,
                             PortfolioItemDto position, String closeReason, String reasonText) {
        OrderRequest sellOrder = OrderRequest.builder()
                .ticker(ticker)
                .quantity(position.getQuantity())
                .price(currentPrice)
                .strategyName(STRATEGY)
                .closeReason(closeReason)
                .build();
        OrderResult result = orderClient.sellWithRetry(sellOrder);
        log.info("[VolBreakoutExit] 매도 주문 — ticker: {}, qty: {}, reason: {}, success: {}",
                ticker, position.getQuantity(), closeReason, result.success());

        slackNotifier.sendVolBreakoutExit(ticker, position.getStockName(),
                position.getAvgPrice(), currentPrice, reasonText, result.success());

        if (result.success()) {
            dailySellBlockService.markSoldToday(ticker);
            timeCutService.clearBuy(ticker);
            trailingStopService.removePeak(ticker);
            stateStore.removeBuyStage(ticker);
            stateStore.removeEntryTag(ticker);
            volatilityBreakoutStrategy.removeTodayBought(ticker);
            bollingerReserveService.clear(ticker);
            intradayPeak.remove(ticker);
        }
    }
}
