package com.axiom.strategy.service;

import com.axiom.strategy.util.TradingCalendar;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DailySellBlockService {

    /** ticker → 매도일 */
    private final Map<String, LocalDate> soldToday = new ConcurrentHashMap<>();

    /** 매도 완료 후 당일 재매수 차단 등록 */
    public void markSoldToday(String ticker) {
        soldToday.put(ticker, LocalDate.now(TradingCalendar.KST));
    }

    /** 해당 종목이 오늘 매도된 적 있는지 확인 */
    public boolean isSoldToday(String ticker) {
        LocalDate date = soldToday.get(ticker);
        return date != null && date.equals(LocalDate.now(TradingCalendar.KST));
    }
}
