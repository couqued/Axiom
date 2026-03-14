package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.admin.TrailingStopStatusDto;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.persistence.StrategyStateStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * <p>peakPrices는 메모리에 저장. 키 형식: "{tradingMode}:{ticker}" (e.g. "paper:005930")
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
    private final StrategyStateStore stateStore;

    /** "{tradingMode}:{ticker}" → 고점 가격 */
    private final Map<String, BigDecimal> peakPrices = new ConcurrentHashMap<>();

    @PostConstruct
    public void initFromPortfolio() {
        for (String mode : List.of("paper", "real")) {
            try {
                Map<String, BigDecimal> fromDb = stateStore.loadAllPeakPrices(mode);
                fromDb.forEach((ticker, price) -> peakPrices.put(key(mode, ticker), price));
                log.info("[TrailingStop] DB에서 peakPrices 복구 — mode={}, {}개", mode, fromDb.size());
            } catch (Exception e) {
                log.warn("[TrailingStop] DB peakPrices 복구 실패 (mode={}): {}", mode, e.getMessage());
            }
        }

        // DB에 없는 보유 종목은 avgPrice를 초기 고점으로 사용 (폴백)
        try {
            List<PortfolioItemDto> positions = portfolioClient.getPositions();
            String mode = adminConfigStore.getTradingMode();
            for (PortfolioItemDto p : positions) {
                if (p.getAvgPrice() != null) {
                    peakPrices.putIfAbsent(key(mode, p.getTicker()), p.getAvgPrice());
                }
            }
            if (!positions.isEmpty()) {
                log.info("[TrailingStop] avgPrice 폴백 초기화 완료 (DB 누락분 보완)");
            }
        } catch (Exception e) {
            log.error("[TrailingStop] avgPrice 폴백 실패 (K8s 기동 순서 문제 가능): {}", e.getMessage());
        }
    }

    public void check(String ticker, BigDecimal currentPrice, List<PortfolioItemDto> positions) {
        StrategyConfig.TrailingStopConfig config = strategyConfig.getTrailingStop();
        if (!config.isEnabled()) return;
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        String mode = adminConfigStore.getTradingMode();
        String mapKey = key(mode, ticker);

        Optional<PortfolioItemDto> positionOpt = positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst();

        if (positionOpt.isEmpty()) {
            peakPrices.remove(mapKey);
            stateStore.removePeakPrice(ticker, mode);
            return;
        }

        // ── V2: 분할 매수 단계 체크 ──
        // 1차 매수(Stage 1) 상태이면 트레일링 스탑 보류 (2차 매수를 기다림)
        PortfolioItemDto position = positionOpt.get();
        if (position.getBuyStage() != null && position.getBuyStage() == 1) {
            log.debug("[TrailingStop][{}] {} | 1차 매수 상태 — 트레일링 스탑 보류", mode, ticker);
            // 1차 매수 중에는 고점 갱신도 하지 않음 (평단가가 바뀔 수 있으므로)
            return;
        }

        BigDecimal prevPeak = peakPrices.get(mapKey);
        if (prevPeak == null) {
            BigDecimal avgPrice = position.getAvgPrice();
            if (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                peakPrices.put(mapKey, avgPrice);
            }
            prevPeak = peakPrices.get(mapKey);
        }

        BigDecimal peak = peakPrices.merge(mapKey, currentPrice, (old, cur) ->
                cur.compareTo(old) > 0 ? cur : old);
        if (prevPeak == null || peak.compareTo(prevPeak) != 0) {
            try {
                stateStore.savePeakPrice(ticker, peak, mode);
            } catch (Exception e) {
                log.error("[TrailingStop] DB peakPrice 저장 실패 — {}: {}", ticker, e.getMessage());
            }
        }

        double stopPercent = adminConfigStore.getTrailingStopPct();
        BigDecimal stopPrice = peak.multiply(BigDecimal.valueOf(1.0 - stopPercent / 100.0))
                .setScale(0, RoundingMode.HALF_UP);

        log.debug("[TrailingStop][{}] {} | 현재가: {} | 고점: {} | 기준가: {}",
                mode, ticker, currentPrice, peak, stopPrice);

        if (currentPrice.compareTo(stopPrice) <= 0) {
            log.warn("[TrailingStop][{}] 트레일링 스탑 발동 — {} | 현재가: {} | 고점: {} | 하락률: {}%",
                    mode, ticker, currentPrice, peak, String.format("%.1f", stopPercent));
            boolean sold = executeSell(ticker, currentPrice, positions, stopPercent);
            if (sold) {
                peakPrices.remove(mapKey);
                stateStore.removePeakPrice(ticker, mode);
            } else {
                log.warn("[TrailingStop] 매도 최종 실패 — {} peakPrices 유지, 다음 주기(1분 후)에 재시도", ticker);
            }
        }
    }

    public Map<String, TrailingStopStatusDto> getStatus() {
        String mode = adminConfigStore.getTradingMode();
        String prefix = mode + ":";
        double stopPct = adminConfigStore.getTrailingStopPct();
        Map<String, TrailingStopStatusDto> result = new HashMap<>();
        peakPrices.forEach((mapKey, peak) -> {
            if (!mapKey.startsWith(prefix)) return;
            String ticker = mapKey.substring(prefix.length());
            BigDecimal stopPrice = peak.multiply(BigDecimal.valueOf(1.0 - stopPct / 100.0))
                    .setScale(0, RoundingMode.HALF_UP);
            result.put(ticker, new TrailingStopStatusDto(peak, stopPrice));
        });
        return result;
    }

    private boolean executeSell(String ticker, BigDecimal currentPrice,
                                List<PortfolioItemDto> positions, double stopPercent) {
        return positions.stream()
                .filter(p -> p.getTicker().equals(ticker))
                .findFirst()
                .map(position -> {
                    OrderRequest sellOrder = OrderRequest.builder()
                            .ticker(ticker)
                            .quantity(position.getQuantity())
                            .price(currentPrice)
                            .closeReason("TRAILING_STOP")
                            .build();
                    OrderResult result = orderClient.sellWithRetry(sellOrder);
                    log.info("[TrailingStop] 매도 주문 — ticker: {}, qty: {}, success: {}",
                            ticker, position.getQuantity(), result.success());
                    slackNotifier.sendTrailingStop(
                            ticker, position.getStockName(), currentPrice, stopPercent, result.success());
                    return result.success();
                })
                .orElse(false);
    }

    private String key(String mode, String ticker) {
        return mode + ":" + ticker;
    }
}
