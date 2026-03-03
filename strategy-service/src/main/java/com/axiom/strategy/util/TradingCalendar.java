package com.axiom.strategy.util;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 거래일 계산 유틸리티.
 * 주말을 제외한 영업일 기준으로 날짜를 계산한다.
 * (공휴일은 미반영 — 추후 한국 공휴일 API 연동 가능)
 */
public final class TradingCalendar {

    private TradingCalendar() {}

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
     * 주말이 아닌 거래일 여부 확인.
     */
    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
}
