package com.axiom.strategy.scheduler;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.OrderSummaryDto;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.notification.SlackNotifier.OvernightExitItem;
import com.axiom.strategy.service.DailySellBlockService;
import com.axiom.strategy.strategy.VolatilityBreakoutStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.axiom.strategy.util.TradingCalendar;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
    private final DailySellBlockService dailySellBlockService;
    private final AdminConfigStore adminConfigStore;

    @Scheduled(cron = "0 19 15 * * MON-FRI", zone = "Asia/Seoul")
    public void forceExit() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) {
            log.info("[ForceExit] 공휴일 — 스킵");
            return;
        }
        if (adminConfigStore.isSellPaused()) {
            log.info("[ForceExit] 매도 중지 상태 — 마감청산 스킵");
            return;
        }

        Map<String, LocalDate> todayBought = volatilityBreakoutStrategy.getTodayBought();
        LocalDate today = LocalDate.now(TradingCalendar.KST);

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
        Set<String> heldTickers = positions.stream()
                .map(PortfolioItemDto::getTicker)
                .collect(Collectors.toSet());

        Set<String> soldOk = new java.util.HashSet<>();
        for (PortfolioItemDto position : positions) {
            if (!boughtToday.contains(position.getTicker())) continue;

            OrderRequest sellOrder = OrderRequest.builder()
                    .ticker(position.getTicker())
                    .quantity(position.getQuantity())
                    .price(null)  // 시장가 매도 (price=null → OrderService → KIS ORD_DVSN=01)
                    .closeReason("FORCE_EXIT")
                    .build();

            OrderResult result = orderClient.sell(sellOrder);
            log.info("[ForceExit] 마감청산 — ticker: {}, qty: {}, success: {}",
                    position.getTicker(), position.getQuantity(), result.success());

            slackNotifier.sendForceExit(
                    position.getTicker(), position.getStockName(),
                    position.getQuantity(), position.getAvgPrice(), result.success());

            if (result.success()) {
                soldOk.add(position.getTicker());
                dailySellBlockService.markSoldToday(position.getTicker());
            } else {
                log.warn("[ForceExit] 매도 실패 — {} todayBought 유지 (9:05 오버나이트 청산 위임)",
                        position.getTicker());
            }
        }

        // 매도 성공 종목만 todayBought에서 제거 (실패 종목은 유지 → 오버나이트 청산 재시도)
        // 포트폴리오에도 없는 종목(이미 타 경로로 청산됨)도 함께 정리
        boughtToday.stream()
                .filter(ticker -> soldOk.contains(ticker) || !heldTickers.contains(ticker))
                .forEach(ticker -> volatilityBreakoutStrategy.removeTodayBought(ticker));

        // 당일 강제청산 실행 완료 — 이후 신규 매수 차단
        volatilityBreakoutStrategy.markForceExited();
    }

    /**
     * 전 거래일 변동성 돌파 미청산 포지션 장 시작 직후 청산 (09:05).
     *
     * <p>15:20 강제청산이 서비스 재시작 등으로 누락된 경우를 대비한 보완 스케줄.
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
    public void exitOvernightPositions() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) {
            log.info("[ForceExit] 공휴일 — 스킵");
            return;
        }
        if (adminConfigStore.isSellPaused()) {
            log.info("[ForceExit] 매도 중지 상태 — 오버나이트 청산 스킵");
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        if (TradingCalendar.isLateOpenDay(LocalDate.now(TradingCalendar.KST)) && now.getHour() < 10) {
            log.info("[ForceExit] 수능일 늦은 개장 — 10시 이전 스킵");
            return;
        }

        Map<String, LocalDate> todayBought = volatilityBreakoutStrategy.getTodayBought();
        LocalDate today = LocalDate.now(TradingCalendar.KST);

        // ① 전 거래일 매수 후 미청산 후보 추출
        Set<String> overnightTickers = todayBought.entrySet().stream()
                .filter(e -> !e.getValue().equals(today))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        if (overnightTickers.isEmpty()) {
            log.info("[ForceExit] 오버나이트 미청산 종목 없음 — 스킵");
            slackNotifier.sendOvernightExitResult(false, 0, false, List.of());
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
            slackNotifier.sendOvernightExitResult(false, 0, false, List.of());
            return;
        }

        log.info("[ForceExit] 오버나이트 청산 대상 확정: {}", confirmedTickers);

        // ③ 실제 보유 중인 종목만 매도
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        Set<String> heldSet = positions.stream()
                .map(PortfolioItemDto::getTicker)
                .collect(Collectors.toSet());
        List<OvernightExitItem> exitItems = new ArrayList<>();
        Set<String> soldOk = new java.util.HashSet<>();
        boolean anyFailed = false;
        for (PortfolioItemDto position : positions) {
            if (!confirmedTickers.contains(position.getTicker())) continue;

            OrderRequest sellOrder = OrderRequest.builder()
                    .ticker(position.getTicker())
                    .quantity(position.getQuantity())
                    .price(null)  // 시장가 매도 (price=null → OrderService → KIS ORD_DVSN=01)
                    .closeReason("FORCE_EXIT")
                    .build();

            OrderResult result = orderClient.sell(sellOrder);
            log.info("[ForceExit] 오버나이트 청산 — ticker: {}, qty: {}, success: {}",
                    position.getTicker(), position.getQuantity(), result.success());

            exitItems.add(new OvernightExitItem(
                    position.getTicker(), position.getStockName(),
                    position.getAvgPrice(), position.getAvgPrice(), result.success()));
            if (result.success()) {
                soldOk.add(position.getTicker());
            } else {
                anyFailed = true;
            }
        }

        slackNotifier.sendOvernightExitResult(true, exitItems.size(), anyFailed, exitItems);

        // ④ 매도 성공 종목 + 포트폴리오 미보유(이미 청산) 종목만 todayBought에서 제거
        confirmedTickers.stream()
                .filter(t -> soldOk.contains(t) || !heldSet.contains(t))
                .forEach(t -> volatilityBreakoutStrategy.removeTodayBought(t));
    }
}
