package com.axiom.strategy.scheduler;

import com.axiom.strategy.service.MarketStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 장 시작 전(08:30) 코스피 지수를 조회하여 시장 상태(상승장/횡보장)를 판별한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketStateScheduler {

    private final MarketStateService marketStateService;

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshMarketState() {
        log.info("[MarketStateScheduler] 시장 상태 판별 시작");
        marketStateService.refresh();
    }
}
