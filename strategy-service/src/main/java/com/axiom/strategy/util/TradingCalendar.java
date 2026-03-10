package com.axiom.strategy.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 거래일 계산 유틸리티.
 * 주말 및 공휴일을 제외한 영업일 기준으로 날짜를 계산한다.
 * 공휴일 목록은 KrxHolidayInitializer가 application.yml 에서 주입한다.
 */
public final class TradingCalendar {

    /** 한국 표준시 (UTC+9) — Docker 컨테이너 기본 UTC와 다르므로 명시적으로 사용 */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Set<LocalDate> HOLIDAYS = new HashSet<>();
    private static final Set<LocalDate> LATE_OPEN_DAYS = new HashSet<>();

    private TradingCalendar() {}

    public static void setHolidays(Collection<LocalDate> holidays) {
        HOLIDAYS.clear();
        HOLIDAYS.addAll(holidays);
    }

    public static void setLateOpenDays(Collection<LocalDate> days) {
        LATE_OPEN_DAYS.clear();
        LATE_OPEN_DAYS.addAll(days);
    }

    /**
     * from 날짜부터 to 날짜 사이의 거래일 수를 반환한다.
     * from과 to 모두 포함하지 않음 (순수 경과 거래일).
     */
    public static int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) return 0;

        int count = 0;
        LocalDate date = from.plusDays(1);
        while (!date.isAfter(to)) {
            if (isTradingDay(date)) count++;
            date = date.plusDays(1);
        }
        return count;
    }

    /**
     * 주말·공휴일이 아닌 거래일 여부 확인.
     */
    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY
                && dow != DayOfWeek.SUNDAY
                && !HOLIDAYS.contains(date);
    }

    /** 수능 등 10:00 늦은 개장일 여부 */
    public static boolean isLateOpenDay(LocalDate date) {
        return LATE_OPEN_DAYS.contains(date);
    }
}
