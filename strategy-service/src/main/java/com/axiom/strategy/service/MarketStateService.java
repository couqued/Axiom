package com.axiom.strategy.service;

import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.config.StrategyConfig;
import com.axiom.strategy.dto.CandleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.axiom.strategy.util.TradingCalendar;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.time.LocalTime;

/**
 * 코스피 지수 20일 이평선 대비 현재 종가 위치로 시장 상태를 판별한다.
 *
 * <ul>
 *   <li>종가 > MA20 → {@link MarketState#BULLISH} → 변동성 돌파 + 골든크로스 전략 실행</li>
 *   <li>종가 ≤ MA20 → {@link MarketState#SIDEWAYS} → RSI + 볼린저밴드 전략 실행</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStateService {

    private final StrategyConfig strategyConfig;
    private final MarketClient marketClient;

    /** 현재 시장 상태 (기본값: SIDEWAYS — 안전 우선) */
    private final AtomicReference<MarketState> currentState = new AtomicReference<>(MarketState.SIDEWAYS);

    // 지수 스냅샷 — 08:30 refresh() 시 저장
    private volatile BigDecimal yesterdayClose;
    private volatile BigDecimal ma20;

    // 오늘 장 시작 초기 지수 — 당일 첫 StrategyEngine.run() 시 저장
    private volatile BigDecimal todayOpenIndex;
    private volatile LocalDate  todayOpenDate;

    // 당일 코스피 하락 매수 차단 상태
    private volatile boolean    indexDropBlockedToday  = false;
    private volatile boolean    indexDropCheckedToday  = false;
    private volatile LocalDate  indexDropCheckDate     = null;

    public record IndexSnapshot(
            BigDecimal yesterdayClose,
            BigDecimal ma20,
            BigDecimal todayOpenIndex
    ) {}

    public MarketState getCurrentState() {
        return currentState.get();
    }

    public IndexSnapshot getIndexSnapshot() {
        return new IndexSnapshot(yesterdayClose, ma20, todayOpenIndex);
    }

    public boolean isIndexDropBlockedToday() { return indexDropBlockedToday; }
    public boolean isIndexDropCheckedToday() { return indexDropCheckedToday; }

    // ── ML market_breadth 캐시 ────────────────────────────────────────────────
    // watch-tickers 중 전일 대비 상승 종목 비율. StrategyEngine 루프 종료 후 갱신되므로
    // ML 추론에는 직전 사이클(~5분 전) 값이 사용된다.
    private volatile double marketBreadth = 0.5;

    public double getMarketBreadth() { return marketBreadth; }

    public void setMarketBreadth(double breadth) {
        this.marketBreadth = Math.max(0.0, Math.min(1.0, breadth));
        log.debug("[MarketState] market_breadth 갱신 → {}", String.format("%.2f", this.marketBreadth));
    }

    // ── ML용 지수 캔들 캐시 (60일치) ─────────────────────────────────────────
    private volatile List<CandleDto> cachedKospiCandles = List.of();
    private volatile long cachedKospiCandlesAt = 0L;
    private static final long KOSPI_CACHE_TTL_MS = 5 * 60 * 1000L; // 5분

    /**
     * ML 전략에서 시장 regime 피처 계산에 쓸 코스피 일봉 캔들.
     * 5분 주기 전략 호출 내에서 종목당 호출이 중복되지 않도록 캐시한다.
     */
    public List<CandleDto> getKospiCandlesCached() {
        long now = System.currentTimeMillis();
        if (cachedKospiCandles.isEmpty() || now - cachedKospiCandlesAt > KOSPI_CACHE_TTL_MS) {
            try {
                List<CandleDto> fresh = marketClient.getIndexCandles(
                        strategyConfig.getMarketFilter().getIndexCode(), 60);
                if (!fresh.isEmpty()) {
                    cachedKospiCandles   = fresh;
                    cachedKospiCandlesAt = now;
                }
            } catch (Exception e) {
                log.warn("[MarketState] KOSPI 캔들 캐시 갱신 실패: {}", e.getMessage());
            }
        }
        return cachedKospiCandles;
    }

    /**
     * 09:02 이후 당일 최초 1회 호출 — 전일 대비 하락률을 계산해 매수 차단 여부를 설정한다.
     */
    public void checkAndSetIndexDropBlock(BigDecimal currentIndex, double blockPct) {
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        if (indexDropCheckDate != null && indexDropCheckDate.equals(today)) return;

        indexDropCheckDate    = today;
        indexDropCheckedToday = true;

        if (yesterdayClose == null || yesterdayClose.compareTo(BigDecimal.ZERO) <= 0 || blockPct <= 0) {
            log.info("[MarketState] 지수 하락 체크 스킵 — yesterdayClose={}, blockPct={}", yesterdayClose, blockPct);
            return;
        }

        double dropPct = yesterdayClose.subtract(currentIndex)
                .divide(yesterdayClose, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        if (dropPct >= blockPct) {
            indexDropBlockedToday = true;
            log.warn("[MarketState] 코스피 전일대비 {}% 하락 — 당일 매수 차단 (설정: {}%)",
                    String.format("%.2f", dropPct), blockPct);
        } else {
            log.info("[MarketState] 코스피 전일대비 {}% → 매수 차단 없음 (설정: {}%)",
                    String.format("%.2f", dropPct), blockPct);
        }
    }

    /**
     * StrategyEngine.run() 시작 시 호출 — 당일 최초 1회만 저장 (9:05 AM 스냅샷).
     */
    public void captureTodayOpenIndex(BigDecimal price) {
        LocalDate today = LocalDate.now(TradingCalendar.KST);
        if (todayOpenDate == null || !todayOpenDate.equals(today)) {
            todayOpenIndex = price;
            todayOpenDate  = today;
            log.info("[MarketState] 오늘 장 초기 지수 저장 — {}: {}", today, price);
        }
    }

    /**
     * 지수 캔들 데이터를 조회하여 시장 상태를 갱신한다.
     * 매일 08:30 MarketStateScheduler에서 호출.
     */
    public void refresh() {
        StrategyConfig.MarketFilterConfig filterConfig = strategyConfig.getMarketFilter();

        if (!filterConfig.isEnabled()) {
            log.info("[MarketState] 시장 필터 비활성화 — 기본값 BULLISH 유지");
            currentState.set(MarketState.BULLISH);
            return;
        }

        String indexCode = filterConfig.getIndexCode();
        int maPeriod     = filterConfig.getMaPeriod();
        int fetchDays    = maPeriod + 5; // 여유분 포함

        List<CandleDto> candles = marketClient.getIndexCandles(indexCode, fetchDays);

        if (candles.size() < maPeriod) {
            log.warn("[MarketState] 지수 캔들 부족 — indexCode: {}, 필요: {}, 실제: {}. 이전 상태 유지.",
                    indexCode, maPeriod, candles.size());
            return;
        }

        // 최근 maPeriod개 종가의 단순 이동평균
        List<CandleDto> recent = candles.subList(candles.size() - maPeriod, candles.size());
        BigDecimal ma = recent.stream()
                .map(CandleDto::getClosePrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(maPeriod), 2, RoundingMode.HALF_UP);

        BigDecimal lastClose = candles.get(candles.size() - 1).getClosePrice();

        // 지수 스냅샷 저장
        this.yesterdayClose = lastClose;
        this.ma20           = ma;
        // refresh 시 todayOpenIndex 초기화 → 다음 전략 실행 시 당일 최신값으로 재캡처
        this.todayOpenDate  = null;
        // 당일 매수 차단 플래그 초기화
        this.indexDropBlockedToday = false;
        this.indexDropCheckedToday = false;
        this.indexDropCheckDate    = null;

        BigDecimal bearishThreshold = ma.multiply(BigDecimal.valueOf(0.97));

        MarketState newState;
        if (lastClose.compareTo(ma) > 0) {
            newState = MarketState.BULLISH;
        } else if (lastClose.compareTo(bearishThreshold) < 0) {
            newState = MarketState.BEARISH;
        } else {
            newState = MarketState.SIDEWAYS;
        }

        MarketState oldState = currentState.getAndSet(newState);

        log.info("[MarketState] 판별 완료 — indexCode: {}, 종가: {}, MA{}: {}, 상태: {}",
                indexCode, lastClose, maPeriod, ma, newState);

        if (oldState != newState) {
            log.info("[MarketState] 상태 변경: {} → {}", oldState, newState);
        }
    }
}
