package com.axiom.order.util;

import com.axiom.order.config.KisApiConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class MarketHoursChecker {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final KisApiConfig kisApiConfig;

    /** mock 모드이면 항상 열림, 그 외에는 KST 평일 09:00~15:30 여부 반환 */
    public boolean isMarketOpen() {
        if (kisApiConfig.isMock()) return true;
        ZonedDateTime now = ZonedDateTime.now(KST);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);
    }

    /** 다음 장 개장 시각 문자열 (ISO-8601 KST) */
    public String nextMarketOpenAt() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        ZonedDateTime next = now.with(MARKET_OPEN);

        // 이미 오늘 장 시작 시각이 지났거나 주말이면 다음 평일로 이동
        if (!now.toLocalTime().isBefore(MARKET_OPEN)) {
            next = next.plusDays(1);
        }
        // 주말 건너뛰기
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY
                || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next.format(DT_FMT);
    }
}
