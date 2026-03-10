package com.axiom.strategy.config;

import com.axiom.strategy.util.TradingCalendar;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KrxHolidayInitializer {

    @Value("${trading.holidays:}")
    private List<String> holidayStrings;

    @Value("${trading.late-open-days:}")
    private List<String> lateOpenDayStrings;

    @PostConstruct
    public void init() {
        Set<LocalDate> holidays = parse(holidayStrings);
        TradingCalendar.setHolidays(holidays);
        log.info("[Holiday] 공휴일 {}일 로드 완료", holidays.size());

        Set<LocalDate> lateOpenDays = parse(lateOpenDayStrings);
        TradingCalendar.setLateOpenDays(lateOpenDays);
        if (!lateOpenDays.isEmpty())
            log.info("[Holiday] 늦은 개장일 {}일 로드 완료 (수능 등)", lateOpenDays.size());
    }

    private Set<LocalDate> parse(List<String> list) {
        return list.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }
}
