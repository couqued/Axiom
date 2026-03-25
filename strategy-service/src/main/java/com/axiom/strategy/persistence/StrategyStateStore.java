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
    public void saveBuyStage(String ticker, int stage, String tradingMode) {
        upsert(stageType(tradingMode), ticker, String.valueOf(stage));
    }

    @Transactional
    public void removeBuyStage(String ticker, String tradingMode) {
        repo.deleteByTypeAndTicker(stageType(tradingMode), ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> loadAllBuyStages(String tradingMode) {
        return repo.findAllByType(stageType(tradingMode)).stream()
                .collect(Collectors.toMap(
                        StrategyState::getTicker,
                        s -> Integer.parseInt(s.getValue())));
    }

    // ── ENTRY_TAG ────────────────────────────────────────────────────────────

    @Transactional
    public void saveEntryTag(String ticker, String tag, String tradingMode) {
        upsert(tagType(tradingMode), ticker, tag);
    }

    @Transactional
    public void removeEntryTag(String ticker, String tradingMode) {
        repo.deleteByTypeAndTicker(tagType(tradingMode), ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, String> loadAllEntryTags(String tradingMode) {
        return repo.findAllByType(tagType(tradingMode)).stream()
                .collect(Collectors.toMap(StrategyState::getTicker, StrategyState::getValue));
    }

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
    public void saveTodayBought(String ticker, LocalDate date, String mode) {
        upsert(todayBoughtType(mode), ticker, date.toString());
    }

    @Transactional
    public void removeTodayBought(String ticker, String mode) {
        repo.deleteByTypeAndTicker(todayBoughtType(mode), ticker);
    }

    @Transactional(readOnly = true)
    public Map<String, LocalDate> loadAllTodayBought(String mode) {
        return repo.findAllByType(todayBoughtType(mode)).stream()
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

    private String stageType(String tradingMode) {
        return BUY_STAGE + "_" + (tradingMode != null ? tradingMode : "paper");
    }

    private String tagType(String tradingMode) {
        return ENTRY_TAG + "_" + (tradingMode != null ? tradingMode : "paper");
    }

    private String todayBoughtType(String mode) {
        return TODAY_BOUGHT + "_" + (mode != null ? mode : "paper");
    }

    private void upsert(String type, String ticker, String value) {
        repo.findByTypeAndTicker(type, ticker).ifPresentOrElse(
                existing -> existing.setValue(value),
                () -> repo.save(new StrategyState(type, ticker, value))
        );
    }
}
