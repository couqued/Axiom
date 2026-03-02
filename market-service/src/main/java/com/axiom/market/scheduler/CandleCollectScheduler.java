package com.axiom.market.scheduler;

import com.axiom.market.config.CandleConfig;
import com.axiom.market.config.KisApiConfig;
import com.axiom.market.service.CandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandleCollectScheduler {

    private final CandleService candleService;
    private final CandleConfig candleConfig;
    private final KisApiConfig kisApiConfig;

    /**
     * 매일 15:40 KST 장 종료 후 당일 일봉 수집.
     * mock 모드일 때는 실행하지 않음 (DB에 저장할 실제 데이터 없음).
     */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectDailyCandles() {
        if (kisApiConfig.isMock()) {
            log.debug("[Scheduler] mock 모드 - 일봉 수집 스킵");
            return;
        }

        LocalDate today = LocalDate.now();
        log.info("[Scheduler] 일봉 수집 시작 - date: {}, tickers: {}",
                today, candleConfig.getWatchTickers());

        for (String ticker : candleConfig.getWatchTickers()) {
            try {
                candleService.collectCandle(ticker, today);
            } catch (Exception e) {
                log.error("[Scheduler] 일봉 수집 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            }
        }

        log.info("[Scheduler] 일봉 수집 완료 - date: {}", today);
    }
}
