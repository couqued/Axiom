package com.axiom.strategy.scheduler;

import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.OrderSummaryDto;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 변동성 돌파 전략 포지션 강제 청산 스케줄러.
 *
 * <p>매일 15:20 (장 마감 10분 전) 변동성 돌파로 매수한 종목 중
 * 아직 보유 중인 것들을 시장가로 청산한다 (오버나이트 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForceExitScheduler {

    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final PortfolioClient portfolioClient;
    private final OrderClient orderClient;
    private final SlackNotifier slackNotifier;

    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Seoul")
    public void forceExit() {
        Map<String, LocalDate> todayBought = volatilityBreakoutStrategy.getTodayBought();
        LocalDate today = LocalDate.now();

        // 오늘 변동성 돌파로 매수한 종목
        Set<String> boughtToday = todayBought.entrySet().stream()
                .filter(e -> e.getValue().equals(today))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        if (boughtToday.isEmpty()) {
            log.info("[ForceExit] 변동성 돌파 보유 종목 없음 — 마감청산 불필요");
            return;
        }

        log.info("[ForceExit] 마감청산 시작 — 대상 종목: {}", boughtToday);

        // portfolio-service에서 실제 보유 종목 확인 후 매도
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        for (PortfolioItemDto position : positions) {
            if (!boughtToday.contains(position.getTicker())) continue;

            OrderRequest sellOrder = OrderRequest.builder()
                    .ticker(position.getTicker())
                    .quantity(position.getQuantity())
                    .price(position.getAvgPrice()) // 시장가 매도 (평균단가 참조)
                    .closeReason("FORCE_EXIT")
                    .build();

            OrderResult result = orderClient.sell(sellOrder);
            log.info("[ForceExit] 마감청산 — ticker: {}, qty: {}, success: {}",
                    position.getTicker(), position.getQuantity(), result.success());

            slackNotifier.sendForceExit(
                    position.getTicker(), position.getStockName(),
                    position.getQuantity(), position.getAvgPrice(), result.success());
        }

        // 오늘 매수 기록 정리
        boughtToday.forEach(todayBought::remove);
    }

    /**
     * 전 거래일 변동성 돌파 미청산 포지션 장 시작 직후 청산 (09:05).
     *
     * <p>15:20 강제청산이 서비스 재시작 등으로 누락된 경우를 대비한 보완 스케줄.
     * <ol>
     *   <li>todayBought에서 오늘 날짜가 아닌 항목 추출 (오버나이트 후보)</li>
     *   <li>order-service 이력에서 volatility-breakout BUY FILLED 확인 (전략 검증)</li>
     *   <li>portfolio-service에서 실제 보유 중인지 확인</li>
     *   <li>검증 통과 종목 매도 → FORCE_EXIT</li>
     * </ol>
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    public void exitOvernightPositions() {
        Map<String, LocalDate> todayBought = volatilityBreakoutStrategy.getTodayBought();
        LocalDate today = LocalDate.now();

        // ① 전 거래일 매수 후 미청산 후보 추출
        Set<String> overnightTickers = todayBought.entrySet().stream()
                .filter(e -> !e.getValue().equals(today))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        if (overnightTickers.isEmpty()) {
            log.info("[ForceExit] 오버나이트 미청산 종목 없음 — 스킵");
            return;
        }

        log.info("[ForceExit] 오버나이트 미청산 후보: {}", overnightTickers);

        // ② order-service 이력으로 volatility-breakout 전략 검증
        List<OrderSummaryDto> orders = orderClient.getFilledOrders();
        Set<String> confirmedTickers = overnightTickers.stream()
                .filter(ticker -> orders.stream().anyMatch(o ->
                        ticker.equals(o.getTicker())
                        && "BUY".equals(o.getOrderType())
                        && "FILLED".equals(o.getStatus())
                        && "volatility-breakout".equals(o.getStrategyName())
                        && o.getCreatedAt() != null
                        && o.getCreatedAt().toLocalDate().equals(todayBought.get(ticker))))
                .collect(Collectors.toSet());

        if (confirmedTickers.isEmpty()) {
            log.warn("[ForceExit] 오버나이트 후보 중 volatility-breakout 이력 미확인 — 매도 중단: {}", overnightTickers);
            return;
        }

        log.info("[ForceExit] 오버나이트 청산 대상 확정: {}", confirmedTickers);

        // ③ 실제 보유 중인 종목만 매도
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        for (PortfolioItemDto position : positions) {
            if (!confirmedTickers.contains(position.getTicker())) continue;

            OrderRequest sellOrder = OrderRequest.builder()
                    .ticker(position.getTicker())
                    .quantity(position.getQuantity())
                    .price(position.getAvgPrice())
                    .closeReason("FORCE_EXIT")
                    .build();

            OrderResult result = orderClient.sell(sellOrder);
            log.info("[ForceExit] 오버나이트 청산 — ticker: {}, qty: {}, success: {}",
                    position.getTicker(), position.getQuantity(), result.success());

            slackNotifier.sendForceExit(
                    position.getTicker(), position.getStockName(),
                    position.getQuantity(), position.getAvgPrice(), result.success());
        }

        // ④ 처리된 항목 todayBought에서 제거
        confirmedTickers.forEach(todayBought::remove);
    }
}
