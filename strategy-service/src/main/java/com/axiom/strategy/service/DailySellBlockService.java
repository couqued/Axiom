package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.util.TradingCalendar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DailySellBlockService {

    private final AdminConfigStore adminConfigStore;

    /** "{mode}:{ticker}" → 매도일 */
    private final Map<String, LocalDate> soldToday = new ConcurrentHashMap<>();

    /** 매도 완료 후 당일 재매수 차단 등록 */
    public void markSoldToday(String ticker) {
        String key = adminConfigStore.getTradingMode() + ":" + ticker;
        soldToday.put(key, LocalDate.now(TradingCalendar.KST));
    }

    /** 해당 종목이 오늘 매도된 적 있는지 확인 */
    public boolean isSoldToday(String ticker) {
        String key = adminConfigStore.getTradingMode() + ":" + ticker;
        LocalDate date = soldToday.get(key);
        return date != null && date.equals(LocalDate.now(TradingCalendar.KST));
    }
}
