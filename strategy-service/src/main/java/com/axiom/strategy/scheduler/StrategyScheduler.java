package com.axiom.strategy.scheduler;

import com.axiom.strategy.engine.StrategyEngine;
import com.axiom.strategy.notification.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.axiom.strategy.util.TradingCalendar;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyScheduler {

    private final StrategyEngine strategyEngine;
    private final DailySummaryCollector dailySummaryCollector;
    private final SlackNotifier slackNotifier;

    /**
     * 평일 09:05 ~ 15:20 사이 5분마다 실행.
     * (09:00 장 시작 직후 5분 대기, 15:25 이후는 실행 안 함)
     */
    @Scheduled(cron = "0 5/5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void runStrategies() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) {
            log.info("[Scheduler] 공휴일 — 스킵");
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        if (TradingCalendar.isLateOpenDay(LocalDate.now(TradingCalendar.KST)) && now.getHour() < 10) {
            log.info("[Scheduler] 수능일 늦은 개장 — 10시 이전 스킵");
            return;
        }

        // 15:21 이후 스킵 (장 마감 근처는 실행하지 않음)
        if (now.getHour() == 15 && now.getMinute() > 20) {
            log.debug("[Scheduler] 15:20 이후 — 스킵");
            return;
        }

        log.info("[Scheduler] 전략 실행 트리거 - {}", now.toLocalTime());
        StrategyEngine.RunResult result = strategyEngine.run();
        dailySummaryCollector.record(result);
    }

    /** 매 시각 정각 10:00~15:00 평일 — 직전 1시간(H-1:xx) 요약 Slack 발송 */
    @Scheduled(cron = "0 0 10-15 * * MON-FRI", zone = "Asia/Seoul")
    public void sendHourlyReport() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) return;
        int prevHour = LocalTime.now(TradingCalendar.KST).plusMinutes(1).getHour() - 1;
        sendHourlySlack(prevHour);
    }

    /** 15:22 — 마지막 실행(15:20) 완료 후 15시대 요약 Slack 발송 */
    @Scheduled(cron = "0 22 15 * * MON-FRI", zone = "Asia/Seoul")
    public void sendFinalHourlyReport() {
        if (!TradingCalendar.isTradingDay(LocalDate.now(TradingCalendar.KST))) return;
        sendHourlySlack(15);
    }

    private static String reasonLabel(String reason) {
        return switch (reason) {
            case "이미보유"      -> "이미 보유 중";
            case "2차신호"      -> "2차 신호 / 1차 포지션 없음";
            case "캔들없음"     -> "캔들 데이터 없음";
            case "캔들부족"     -> "캔들 부족";
            case "시장경보"     -> "시장 경보";
            case "최대보유"     -> "최대 보유 수 초과";
            case "그룹한도(볼린저)" -> "그룹 한도(볼린저)";
            case "그룹한도(추세)"   -> "그룹 한도(추세)";
            default             -> reason;
        };
    }

    private void sendHourlySlack(int hour) {
        log.info("[Scheduler] 시간별 요약 Slack 시도 — {}시", hour);
        List<StrategyEngine.RunRecord> hrRuns = strategyEngine.getTodayRuns().stream()
                .filter(r -> r.runAt().getHour() == hour)
                .collect(Collectors.toList());
        if (hrRuns.isEmpty()) {
            log.warn("[Scheduler] 시간별 요약 스킵 — {}시 실행 이력 없음 (pod 재시작?)", hour);
            return;
        }

        int evaluated = hrRuns.stream().mapToInt(StrategyEngine.RunRecord::evaluated).sum();
        int bought    = hrRuns.stream().mapToInt(StrategyEngine.RunRecord::bought).sum();
        int sold      = hrRuns.stream().mapToInt(StrategyEngine.RunRecord::sold).sum();
        int errors    = hrRuns.stream().mapToInt(StrategyEngine.RunRecord::errors).sum();
        List<String> boughtTickers = hrRuns.stream()
                .flatMap(r -> r.boughtList().stream())
                .map(StrategyEngine.TradeRecord::stockName)
                .distinct().collect(Collectors.toList());
        // reason → 종목명 목록으로 그룹화
        Map<String, List<String>> skipByReason = new LinkedHashMap<>();
        hrRuns.stream()
                .flatMap(r -> r.skippedList().stream())
                .forEach(s -> skipByReason
                        .computeIfAbsent(s.reason(), k -> new ArrayList<>())
                        .add(s.stockName() != null && !s.stockName().isBlank() ? s.stockName() : s.ticker()));

        // 중복 제거 및 라인 포맷
        List<String> skipLines = skipByReason.entrySet().stream()
                .map(e -> reasonLabel(e.getKey()) + " : " +
                          e.getValue().stream().distinct().collect(Collectors.joining(", ")))
                .collect(Collectors.toList());

        long noSignalCount = hrRuns.stream()
                .filter(r -> r.bought() == 0 && r.skippedList().isEmpty()).count();

        log.info("[Scheduler] 시간별 요약 Slack 발송 — {}시, {}회 실행", hour, hrRuns.size());
        slackNotifier.sendHourlySummary(hour, hrRuns.size(), evaluated,
                bought, sold, errors, boughtTickers, skipLines, noSignalCount);
    }
}
