package com.axiom.strategy.service;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.notification.SlackNotifier;
import com.axiom.strategy.util.TradingCalendar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ML 전략 DEFER 카운터. 연속 DEFER {@code maxCount} 회 초과 시 당일 블랙리스트.
 *
 * <p>메모리 전용 — 재시작 시 자연 복구 (당일 이슈면 곧 다시 DEFER 될 것).
 * 자정(날짜 변경) 기준으로 자동 초기화.
 */
@Slf4j
@Component
public class MlDeferTracker {

    private final int maxCount;
    private final SlackNotifier slackNotifier;
    private final MarketClient marketClient;
    private final Map<String, DeferState> deferred = new ConcurrentHashMap<>();

    public MlDeferTracker(
            @Value("${ml-prediction.entry-timing.max-defer-count:3}") int maxCount,
            SlackNotifier slackNotifier,
            MarketClient marketClient) {
        this.maxCount = maxCount;
        this.slackNotifier = slackNotifier;
        this.marketClient = marketClient;
    }

    private record DeferState(int count, LocalDate date) {}

    public boolean isBlacklisted(String ticker) {
        DeferState s = deferred.get(ticker);
        if (s == null) return false;
        if (!s.date().equals(LocalDate.now(TradingCalendar.KST))) {
            deferred.remove(ticker);
            return false;
        }
        return s.count() >= maxCount;
    }

    /**
     * DEFER 발생 시 호출. {@code countForBlacklist=false}면 로그만 남기고 카운트하지 않는다.
     * multiplier < 0.90 (가격 과열)으로 인한 DEFER는 카운트에서 제외해 눌림목 복귀 후 재진입을 허용한다.
     */
    public void recordDefer(String ticker, boolean countForBlacklist) {
        if (!countForBlacklist) {
            log.debug("[MlDefer] 진입품질 DEFER (카운트 제외) — ticker: {}", ticker);
            return;
        }
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        deferred.compute(ticker, (k, prev) -> {
            if (prev == null || !prev.date().equals(today)) return new DeferState(1, today);
            int nextCount = prev.count() + 1;
            if (nextCount == maxCount) {
                log.warn("[MlDefer] 블랙리스트 등록 — ticker: {} ({}회 연속 DEFER)", ticker, nextCount);
                String stockName = marketClient.getTickerNames().getOrDefault(ticker, ticker);
                slackNotifier.sendMlBlacklist(ticker, stockName, nextCount);
            }
            return new DeferState(nextCount, today);
        });
    }

    /** DEFER 발생 시 호출 (+1). 연속 카운트가 상한 도달 시 Slack 블랙리스트 알림. */
    public void recordDefer(String ticker) {
        recordDefer(ticker, true);
    }

    /** 정상 체결 or 조건 해소 시 초기화. */
    public void clear(String ticker) {
        deferred.remove(ticker);
    }

    public int getCount(String ticker) {
        DeferState s = deferred.get(ticker);
        if (s == null || !s.date().equals(LocalDate.now(TradingCalendar.KST))) return 0;
        return s.count();
    }
}
