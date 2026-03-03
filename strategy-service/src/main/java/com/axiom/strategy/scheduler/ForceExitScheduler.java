package com.axiom.strategy.scheduler;

import com.axiom.strategy.client.OrderClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.OrderRequest;
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
            log.info("[ForceExit] 변동성 돌파 보유 종목 없음 — 강제 청산 불필요");
            return;
        }

        log.info("[ForceExit] 강제 청산 시작 — 대상 종목: {}", boughtToday);

        // portfolio-service에서 실제 보유 종목 확인 후 매도
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        for (PortfolioItemDto position : positions) {
            if (!boughtToday.contains(position.getTicker())) continue;

            OrderRequest sellOrder = OrderRequest.builder()
                    .ticker(position.getTicker())
                    .quantity(position.getQuantity())
                    .price(position.getAvgPrice()) // 시장가 매도 (평균단가 참조)
                    .build();

            boolean success = orderClient.sell(sellOrder);
            log.info("[ForceExit] 강제 청산 — ticker: {}, qty: {}, success: {}",
                    position.getTicker(), position.getQuantity(), success);

            slackNotifier.sendError(String.format(
                    "🔔 [강제 청산] %s %d주 — 변동성 돌파 오버나이트 방지 (%s)",
                    position.getTicker(), position.getQuantity(), success ? "성공" : "실패"));
        }

        // 오늘 매수 기록 정리
        boughtToday.forEach(todayBought::remove);
    }
}
