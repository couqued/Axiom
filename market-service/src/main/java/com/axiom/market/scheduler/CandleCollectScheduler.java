package com.axiom.market.scheduler;

import com.axiom.market.service.CandleService;
import com.axiom.market.service.StockScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CandleCollectScheduler {

    private final CandleService candleService;
    private final StockScreenerService stockScreenerService;

    /**
     * 매일 15:40 KST 장 종료 후 당일 일봉 수집.
     */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void collectDailyCandles() {
        LocalDate today = LocalDate.now();
        List<String> tickers = stockScreenerService.getScreenedTickers();
        log.info("[Scheduler] 일봉 수집 시작 - date: {}, tickers: {}개", today, tickers.size());

        for (String ticker : tickers) {
            try {
                candleService.collectCandle(ticker, today);
            } catch (Exception e) {
                log.error("[Scheduler] 일봉 수집 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            }
        }

        log.info("[Scheduler] 일봉 수집 완료 - date: {}", today);
    }
}
