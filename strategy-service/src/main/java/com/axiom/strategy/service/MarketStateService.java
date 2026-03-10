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

        MarketState newState = lastClose.compareTo(ma) > 0 ? MarketState.BULLISH : MarketState.SIDEWAYS;
        MarketState oldState = currentState.getAndSet(newState);

        log.info("[MarketState] 판별 완료 — indexCode: {}, 종가: {}, MA{}: {}, 상태: {}",
                indexCode, lastClose, maPeriod, ma, newState);

        if (oldState != newState) {
            log.info("[MarketState] 상태 변경: {} → {}", oldState, newState);
        }
    }
}
