package com.axiom.strategy.scheduler;

import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.notification.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 전략 실행 결과를 하루 단위로 집계하여 15:25에 슬랙으로 요약 발송.
 * StrategyScheduler(5분마다)가 실행할 때마다 record()를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryCollector {

    private final SlackNotifier slackNotifier;

    private final AtomicInteger runs              = new AtomicInteger();
    private final AtomicInteger evaluated         = new AtomicInteger();
    private final AtomicInteger bought            = new AtomicInteger();
    private final AtomicInteger sold              = new AtomicInteger();
    private final AtomicInteger errors            = new AtomicInteger();
    private final AtomicInteger skippedMarketWarn = new AtomicInteger();
    private final AtomicInteger skippedMaxPos     = new AtomicInteger();

    private final List<StrategyEngine.TradeRecord> boughtList  = Collections.synchronizedList(new ArrayList<>());
    private final List<StrategyEngine.TradeRecord> soldList    = Collections.synchronizedList(new ArrayList<>());
    private final List<StrategyEngine.SkipRecord>  skippedList = Collections.synchronizedList(new ArrayList<>());

    public void record(StrategyEngine.RunResult result) {
        if (result.paused()) return;
        runs.incrementAndGet();
        evaluated.addAndGet(result.evaluated());
        bought.addAndGet(result.bought());
        sold.addAndGet(result.sold());
        errors.addAndGet(result.errors());
        skippedMarketWarn.addAndGet(result.skippedMarketWarn());
        skippedMaxPos.addAndGet(result.skippedMaxPositions());
        boughtList.addAll(result.boughtList());
        soldList.addAll(result.soldList());
        skippedList.addAll(result.skippedList());
    }

    @Scheduled(cron = "0 25 15 * * MON-FRI", zone = "Asia/Seoul")
    public void sendAndReset() {
        log.info("[DailySummary] 일일 요약 발송 — runs: {}, bought: {}, sold: {}, errors: {}",
                runs.get(), bought.get(), sold.get(), errors.get());

        slackNotifier.sendDailyStrategySummary(
                runs.get(), evaluated.get(), bought.get(), sold.get(),
                errors.get(), skippedMarketWarn.get(), skippedMaxPos.get(),
                List.copyOf(boughtList), List.copyOf(soldList), List.copyOf(skippedList)
        );

        runs.set(0);
        evaluated.set(0);
        bought.set(0);
        sold.set(0);
        errors.set(0);
        skippedMarketWarn.set(0);
        skippedMaxPos.set(0);
        boughtList.clear();
        soldList.clear();
        skippedList.clear();
    }
}
