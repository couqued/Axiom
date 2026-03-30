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
    private static final String BUY_STAGE    = "BUY_STAGE";
    private static final String ENTRY_TAG    = "ENTRY_TAG";

    private final StrategyStateRepository repo;

    // ── BUY_STAGE ───────────────────────────────────────────────────────────

    @Transactional
    public void saveBuyStage(String ticker, int stage) {
        upsert(BUY_STAGE, ticker, String.valueOf(stage));
    }

    @Transactional
    public void removeBuyStage(String ticker) {
        repo.deleteByTypeAndTicker(BUY_STAGE, ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> loadAllBuyStages() {
        return repo.findAllByType(BUY_STAGE).stream()
                .collect(Collectors.toMap(
                        StrategyState::getTicker,
                        s -> Integer.parseInt(s.getValue())));
    }

    // ── ENTRY_TAG ────────────────────────────────────────────────────────────

    @Transactional
    public void saveEntryTag(String ticker, String tag) {
        upsert(ENTRY_TAG, ticker, tag);
    }

    @Transactional
    public void removeEntryTag(String ticker) {
        repo.deleteByTypeAndTicker(ENTRY_TAG, ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, String> loadAllEntryTags() {
        return repo.findAllByType(ENTRY_TAG).stream()
                .collect(Collectors.toMap(StrategyState::getTicker, StrategyState::getValue));
    }

    // ── PEAK_PRICE ──────────────────────────────────────────────────────────

    @Transactional
    public void savePeakPrice(String ticker, BigDecimal price) {
        upsert(PEAK_PRICE, ticker, price.toPlainString());
    }

    @Transactional
    public void removePeakPrice(String ticker) {
        repo.deleteByTypeAndTicker(PEAK_PRICE, ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> loadAllPeakPrices() {
        return repo.findAllByType(PEAK_PRICE).stream()
                .collect(Collectors.toMap(
                        StrategyState::getTicker,
                        s -> new BigDecimal(s.getValue())));
    }

    // ── BUY_DATE ────────────────────────────────────────────────────────────

    @Transactional
    public void saveBuyDate(String ticker, LocalDate date) {
        upsert(BUY_DATE, ticker, date.toString());
    }

    @Transactional
    public void removeBuyDate(String ticker) {
        repo.deleteByTypeAndTicker(BUY_DATE, ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, LocalDate> loadAllBuyDates() {
        return repo.findAllByType(BUY_DATE).stream()
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

    private void upsert(String type, String ticker, String value) {
        repo.findByTypeAndTicker(type, ticker).ifPresentOrElse(
                existing -> existing.setValue(value),
                () -> repo.save(new StrategyState(type, ticker, value))
        );
    }
}
