package com.axiom.market.scheduler;

import com.axiom.market.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * 워치리스트 자동 리밸런싱 cron.
 * - 일일 (거래일 08:30 KST): 거래정지/관리종목/투자위험 자동 제외 + 정상화 자동 복귀.
 * - 주간 (월요일 08:30 KST): 시총/거래대금 임계 평가 (히스테리시스).
 * dry-run=true 일 때는 결정만 로그/Slack에 출력.
 *
 * Phase 3 (KRX 분기 리밸런싱)은 별도 cron으로 분리됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchlistRebalanceScheduler {

    private final WatchlistService watchlistService;

    /**
     * 평일 08:30 일일 점검. 월요일은 weeklyMarketCapReview()가 같은 시간에 실행되므로
     * dailyReview()는 화~금 또는 월요일 외에 돌리는 게 깔끔하지만,
     * 둘 다 idempotent하고 빠르므로 월요일에도 같이 돌려도 무방.
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void runDailyReview() {
        log.info("[Watchlist-Cron] dailyReview 시작 - {}", LocalDate.now());
        try {
            Map<String, Object> result = watchlistService.dailyReview();
            log.info("[Watchlist-Cron] dailyReview 완료 - {}", result);
        } catch (Exception e) {
            log.error("[Watchlist-Cron] dailyReview 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 월요일 08:30 주간 시총/거래대금 점검.
     */
    @Scheduled(cron = "0 30 8 * * MON", zone = "Asia/Seoul")
    public void runWeeklyMarketCapReview() {
        if (LocalDate.now().getDayOfWeek() != DayOfWeek.MONDAY) return;
        log.info("[Watchlist-Cron] weeklyMarketCapReview 시작 - {}", LocalDate.now());
        try {
            Map<String, Object> result = watchlistService.weeklyMarketCapReview();
            log.info("[Watchlist-Cron] weeklyMarketCapReview 완료 - {}", result);
        } catch (Exception e) {
            log.error("[Watchlist-Cron] weeklyMarketCapReview 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 6/1 · 12/1 07:00 KST KRX 분기 정기변경 자동 반영 (Phase 3).
     * 5월 말/11월 말 발표 다음날(영업일) 시점 — KIS index endpoint 운영 검증 후 활성화.
     * dry-run=true 일 때는 결정만 로그/Slack에 출력.
     */
    @Scheduled(cron = "0 0 7 1 6,12 *", zone = "Asia/Seoul")
    public void runQuarterlyRebalance() {
        log.info("[Watchlist-Cron] quarterlyRebalance 시작 - {}", LocalDate.now());
        try {
            Map<String, Object> result = watchlistService.quarterlyRebalance();
            log.info("[Watchlist-Cron] quarterlyRebalance 완료 - {}", result);
        } catch (Exception e) {
            log.error("[Watchlist-Cron] quarterlyRebalance 실패: {}", e.getMessage(), e);
        }
    }
}
