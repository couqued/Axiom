package com.axiom.strategy.scheduler;

import com.axiom.strategy.engine.StrategyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.axiom.strategy.util.TradingCalendar;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyScheduler {

    private final StrategyEngine strategyEngine;
    private final DailySummaryCollector dailySummaryCollector;

    /**
     * 평일 09:05 ~ 15:20 사이 5분마다 실행.
     * (09:00 장 시작 직후 5분 대기, 15:25 이후는 실행 안 함)
     */
    @Scheduled(cron = "0 5/5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void runStrategies() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) {
            log.info("[Scheduler] 공휴일 — 스킵");
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        if (TradingCalendar.isLateOpenDay(LocalDate.now(TradingCalendar.KST)) && now.getHour() < 10) {
            log.info("[Scheduler] 수능일 늦은 개장 — 10시 이전 스킵");
            return;
        }

        // 15:21 이후 스킵 (장 마감 근처는 실행하지 않음)
        if (now.getHour() == 15 && now.getMinute() > 20) {
            log.debug("[Scheduler] 15:20 이후 — 스킵");
            return;
        }

        log.info("[Scheduler] 전략 실행 트리거 - {}", now.toLocalTime());
        StrategyEngine.RunResult result = strategyEngine.run();
        dailySummaryCollector.record(result);
    }
}
