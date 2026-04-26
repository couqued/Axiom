package com.axiom.strategy.service;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.MinuteCandleDto;
import com.axiom.strategy.util.TradingCalendar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;

/**
 * 분봉 기반 Entry Quality Multiplier 계산기.
 *
 * <p>세 축을 독립 계산 후 최소값 채택:
 * <ul>
 *   <li>shortMult — 직전 10분봉 내 현재가 위치</li>
 *   <li>sessionMult — 당일 시가·고점 대비 현재가</li>
 *   <li>gapMult — 당일 시가 vs 전일 종가 (갭업)</li>
 * </ul>
 *
 * <p>추가로 두 개의 강제 가드:
 * <ul>
 *   <li>guard-1: 갭업 +2% AND 09:15 이전 → -1.0 반환 (강제 DEFER)</li>
 *   <li>guard-2: 시가 대비 +2% AND 10:30 이전 → -1.0 반환 (강제 DEFER)</li>
 * </ul>
 */
@Slf4j
@Component
public class EntryQualityEvaluator {

    private final MarketClient marketClient;

    private final int    minuteCandleCount;
    private final int    minShortCandles;

    // sessionMult 파라미터
    private final double sessionBelowOpen;
    private final double sessionMild;
    private final double sessionModerate;
    private final double sessionElevated;
    private final boolean sessionNearHighDrop;

    // gapMult 파라미터
    private final double gapNormal;
    private final double gapMild;
    private final double gapLarge;
    private final double gapExtreme;

    // guards
    private final boolean gapUpGuardEnabled;
    private final double  gapUpGuardPct;
    private final LocalTime gapUpGuardUntil;
    private final boolean fomoGuardEnabled;
    private final double  fomoGuardPct;
    private final LocalTime fomoGuardUntil;

    public EntryQualityEvaluator(
            MarketClient marketClient,
            @Value("${ml-prediction.entry-timing.minute-candle-count:10}") int minuteCandleCount,
            @Value("${ml-prediction.entry-timing.short-mult.min-candles:5}") int minShortCandles,
            @Value("${ml-prediction.entry-timing.session-mult.below-open:1.10}") double sessionBelowOpen,
            @Value("${ml-prediction.entry-timing.session-mult.mild:1.00}")       double sessionMild,
            @Value("${ml-prediction.entry-timing.session-mult.moderate:0.85}")   double sessionModerate,
            @Value("${ml-prediction.entry-timing.session-mult.elevated:0.60}")   double sessionElevated,
            @Value("${ml-prediction.entry-timing.session-mult.near-high-drop:true}") boolean sessionNearHighDrop,
            @Value("${ml-prediction.entry-timing.gap-mult.normal:1.00}")  double gapNormal,
            @Value("${ml-prediction.entry-timing.gap-mult.mild:0.90}")    double gapMild,
            @Value("${ml-prediction.entry-timing.gap-mult.large:0.70}")   double gapLarge,
            @Value("${ml-prediction.entry-timing.gap-mult.extreme:0.50}") double gapExtreme,
            @Value("${ml-prediction.entry-timing.guards.gap-up.enabled:true}") boolean gapUpGuardEnabled,
            @Value("${ml-prediction.entry-timing.guards.gap-up.gap-pct:2.0}")  double gapUpGuardPct,
            @Value("${ml-prediction.entry-timing.guards.gap-up.until:09:15}")  String gapUpGuardUntil,
            @Value("${ml-prediction.entry-timing.guards.fomo.enabled:true}")      boolean fomoGuardEnabled,
            @Value("${ml-prediction.entry-timing.guards.fomo.pct-from-open:2.0}") double fomoGuardPct,
            @Value("${ml-prediction.entry-timing.guards.fomo.until:10:30}")       String fomoGuardUntil
    ) {
        this.marketClient = marketClient;
        this.minuteCandleCount   = minuteCandleCount;
        this.minShortCandles     = minShortCandles;
        this.sessionBelowOpen    = sessionBelowOpen;
        this.sessionMild         = sessionMild;
        this.sessionModerate     = sessionModerate;
        this.sessionElevated     = sessionElevated;
        this.sessionNearHighDrop = sessionNearHighDrop;
        this.gapNormal           = gapNormal;
        this.gapMild             = gapMild;
        this.gapLarge            = gapLarge;
        this.gapExtreme          = gapExtreme;
        this.gapUpGuardEnabled   = gapUpGuardEnabled;
        this.gapUpGuardPct       = gapUpGuardPct;
        this.gapUpGuardUntil     = LocalTime.parse(gapUpGuardUntil);
        this.fomoGuardEnabled    = fomoGuardEnabled;
        this.fomoGuardPct        = fomoGuardPct;
        this.fomoGuardUntil      = LocalTime.parse(fomoGuardUntil);
    }

