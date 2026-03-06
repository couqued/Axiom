package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.axiom.strategy.admin.TrailingStopStatusDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 트레일링 스탑 서비스.
 *
 * <p>보유 종목의 고점을 추적하여, 현재가가 고점 대비 설정 비율 이하로 하락하면 매도 신호를 발생시킨다.
 * <p>기본값: 고점 대비 7% 하락 시 청산 (application.yml에서 조정 가능).
 *
 * <p>peakPrices는 메모리에 저장되므로 서비스 재시작 시 초기화됨.
 * 재시작 직후 첫 번째 check() 호출 시 현재가를 고점으로 초기화.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrailingStopService {

    private final StrategyConfig strategyConfig;
    private final AdminConfigStore adminConfigStore;
    private final OrderClient orderClient;
    private final PortfolioClient portfolioClient;
    private final SlackNotifier slackNotifier;

    /** ticker → 고점 가격 (메모리, 재시작 시 portfolio avgPrice로 초기화) */
    private final Map<String, BigDecimal> peakPrices = new ConcurrentHashMap<>();

    /**
     * 서비스 재시작 후 보유 포지션의 avgPrice를 고점 초기값으로 복구.
     * 다음 전략 실행 시 실제 현재가로 자동 갱신됨.
     */
    @PostConstruct
    public void initFromPortfolio() {
        try {
            List<PortfolioItemDto> positions = portfolioClient.getPositions();
            for (PortfolioItemDto p : positions) {
                if (p.getAvgPrice() != null) {
                    peakPrices.putIfAbsent(p.getTicker(), p.getAvgPrice());
                }
            }
            if (!positions.isEmpty()) {
                log.info("[TrailingStop] 재시작 복구 — {}개 종목 avgPrice로 초기화", positions.size());
            }
        } catch (Exception e) {
            log.warn("[TrailingStop] 재시작 복구 실패 (다음 전략 실행 시 자동 복구): {}", e.getMessage());
        }
    }

    /**
     * 종목의 현재가를 확인하여 트레일링 스탑 조건을 체크한다.
     * StrategyEngine에서 매 5분마다 호출.
     *
     * @param ticker       종목 코드
     * @param currentPrice 현재가
     * @param positions    현재 보유 포지션 목록 (portfolio-service에서 조회)
     */
    public void check(String ticker, BigDecimal currentPrice, List<PortfolioItemDto> positions) {
        StrategyConfig.TrailingStopConfig config = strategyConfig.getTrailingStop();
        if (!config.isEnabled()) return;
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        // 보유 중인 종목만 체크
        boolean isHolding = positions.stream().anyMatch(p -> p.getTicker().equals(ticker));
        if (!isHolding) {
            peakPrices.remove(ticker); // 보유 해제 시 고점 기록 삭제
            return;
        }

        // 고점 갱신 (처음이거나 현재가가 더 높으면 갱신)
        BigDecimal peak = peakPrices.merge(ticker, currentPrice, (old, cur) ->
                cur.compareTo(old) > 0 ? cur : old);

        // 트레일링 스탑 기준가 = 고점 × (1 - stopPercent / 100)
        double stopPercent = adminConfigStore.getTrailingStopPct();
        BigDecimal stopPrice = peak.multiply(BigDecimal.valueOf(1.0 - stopPercent / 100.0))
                .setScale(0, RoundingMode.HALF_UP);

        log.debug("[TrailingStop] {} | 현재가: {} | 고점: {} | 기준가: {}",
                ticker, currentPrice, peak, stopPrice);

        if (currentPrice.compareTo(stopPrice) <= 0) {
            log.warn("[TrailingStop] 트레일링 스탑 발동 — {} | 현재가: {} | 고점: {} | 하락률: {}%",
                    ticker, currentPrice, peak, String.format("%.1f", stopPercent));
            executeSell(ticker, currentPrice, positions, stopPercent);
            peakPrices.remove(ticker);
        }
    }

    public Map<String, TrailingStopStatusDto> getStatus() {
        double stopPct = adminConfigStore.getTrailingStopPct();
        Map<String, TrailingStopStatusDto> result = new HashMap<>();
        peakPrices.forEach((ticker, peak) -> {
            BigDecimal stopPrice = peak.multiply(BigDecimal.valueOf(1.0 - stopPct / 100.0))
                    .setScale(0, RoundingMode.HALF_UP);
            result.put(ticker, new TrailingStopStatusDto(peak, stopPrice));
        });
        return result;
    }

    private void executeSell(String ticker, BigDecimal currentPrice,
                             List<PortfolioItemDto> positions, double stopPercent) {
        positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst()
                .ifPresent(position -> {
                    OrderRequest sellOrder = OrderRequest.builder()
                            .ticker(ticker)
                            .quantity(position.getQuantity())
                            .price(currentPrice)
                            .closeReason("TRAILING_STOP")
                            .build();

                    OrderResult result = orderClient.sell(sellOrder);
                    log.info("[TrailingStop] 매도 주문 — ticker: {}, qty: {}, success: {}",
                            ticker, position.getQuantity(), result.success());

                    slackNotifier.sendTrailingStop(
                            ticker, position.getStockName(), currentPrice, stopPercent, result.success());
                });
    }
}
