package com.axiom.strategy.scheduler;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.client.PortfolioClient;
import com.axiom.strategy.dto.PortfolioItemDto;
import com.axiom.strategy.dto.StockPriceDto;
import com.axiom.strategy.service.TrailingStopService;
import com.axiom.strategy.util.TradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrailingStopScheduler {

    private final AdminConfigStore adminConfigStore;
    private final PortfolioClient portfolioClient;
    private final MarketClient marketClient;
    private final TrailingStopService trailingStopService;

    /**
     * 평일 09:00 ~ 15:20 사이 1분마다 보유 종목 트레일링 스탑 체크.
     * StrategyEngine 5분 주기 실행 사이 구간을 보완하여 빠른 대응을 제공한다.
     */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void checkTrailingStop() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) {
            log.info("[TrailingStopScheduler] 공휴일 — 스킵");
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        if (TradingCalendar.isLateOpenDay(LocalDate.now(TradingCalendar.KST)) && now.getHour() < 10) {
            log.info("[TrailingStopScheduler] 수능일 늦은 개장 — 10시 이전 스킵");
            return;
        }

        if (now.getHour() == 15 && now.getMinute() > 20) return;

        if (adminConfigStore.isPaused()) return;

        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        if (positions.isEmpty()) return;

        log.debug("[TrailingStopScheduler] 체크 시작 — 보유 {}개", positions.size());

        for (PortfolioItemDto position : positions) {
            try {
                StockPriceDto price = marketClient.getCurrentPrice(position.getTicker());
                if (price == null || price.getCurrentPrice() == null) continue;

                trailingStopService.check(position.getTicker(), price.getCurrentPrice(), positions);
                Thread.sleep(200); // KIS API Rate Limit
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[TrailingStopScheduler] 오류 — ticker: {}, error: {}",
                        position.getTicker(), e.getMessage());
            }
        }
    }
}
