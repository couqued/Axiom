package com.axiom.strategy.strategy;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.util.TradingCalendar;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 변동성 돌파 전략 (상승장 단기 매매).
 *
 * <p>목표가 = 오늘 시가 + (전일 고가 - 전일 저가) × K
 * <p>현재가 ≥ 목표가 → 매수 (당일 내 중복 매수 방지)
 * <p>매도는 ForceExitScheduler가 15:20에 강제 청산.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolatilityBreakoutStrategy implements TradingStrategy {

    private static final double K            = 0.4;
    private static final double BREAKOUT_CAP = 3.0;
    private static final double VOL_CAP      = 2.0;

    private final StrategyStateStore stateStore;
    private final AdminConfigStore adminConfigStore;

    /** 당일 매수 종목 추적: "{mode}:{ticker}" → 매수일. ForceExitScheduler에서도 참조. */
    private final Map<String, LocalDate> todayBought = new ConcurrentHashMap<>();

    /** 15:20 강제 청산이 실행된 날짜: mode → 날짜 */
    private final Map<String, LocalDate> forceExitDates = new ConcurrentHashMap<>();

    @PostConstruct
    public void initFromDb() {
        for (String mode : List.of("paper", "real")) {
            try {
                Map<String, LocalDate> fromDb = stateStore.loadAllTodayBought(mode);
                fromDb.forEach((ticker, date) -> todayBought.put(mode + ":" + ticker, date));
                log.info("[VolBreakout] DB에서 todayBought 복구 — mode={}, {}개", mode, fromDb.size());
            } catch (Exception e) {
                log.warn("[VolBreakout] DB todayBought 복구 실패 (mode={}): {}", mode, e.getMessage());
            }
        }
    }

    @Override
    public String getName() {
        return "volatility-breakout";
    }

    @Override
    public int minimumCandles() {
        return 3;
    }

    @Override
    public SignalDto evaluate(String ticker, List<CandleDto> candles) {
        CandleDto today     = candles.get(candles.size() - 1);
        CandleDto yesterday = candles.get(candles.size() - 2);

        BigDecimal range       = yesterday.getHighPrice().subtract(yesterday.getLowPrice());
        BigDecimal targetPrice = today.getOpenPrice().add(range.multiply(BigDecimal.valueOf(K)));
        BigDecimal currentPrice = today.getClosePrice();

        log.debug("[VolBreakout] {} | 목표가: {} | 현재가: {} | Range: {}",
                ticker, targetPrice, currentPrice, range);

        if (range.compareTo(BigDecimal.ZERO) == 0 || targetPrice.compareTo(BigDecimal.ZERO) == 0) {
            return hold(ticker, currentPrice, "전일 변동폭 없음 — 전략 적용 불가");
        }

        String mode = adminConfigStore.getTradingMode();
        LocalDate todayDate = today.getTradeDate();

        // 당일 15:20 강제 청산 실행 후 추가 매수 차단
        LocalDate fExitDate = forceExitDates.get(mode);
        if (fExitDate != null && fExitDate.equals(todayDate)) {
            return hold(ticker, currentPrice, "당일 15:20 강제청산 완료 — 추가 매수 차단");
        }

        // 당일 이미 매수한 종목 스킵
        String mapKey = mode + ":" + ticker;
        if (todayBought.containsKey(mapKey) && todayBought.get(mapKey).equals(todayDate)) {
            return hold(ticker, currentPrice, "오늘 이미 매수됨");
        }

        if (currentPrice.compareTo(targetPrice) >= 0) {
            double breakoutPct = currentPrice.subtract(targetPrice)
                    .divide(targetPrice, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
            double avgVol  = avgVolume(candles);
            double volRatio = avgVol > 0 ? today.getVolume() / avgVol : 1.0;
            double score = Math.min(breakoutPct / BREAKOUT_CAP, 1.0) * 50
                         + Math.min(volRatio    / VOL_CAP,        1.0) * 50;

            return SignalDto.builder()
                    .action(SignalDto.Action.BUY)
                    .ticker(ticker)
                    .price(currentPrice)
                    .strategyName(getName())
                    .score(score)
                    .reason(String.format("변동성 돌파 — 현재(%.0f) ≥ 목표(%.0f) = 시가(%.0f) + Range(%.0f)×%.1f [score=%.1f]",
                            currentPrice.doubleValue(), targetPrice.doubleValue(),
                            today.getOpenPrice().doubleValue(), range.doubleValue(), K, score))
                    .signalAt(LocalDateTime.now())
                    .build();
        }

        return hold(ticker, currentPrice,
                String.format("목표가 미달성 — 현재(%.0f) < 목표(%.0f)", currentPrice.doubleValue(), targetPrice.doubleValue()));
    }

    /**
     * 오늘 변동성 돌파로 매수한 종목 목록 반환 (해당 모드만).
     * ForceExitScheduler에서 15:20 강제 청산 시 사용.
     */
    public Map<String, LocalDate> getTodayBought(String mode) {
        String prefix = mode + ":";
        Map<String, LocalDate> result = new HashMap<>();
        todayBought.forEach((key, date) -> {
            if (key.startsWith(prefix)) {
                result.put(key.substring(prefix.length()), date);
            }
        });
        return result;
    }

    /**
     * 주문 체결 확정 후 호출. StrategyEngine에서 호출.
     */
    public void markBought(String ticker, String mode) {
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        todayBought.put(mode + ":" + ticker, today);
        stateStore.saveTodayBought(ticker, today, mode);
    }

    public void removeTodayBought(String ticker, String mode) {
        todayBought.remove(mode + ":" + ticker);
        stateStore.removeTodayBought(ticker, mode);
    }

    /**
     * 15:20 강제 청산 실행 완료 후 호출.
     * 이후 당일 신규 BUY 신호를 차단한다.
     */
    public void markForceExited(String mode) {
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        forceExitDates.put(mode, today);
        log.info("[VolBreakout] 당일 강제청산 완료 — 추가 매수 차단 설정 ({}, mode={})", today, mode);
    }

    private double avgVolume(List<CandleDto> candles) {
        int end   = candles.size() - 2;
        int start = Math.max(0, end - 19);
        long sum  = 0;
        int  cnt  = 0;
        for (int i = start; i <= end; i++) {
            sum += candles.get(i).getVolume();
            cnt++;
        }
        return cnt == 0 ? 1.0 : (double) sum / cnt;
    }

    private SignalDto hold(String ticker, BigDecimal price, String reason) {
        return SignalDto.builder()
                .action(SignalDto.Action.HOLD)
                .ticker(ticker)
                .price(price)
                .strategyName(getName())
                .reason(reason)
                .signalAt(LocalDateTime.now())
                .build();
    }
}
