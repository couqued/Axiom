package com.axiom.strategy.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyStateStore {

    private static final String PEAK_PRICE   = "PEAK_PRICE";
    private static final String BUY_DATE     = "BUY_DATE";
    private static final String TODAY_BOUGHT = "TODAY_BOUGHT";

    private final StrategyStateRepository repo;

    // ── PEAK_PRICE ──────────────────────────────────────────────────────────

    @Transactional
    public void savePeakPrice(String ticker, BigDecimal price, String tradingMode) {
        upsert(peakType(tradingMode), ticker, price.toPlainString());
    }

    @Transactional
    public void removePeakPrice(String ticker, String tradingMode) {
        repo.deleteByTypeAndTicker(peakType(tradingMode), ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> loadAllPeakPrices(String tradingMode) {
        return repo.findAllByType(peakType(tradingMode)).stream()
                .collect(Collectors.toMap(
                        StrategyState::getTicker,
                        s -> new BigDecimal(s.getValue())));
    }

    // ── BUY_DATE ────────────────────────────────────────────────────────────

    @Transactional
    public void saveBuyDate(String ticker, LocalDate date, String tradingMode) {
        upsert(buyType(tradingMode), ticker, date.toString());
    }

    @Transactional
    public void removeBuyDate(String ticker, String tradingMode) {
        repo.deleteByTypeAndTicker(buyType(tradingMode), ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, LocalDate> loadAllBuyDates(String tradingMode) {
        return repo.findAllByType(buyType(tradingMode)).stream()
                .collect(Collectors.toMap(
                        StrategyState::getTicker,
                        s -> LocalDate.parse(s.getValue())));
    }

    // ── TODAY_BOUGHT ─────────────────────────────────────────────────────────

    @Transactional
    public void saveTodayBought(String ticker, LocalDate date) {
        upsert(TODAY_BOUGHT, ticker, date.toString());
    }

    @Transactional
    public void removeTodayBought(String ticker) {
        repo.deleteByTypeAndTicker(TODAY_BOUGHT, ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, LocalDate> loadAllTodayBought() {
        return repo.findAllByType(TODAY_BOUGHT).stream()
                .collect(Collectors.toMap(
                        StrategyState::getTicker,
                        s -> LocalDate.parse(s.getValue())));
    }

    // ── Private ──────────────────────────────────────────────────────────────

    /** tradingMode를 type 키에 내포: "PEAK_PRICE_paper", "PEAK_PRICE_real" */
    private String peakType(String tradingMode) {
        return PEAK_PRICE + "_" + (tradingMode != null ? tradingMode : "paper");
    }

    private String buyType(String tradingMode) {
        return BUY_DATE + "_" + (tradingMode != null ? tradingMode : "paper");
    }

    private void upsert(String type, String ticker, String value) {
        repo.findByTypeAndTicker(type, ticker).ifPresentOrElse(
                existing -> existing.setValue(value),
                () -> repo.save(new StrategyState(type, ticker, value))
        );
    }
}