    /**
     * Entry Quality Multiplier 계산. 강제 가드 발동 시 음수 반환(-1.0) → 호출자가 DEFER 처리.
     *
     * @param ticker  종목 코드
     * @param current 모델이 판단한 진입가(= 현재가, LiveCandle의 종가)
     * @param dailyCandles 일봉 배열 (마지막 원소는 당일 LiveCandle). 전일 종가·당일 시가/고점 계산용.
     * @return multiplier (0.5 ~ 1.10) 또는 -1.0 (강제 DEFER)
     */
    public double evaluate(String ticker, BigDecimal current, List<CandleDto> dailyCandles) {
        if (current == null || current.compareTo(BigDecimal.ZERO) <= 0 || dailyCandles == null || dailyCandles.size() < 2) {
            return 1.0;
        }

        CandleDto today = dailyCandles.get(dailyCandles.size() - 1);
        CandleDto prev  = dailyCandles.get(dailyCandles.size() - 2);

        BigDecimal openToday  = today.getOpenPrice();
        BigDecimal highToday  = today.getHighPrice() != null ? today.getHighPrice() : current;
        BigDecimal prevClose  = prev.getClosePrice();

        LocalTime now = LocalTime.now(TradingCalendar.KST);

        // ── Forced Guards ────────────────────────────────────────────────────
        double gapUpPct = pct(openToday, prevClose);
        if (gapUpGuardEnabled && now.isBefore(gapUpGuardUntil) && gapUpPct > gapUpGuardPct) {
            log.info("[EntryQuality] guard-1 발동 - ticker: {}, gapUp {}%, 현재 {}", ticker, String.format("%.2f", gapUpPct), now);
            return -1.0;
        }
        double pctFromOpen = pct(current, openToday);
        if (fomoGuardEnabled && now.isBefore(fomoGuardUntil) && pctFromOpen > fomoGuardPct) {
            log.info("[EntryQuality] guard-2 발동 - ticker: {}, +{}% from open, 현재 {}", ticker, String.format("%.2f", pctFromOpen), now);
            return -1.0;
        }

        // ── 3축 multiplier ───────────────────────────────────────────────────
        double shortMult   = calcShortMult(ticker, current);
        double sessionMult = calcSessionMult(current, openToday, highToday);
        double gapMult     = calcGapMult(gapUpPct);

        double result = Math.min(shortMult, Math.min(sessionMult, gapMult));
        log.debug("[EntryQuality] {} — short={} session={} gap={} → {}",
                ticker, fmt(shortMult), fmt(sessionMult), fmt(gapMult), fmt(result));
        return result;
    }

    // ── shortMult ─────────────────────────────────────────────────────────────

    private double calcShortMult(String ticker, BigDecimal current) {
        List<MinuteCandleDto> mins = marketClient.getMinuteCandles(ticker, minuteCandleCount);
        if (mins == null || mins.size() < minShortCandles) return 1.0;

        BigDecimal high = mins.stream().map(MinuteCandleDto::getHighPrice)
                .reduce(BigDecimal.ZERO, (a, b) -> a.compareTo(b) > 0 ? a : b);
        BigDecimal low  = mins.stream().map(MinuteCandleDto::getLowPrice)
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .reduce(mins.get(0).getHighPrice(), (a, b) -> a.compareTo(b) < 0 ? a : b);
        BigDecimal vwap = computeVwap(mins);

        double lowBound   = low.doubleValue()  * 1.001;
        double vwapBound  = vwap.doubleValue() * 1.001;
        double highBound  = high.doubleValue() * 0.997;
        double spikeBound = vwap.doubleValue() * 1.010;

        double c = current.doubleValue();
        if (c <= lowBound)   return 1.10;
        if (c <= vwapBound)  return 1.00;
        if (c <= highBound)  return 0.95;
        if (c >  spikeBound) return 0.50;
        return 0.70;
    }

    private BigDecimal computeVwap(List<MinuteCandleDto> mins) {
        BigDecimal tpVol = BigDecimal.ZERO;
        BigDecimal vol   = BigDecimal.ZERO;
        for (MinuteCandleDto m : mins) {
            BigDecimal tp = m.getHighPrice().add(m.getLowPrice()).add(m.getClosePrice())
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            BigDecimal v  = BigDecimal.valueOf(m.getVolume() != null ? m.getVolume() : 0L);
            tpVol = tpVol.add(tp.multiply(v));
            vol   = vol.add(v);
        }
        if (vol.signum() == 0) return mins.get(mins.size() - 1).getClosePrice();
        return tpVol.divide(vol, 4, RoundingMode.HALF_UP);
    }

    // ── sessionMult ──────────────────────────────────────────────────────────

    private double calcSessionMult(BigDecimal current, BigDecimal openToday, BigDecimal highToday) {
        if (openToday == null || openToday.compareTo(BigDecimal.ZERO) <= 0) return 1.0;
        double p = pct(current, openToday);

        double base;
        if      (p <= 0)   base = sessionBelowOpen;
        else if (p <= 0.5) base = sessionMild;
        else if (p <= 1.5) base = sessionModerate;
        else               base = sessionElevated;

        if (sessionNearHighDrop && highToday != null && highToday.signum() > 0) {
            double nearHighThreshold = highToday.doubleValue() * 0.995;
            if (current.doubleValue() >= nearHighThreshold) {
                base = dropOneStep(base);
            }
        }
        return base;
    }

    private double dropOneStep(double v) {
        // 한 단계 낮춤: 1.10→1.00→0.85→0.60
        if (v >= sessionBelowOpen) return sessionMild;
        if (v >= sessionMild)      return sessionModerate;
        if (v >= sessionModerate)  return sessionElevated;
        return sessionElevated;
    }

    // ── gapMult ──────────────────────────────────────────────────────────────

    private double calcGapMult(double gapUpPct) {
        if (gapUpPct <= 1.0) return gapNormal;
        if (gapUpPct <= 2.0) return gapMild;
        if (gapUpPct <= 3.5) return gapLarge;
        return gapExtreme;
    }

    // ── utils ────────────────────────────────────────────────────────────────

    private double pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) return 0.0;
        return numerator.subtract(denominator)
                .divide(denominator, 6, RoundingMode.HALF_UP)
                .doubleValue() * 100;
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
