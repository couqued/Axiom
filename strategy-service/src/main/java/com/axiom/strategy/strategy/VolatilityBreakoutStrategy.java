package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.service.EntryQualityEvaluator;
import com.axiom.strategy.util.TradingCalendar;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private static final double K                  = 0.4;
    private static final double BREAKOUT_CAP       = 3.0;
    private static final double VOL_CAP            = 2.0;
    /** 목표가 대비 진입 허용 슬리피지 — 0.5%. 목표가를 이미 한참 초과해 추격 매수가 되는 케이스 차단. */
    private static final double ENTRY_SLIPPAGE_CAP = 0.005;

    private final StrategyStateStore stateStore;
    private final EntryQualityEvaluator entryQualityEvaluator;

    /** 당일 매수 종목 추적: ticker → 매수일. ForceExitScheduler에서도 참조. */
    private final Map<String, LocalDate> todayBought = new ConcurrentHashMap<>();

    /** 15:20 강제 청산이 실행된 날짜 */
    private volatile LocalDate forceExitDate = null;

    @PostConstruct
    public void initFromDb() {
        try {
            Map<String, LocalDate> fromDb = stateStore.loadAllTodayBought();
            todayBought.putAll(fromDb);
            log.info("[VolBreakout] DB에서 todayBought 복구 — {}개", fromDb.size());
        } catch (Exception e) {
            log.warn("[VolBreakout] DB todayBought 복구 실패: {}", e.getMessage());
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

        LocalDate todayDate = today.getTradeDate();

        // 당일 15:20 강제 청산 실행 후 추가 매수 차단
        if (forceExitDate != null && forceExitDate.equals(todayDate)) {
            return hold(ticker, currentPrice, "당일 15:20 강제청산 완료 — 추가 매수 차단");
        }

        // 당일 이미 매수한 종목 스킵
        if (todayBought.containsKey(ticker) && todayBought.get(ticker).equals(todayDate)) {
            return hold(ticker, currentPrice, "오늘 이미 매수됨");
        }

        if (currentPrice.compareTo(targetPrice) >= 0) {
            // 갭업/FOMO 가드 (장초반 과열 구간 차단)
            double quality = entryQualityEvaluator.evaluate(ticker, currentPrice, candles);
            if (quality < 0) {
                return hold(ticker, currentPrice, "EntryQuality 가드 — 갭업/FOMO 과열 구간 진입 차단");
            }

            // 목표가 대비 슬리피지 캡 (목표가를 이미 0.5% 이상 초과 → 추격 매수)
            double breakoutPct = currentPrice.subtract(targetPrice)
                    .divide(targetPrice, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
            if (breakoutPct > ENTRY_SLIPPAGE_CAP * 100) {
                return hold(ticker, currentPrice, String.format(
                        "추격 매수 차단 — 목표가(%.0f) 대비 +%.2f%% (허용 +%.1f%%)",
                        targetPrice.doubleValue(), breakoutPct, ENTRY_SLIPPAGE_CAP * 100));
            }

            double avgVol  = avgVolume(candles);
            double volRatio = avgVol > 0 ? today.getVolume() / avgVol : 1.0;
            // score 역전: 목표가에 가까울수록 ↑ (꼭지 추격이 만점이던 기존 공식 폐지)
            double proximity = Math.max(1.0 - breakoutPct / BREAKOUT_CAP, 0.0) * 50;
            double volume   = Math.min(volRatio / VOL_CAP, 1.0) * 50;
            double score    = proximity + volume;

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
     * 오늘 변동성 돌파로 매수한 종목 목록 반환.
     * ForceExitScheduler에서 15:20 강제 청산 시 사용.
     */
    public Map<String, LocalDate> getTodayBought() {
        return java.util.Collections.unmodifiableMap(todayBought);
    }

    /**
     * 주문 체결 확정 후 호출. StrategyEngine에서 호출.
     */
    public void markBought(String ticker) {
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        todayBought.put(ticker, today);
        stateStore.saveTodayBought(ticker, today);
    }

    public void removeTodayBought(String ticker) {
        todayBought.remove(ticker);
        stateStore.removeTodayBought(ticker);
    }

    /**
     * 15:20 강제 청산 실행 완료 후 호출.
     * 이후 당일 신규 BUY 신호를 차단한다.
     */
    public void markForceExited() {
        forceExitDate = LocalDate.now(TradingCalendar.KST);
        log.info("[VolBreakout] 당일 강제청산 완료 — 추가 매수 차단 설정 ({})", forceExitDate);
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
