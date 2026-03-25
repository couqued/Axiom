package com.axiom.strategy.service;

import com.axiom.strategy.admin.AdminConfigStore;
import com.axiom.strategy.client.MarketClient;
import com.axiom.strategy.persistence.StrategyStateStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSI-Bollinger 전략의 2차 매수 대기 현황을 표시용으로 관리한다.
 *
 * <p>1차 매수(stage=1) 성공 시 등록되며, 2차 매수 완료 또는 매도/수동청산 시 해제된다.
 * 예약 금액은 실제 자금을 잠그지 않으며 Admin UI 표시 목적으로만 사용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BollingerReserveService {

    private final StrategyStateStore stateStore;
    private final AdminConfigStore adminConfigStore;
    private final MarketClient marketClient;

    /** 2차 매수 대기 항목 */
    public record ReservationEntry(String stockName, int amount) {}

    /** ticker → 2차 매수 대기 정보 */
    private final Map<String, ReservationEntry> reservations = new ConcurrentHashMap<>();

    /** 서비스 재시작 시 DB의 buyStage=1 + entryTag=rsi-bollinger 종목 복구 */
    @PostConstruct
    void restoreReservations() {
        Map<String, String> tickerNames;
        try {
            tickerNames = marketClient.getTickerNames();
        } catch (Exception e) {
            log.warn("[BollingerReserve] ticker-names 조회 실패 — ticker로 대체: {}", e.getMessage());
            tickerNames = Map.of();
        }
        final Map<String, String> nameMap = tickerNames != null ? tickerNames : Map.of();

        for (String mode : List.of("paper", "real")) {
            Map<String, Integer> stages = stateStore.loadAllBuyStages(mode);
            Map<String, String> tags = stateStore.loadAllEntryTags(mode);
            int halfInvest = adminConfigStore.getSettings(mode).investAmountKrw() / 2;
            stages.entrySet().stream()
                    .filter(e -> e.getValue() == 1)
                    .filter(e -> "rsi-bollinger".equals(tags.get(e.getKey())))
                    .forEach(e -> {
                        String ticker = e.getKey();
                        String stockName = nameMap.getOrDefault(ticker, ticker);
                        reservations.putIfAbsent(ticker, new ReservationEntry(stockName, halfInvest));
                        log.info("[BollingerReserve] 복구 — {}/{}({}원), mode: {}",
                                stockName, ticker, halfInvest, mode);
                    });
        }
        if (!reservations.isEmpty()) {
            log.info("[BollingerReserve] 총 {}개 종목 2차 매수 대기 복구 완료", reservations.size());
        }
    }

    /** 1차 매수 성공 후 2차 매수 대기 등록 */
    public void reserve(String ticker, String stockName, int amount) {
        reservations.put(ticker, new ReservationEntry(stockName, amount));
        log.info("[BollingerReserve] 2차 매수 대기 등록 — {}/{}({}원)", stockName, ticker, amount);
    }

    /** 2차 매수 시 예약 금액 조회 (없으면 0) */
    public int getReserved(String ticker) {
        ReservationEntry entry = reservations.get(ticker);
        return entry != null ? entry.amount() : 0;
    }

    /** 2차 매수 완료 또는 매도/수동청산 시 등록 해제 */
    public void clear(String ticker) {
        ReservationEntry removed = reservations.remove(ticker);
        if (removed != null) {
            log.info("[BollingerReserve] 2차 매수 대기 해제 — {}/{}", removed.stockName(), ticker);
        }
    }

    /** Admin 표시용: 전체 2차 매수 대기 현황 (불변 복사본) */
    public Map<String, ReservationEntry> getAllReservations() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(reservations));
    }
}
