package com.axiom.market.service;

import com.axiom.market.client.PortfolioClient;
import com.axiom.market.dto.StockUniverse;
import com.axiom.market.entity.DailyCandle;
import com.axiom.market.entity.WatchTicker;
import com.axiom.market.entity.WatchTicker.Status;
import com.axiom.market.notification.MarketSlackNotifier;
import com.axiom.market.repository.DailyCandleRepository;
import com.axiom.market.repository.WatchTickerRepository;
import com.axiom.market.service.KisMarketApiService.MarketSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 워치리스트 비즈니스 로직.
 * - DB 영구 저장 (market.watch_tickers)
 * - 보유 포지션 보호 (자동 제거 시 pendingRemoval 플래그)
 * - 부트스트랩 (stock-universe.json → DB INSERT)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchTickerRepository repo;
    private final PortfolioClient portfolioClient;
    private final ObjectMapper objectMapper;
    private final KisMarketApiService kisMarketApiService;
    private final DailyCandleRepository dailyCandleRepository;
    private final MarketSlackNotifier slackNotifier;

    @Value("${watchlist.rebalance.dry-run:true}")
    private boolean dryRun;

    @Value("${watchlist.rebalance.liquidity.remove-threshold-krw:5000000000}")
    private BigDecimal liquidityRemoveKrw;

    @Value("${watchlist.rebalance.liquidity.restore-threshold-krw:10000000000}")
    private BigDecimal liquidityRestoreKrw;

    @Value("${watchlist.rebalance.market-cap.remove-threshold-krw:500000000000}")
    private BigDecimal marketCapRemoveKrw;

    @Value("${watchlist.rebalance.market-cap.restore-threshold-krw:600000000000}")
    private BigDecimal marketCapRestoreKrw;

    /**
     * 기동 시 DB가 비어있으면 stock-universe.json 부트스트랩.
     */
    @PostConstruct
    public void initIfEmpty() {
        long total = repo.count();
        if (total == 0) {
            log.info("[Watchlist] DB 비어있음 — stock-universe.json 부트스트랩 시작");
            int inserted = syncFromUniverse();
            log.info("[Watchlist] 부트스트랩 완료 — {}개 INSERT", inserted);
        } else {
            log.info("[Watchlist] DB 기존 데이터 — total: {}, ACTIVE: {}",
                    total, repo.countByStatus(Status.ACTIVE));
        }
    }

    /** ACTIVE + pendingRemoval 종목 (전략 평가/보유 종목 청산용) */
    public List<String> loadActiveTickers() {
        return repo.findByStatus(Status.ACTIVE).stream()
                .map(WatchTicker::getTicker)
                .collect(Collectors.toList());
    }

    /** ACTIVE 종목의 ticker → 종목명 맵 */
    public Map<String, String> loadActiveTickerNames() {
        return repo.findByStatus(Status.ACTIVE).stream()
                .collect(Collectors.toMap(WatchTicker::getTicker, WatchTicker::getStockName, (a, b) -> a));
    }

    /** 전체 워치리스트 조회 (Admin UI용) */
    public List<WatchTicker> findAll() {
        return repo.findAll();
    }

    /** stock-universe.json → DB 부트스트랩 (멱등). 신규 ticker만 INSERT. */
    @Transactional
    public int syncFromUniverse() {
        try {
            ClassPathResource resource = new ClassPathResource("stock-universe.json");
            StockUniverse universe = objectMapper.readValue(resource.getInputStream(), StockUniverse.class);

            Set<String> existing = repo.findAll().stream()
                    .map(WatchTicker::getTicker)
                    .collect(Collectors.toSet());

            Map<String, String> names = universe.getTickerNames() != null ? universe.getTickerNames() : Map.of();
            int inserted = 0;
            LocalDateTime now = LocalDateTime.now();

            if (universe.getKospi200() != null) {
                for (String ticker : universe.getKospi200()) {
                    if (existing.contains(ticker)) continue;
                    repo.save(WatchTicker.builder()
                            .ticker(ticker)
                            .stockName(names.getOrDefault(ticker, ticker))
                            .marketIndex("KOSPI200")
                            .status(Status.ACTIVE)
                            .addedAt(now)
                            .pendingRemoval(false)
                            .build());
                    inserted++;
                }
            }
            if (universe.getKosdaq150() != null) {
                for (String ticker : universe.getKosdaq150()) {
                    if (existing.contains(ticker)) continue;
                    repo.save(WatchTicker.builder()
                            .ticker(ticker)
                            .stockName(names.getOrDefault(ticker, ticker))
                            .marketIndex("KOSDAQ150")
                            .status(Status.ACTIVE)
                            .addedAt(now)
                            .pendingRemoval(false)
                            .build());
                    inserted++;
                }
            }
            return inserted;
        } catch (Exception e) {
            log.error("[Watchlist] syncFromUniverse 실패: {}", e.getMessage(), e);
            return 0;
        }
    }

    /** 수동 추가 (Admin UI용) */
    @Transactional
    public WatchTicker addManual(String ticker, String stockName, String marketIndex) {
        Optional<WatchTicker> existing = repo.findByTicker(ticker);
        WatchTicker w = existing.orElseGet(() -> WatchTicker.builder()
                .ticker(ticker)
                .addedAt(LocalDateTime.now())
                .pendingRemoval(false)
                .build());
        w.setStockName(stockName != null ? stockName : ticker);
        w.setMarketIndex(marketIndex != null ? marketIndex : "MANUAL");
        w.setStatus(Status.ACTIVE);
        w.setRemovalReason(null);
        w.setLastReviewedAt(LocalDateTime.now());
        return repo.save(w);
    }

    /** 수동 제외. 보유 포지션 있으면 pendingRemoval=true 만 설정. */
    @Transactional
    public WatchTicker excludeManual(String ticker, String reason) {
        WatchTicker w = repo.findByTicker(ticker)
                .orElseThrow(() -> new IllegalArgumentException("ticker not found: " + ticker));
        Set<String> activePositions = portfolioClient.getActiveTickers();
        if (activePositions.contains(ticker)) {
            w.setPendingRemoval(true);
            w.setRemovalReason("[수동] " + reason + " (보유 종목 — 청산 후 처리)");
            w.setLastReviewedAt(LocalDateTime.now());
            log.info("[Watchlist] 수동 제외 보류 (보유 종목) — ticker: {}, reason: {}", ticker, reason);
        } else {
            w.setStatus(Status.EXCLUDED_MANUAL);
            w.setRemovalReason("[수동] " + reason);
            w.setPendingRemoval(false);
            w.setLastReviewedAt(LocalDateTime.now());
            log.info("[Watchlist] 수동 제외 — ticker: {}, reason: {}", ticker, reason);
        }
        return repo.save(w);
    }

    /** 수동 제외 해제 → ACTIVE 복구 */
    @Transactional
    public WatchTicker restoreManual(String ticker) {
        WatchTicker w = repo.findByTicker(ticker)
                .orElseThrow(() -> new IllegalArgumentException("ticker not found: " + ticker));
        w.setStatus(Status.ACTIVE);
        w.setRemovalReason(null);
        w.setPendingRemoval(false);
        w.setLastReviewedAt(LocalDateTime.now());
        log.info("[Watchlist] 수동 제외 해제 — ticker: {}", ticker);
        return repo.save(w);
    }

    /** Phase 2 자동 제외 (보유 종목 보호). dry-run이 아닐 때만 호출. */
    @Transactional
    public void markAutoExcluded(String ticker, String reason) {
        WatchTicker w = repo.findByTicker(ticker).orElse(null);
        if (w == null) return;
        if (w.getStatus() == Status.EXCLUDED_MANUAL) return; // 수동 제외 우선
        Set<String> activePositions = portfolioClient.getActiveTickers();
        if (activePositions.contains(ticker)) {
            w.setPendingRemoval(true);
            w.setRemovalReason("[자동] " + reason + " (보유 종목 — 청산 후 처리)");
        } else {
            w.setStatus(Status.EXCLUDED_AUTO);
            w.setRemovalReason("[자동] " + reason);
            w.setPendingRemoval(false);
        }
        w.setLastReviewedAt(LocalDateTime.now());
        repo.save(w);
    }

    /** Phase 2 자동 복귀 (히스테리시스) */
    @Transactional
    public void markAutoRestored(String ticker) {
        WatchTicker w = repo.findByTicker(ticker).orElse(null);
        if (w == null) return;
        if (w.getStatus() != Status.EXCLUDED_AUTO) return;
        w.setStatus(Status.ACTIVE);
        w.setRemovalReason(null);
        w.setPendingRemoval(false);
        w.setLastReviewedAt(LocalDateTime.now());
        repo.save(w);
    }

    /** Phase 3 분기 KRX 정기변경에서 편출된 종목 */
    @Transactional
    public void markRemoved(String ticker, String reason) {
        WatchTicker w = repo.findByTicker(ticker).orElse(null);
        if (w == null) return;
        if (w.getStatus() == Status.EXCLUDED_MANUAL) return; // 수동 제외 우선
        Set<String> activePositions = portfolioClient.getActiveTickers();
        if (activePositions.contains(ticker)) {
            w.setPendingRemoval(true);
            w.setRemovalReason("[KRX] " + reason + " (보유 종목 — 청산 후 처리)");
        } else {
            w.setStatus(Status.REMOVED);
            w.setRemovalReason("[KRX] " + reason);
            w.setPendingRemoval(false);
        }
        w.setLastReviewedAt(LocalDateTime.now());
        repo.save(w);
    }

    /** 매일 08:31 sweep — pendingRemoval 종목이 보유 해제되었으면 최종 상태 적용 */
    @Transactional
    public int sweepPendingRemovals() {
        Set<String> activePositions = portfolioClient.getActiveTickers();
        List<WatchTicker> pending = repo.findAll().stream()
                .filter(WatchTicker::isPendingRemoval)
                .filter(w -> !activePositions.contains(w.getTicker()))
                .collect(Collectors.toList());
        for (WatchTicker w : pending) {
            String reason = w.getRemovalReason() != null ? w.getRemovalReason() : "";
            if (reason.startsWith("[KRX]")) {
                w.setStatus(Status.REMOVED);
            } else if (reason.startsWith("[자동]")) {
                w.setStatus(Status.EXCLUDED_AUTO);
            } else {
                w.setStatus(Status.EXCLUDED_MANUAL);
            }
            w.setPendingRemoval(false);
            w.setLastReviewedAt(LocalDateTime.now());
            repo.save(w);
            log.info("[Watchlist] sweep 완료 — ticker: {}, status: {}", w.getTicker(), w.getStatus());
        }
        return pending.size();
    }

    /** 강제 sync — Admin "리밸런싱 강제 실행" */
    @Transactional
    public Map<String, Integer> forceSync() {
        int added = syncFromUniverse();
        int swept = sweepPendingRemovals();
        Map<String, Integer> result = new HashMap<>();
        result.put("added", added);
        result.put("swept", swept);
        return result;
    }

    /**
     * 일일 자동 점검 (거래정지/관리종목/투자위험 자동 제외 + 정상화 종목 자동 복귀).
     * dry-run=true 일 때는 결정만 로그/Slack에 출력하고 DB는 변경하지 않음.
     */
    @Transactional
    public Map<String, Object> dailyReview() {
        List<WatchTicker> all = repo.findByStatusIn(List.of(Status.ACTIVE, Status.EXCLUDED_AUTO));
        List<MarketSlackNotifier.Change> excluded = new ArrayList<>();
        List<MarketSlackNotifier.Change> restored = new ArrayList<>();
        Set<String> activePositions = portfolioClient.getActiveTickers();

        for (WatchTicker w : all) {
            MarketSnapshot snap = kisMarketApiService.getMarketSnapshot(w.getTicker());
            if (snap == null) continue;

            boolean shouldExclude = snap.isHalted() || snap.isWarned();
            if (w.getStatus() == Status.ACTIVE && shouldExclude) {
                String reason = snap.isHalted()
                        ? "거래상태 코드 " + snap.stockStatusCode()
                        : "시장경보 코드 " + snap.marketWarnCode();
                excluded.add(new MarketSlackNotifier.Change(w.getTicker(), w.getStockName(), reason));
                if (!dryRun) applyAutoExclude(w, reason, activePositions);
            } else if (w.getStatus() == Status.EXCLUDED_AUTO && !shouldExclude) {
                String prev = w.getRemovalReason() != null ? w.getRemovalReason() : "";
                if (prev.contains("거래상태") || prev.contains("시장경보")) {
                    restored.add(new MarketSlackNotifier.Change(w.getTicker(), w.getStockName(),
                            "정상화"));
                    if (!dryRun) applyAutoRestore(w);
                }
            }
        }

        int swept = dryRun ? 0 : sweepPendingRemovals();
        slackNotifier.sendDailyReview(dryRun, excluded, restored);
        log.info("[Watchlist-DailyReview] dryRun: {}, excluded: {}, restored: {}, swept: {}",
                dryRun, excluded.size(), restored.size(), swept);
        Map<String, Object> result = new HashMap<>();
        result.put("dryRun", dryRun);
        result.put("excluded", excluded.size());
        result.put("restored", restored.size());
        result.put("swept", swept);
        return result;
    }

    /**
     * 주간 시총/거래대금 점검 (월요일 08:30).
     * 시총 5000억 미만 또는 20일 평균 거래대금 50억 미만 → 자동 제외.
     * 시총 6000억 회복 + 거래대금 100억 회복 → 자동 복귀 (히스테리시스).
     */
    @Transactional
    public Map<String, Object> weeklyMarketCapReview() {
        List<WatchTicker> all = repo.findByStatusIn(List.of(Status.ACTIVE, Status.EXCLUDED_AUTO));
        List<MarketSlackNotifier.Change> excluded = new ArrayList<>();
        List<MarketSlackNotifier.Change> restored = new ArrayList<>();
        Set<String> activePositions = portfolioClient.getActiveTickers();

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(40);

        for (WatchTicker w : all) {
            MarketSnapshot snap = kisMarketApiService.getMarketSnapshot(w.getTicker());
            if (snap == null) continue;

            BigDecimal mcKrw = snap.marketCapKrw();
            BigDecimal avgTurnover = computeAvgTurnover20d(w.getTicker(), from, to);

            // 캐시 갱신
            w.setMarketCap(mcKrw);
            w.setAvgTurnover20d(avgTurnover);

            boolean lowMc = mcKrw.compareTo(marketCapRemoveKrw) < 0 && mcKrw.signum() > 0;
            boolean lowLiq = avgTurnover.compareTo(liquidityRemoveKrw) < 0 && avgTurnover.signum() > 0;
            boolean highMc = mcKrw.compareTo(marketCapRestoreKrw) >= 0;
            boolean highLiq = avgTurnover.compareTo(liquidityRestoreKrw) >= 0;

            if (w.getStatus() == Status.ACTIVE && (lowMc || lowLiq)) {
                String reason = lowMc && lowLiq
                        ? String.format("시총 %s억 / 평균거래대금 %s억", toEok(mcKrw), toEok(avgTurnover))
                        : lowMc
                            ? String.format("시총 %s억 (임계 %s억 미달)", toEok(mcKrw), toEok(marketCapRemoveKrw))
                            : String.format("평균거래대금 %s억 (임계 %s억 미달)", toEok(avgTurnover), toEok(liquidityRemoveKrw));
                excluded.add(new MarketSlackNotifier.Change(w.getTicker(), w.getStockName(), reason));
                if (!dryRun) applyAutoExclude(w, reason, activePositions);
            } else if (w.getStatus() == Status.EXCLUDED_AUTO) {
                String prev = w.getRemovalReason() != null ? w.getRemovalReason() : "";
                boolean wasMc = prev.contains("시총");
                boolean wasLiq = prev.contains("거래대금");
                boolean restoreOk = (!wasMc || highMc) && (!wasLiq || highLiq);
                if (restoreOk && (wasMc || wasLiq)) {
                    String reason = String.format("시총 %s억 / 거래대금 %s억 회복", toEok(mcKrw), toEok(avgTurnover));
                    restored.add(new MarketSlackNotifier.Change(w.getTicker(), w.getStockName(), reason));
                    if (!dryRun) applyAutoRestore(w);
                }
            }

            if (!dryRun) {
                w.setLastReviewedAt(LocalDateTime.now());
                repo.save(w);
            }
        }

        slackNotifier.sendWeeklyReview(dryRun, excluded, restored);
        log.info("[Watchlist-WeeklyReview] dryRun: {}, excluded: {}, restored: {}",
                dryRun, excluded.size(), restored.size());
        Map<String, Object> result = new HashMap<>();
        result.put("dryRun", dryRun);
        result.put("excluded", excluded.size());
        result.put("restored", restored.size());
        return result;
    }

    /**
     * Phase 3 — 분기 KRX 정기변경 자동 반영.
     * - KIS에서 KOSPI200 + KOSDAQ150 현재 구성종목 조회.
     * - 둘 중 하나라도 실패하거나 비정상적으로 적게 조회되면 abort (DB write 금지) + 에러 슬랙.
     * - 신규 편입: 새 ticker INSERT (status=ACTIVE).
     * - 편출: ACTIVE/EXCLUDED_AUTO 종목이 두 지수 모두에 없으면 markRemoved.
     * - EXCLUDED_MANUAL은 절대 건드리지 않음.
     * dry-run=true 일 때는 결정만 로그/Slack에 출력하고 DB write 안 함.
     */
    @Transactional
    public Map<String, Object> quarterlyRebalance() {
        Map<String, Object> result = new HashMap<>();
        result.put("dryRun", dryRun);

        Map<String, String> kospi200 = kisMarketApiService.fetchIndexConstituents("1028");
        Map<String, String> kosdaq150 = kisMarketApiService.fetchIndexConstituents("2002");

        // sanity check — KOSPI200은 ~200, KOSDAQ150은 ~150이 정상.
        // 절반 미만이면 KIS endpoint 이상으로 간주 abort.
        boolean kospiOk = kospi200 != null && kospi200.size() >= 100;
        boolean kosdaqOk = kosdaq150 != null && kosdaq150.size() >= 75;
        if (!kospiOk || !kosdaqOk) {
            String error = String.format(
                    "지수 구성종목 조회 실패 — KOSPI200: %s, KOSDAQ150: %s. KIS endpoint 점검 필요.",
                    kospi200 != null ? kospi200.size() + "개" : "null",
                    kosdaq150 != null ? kosdaq150.size() + "개" : "null");
            log.error("[Watchlist-Quarterly] {}", error);
            slackNotifier.sendQuarterlyRebalance(dryRun, List.of(), List.of(), error);
            result.put("error", error);
            return result;
        }

        // ticker → marketIndex 라벨
        Map<String, String> universeIndex = new HashMap<>();
        Map<String, String> universeName = new HashMap<>();
        for (var e : kospi200.entrySet()) {
            universeIndex.put(e.getKey(), "KOSPI200");
            universeName.put(e.getKey(), e.getValue());
        }
        for (var e : kosdaq150.entrySet()) {
            universeIndex.putIfAbsent(e.getKey(), "KOSDAQ150");
            universeName.putIfAbsent(e.getKey(), e.getValue());
        }
        Set<String> universe = universeIndex.keySet();

        List<MarketSlackNotifier.Change> added = new ArrayList<>();
        List<MarketSlackNotifier.Change> removed = new ArrayList<>();
        Set<String> activePositions = portfolioClient.getActiveTickers();

        // 1) 편출 — DB의 ACTIVE/EXCLUDED_AUTO 중 universe에 없는 종목
        List<WatchTicker> existing = repo.findByStatusIn(List.of(Status.ACTIVE, Status.EXCLUDED_AUTO));
        for (WatchTicker w : existing) {
            if (!universe.contains(w.getTicker())) {
                String reason = "지수 편출 (KRX 분기 정기변경)";
                removed.add(new MarketSlackNotifier.Change(w.getTicker(), w.getStockName(), reason));
                if (!dryRun) {
                    if (w.getStatus() == Status.EXCLUDED_MANUAL) continue;
                    if (activePositions.contains(w.getTicker())) {
                        w.setPendingRemoval(true);
                        w.setRemovalReason("[KRX] " + reason + " (보유 종목 — 청산 후 처리)");
                    } else {
                        w.setStatus(Status.REMOVED);
                        w.setRemovalReason("[KRX] " + reason);
                        w.setPendingRemoval(false);
                    }
                    w.setLastReviewedAt(LocalDateTime.now());
                    repo.save(w);
                }
            }
        }

        // 2) 신규 편입 — universe에 있는데 DB에 없거나 REMOVED 상태인 종목
        Map<String, WatchTicker> existingMap = repo.findAll().stream()
                .collect(Collectors.toMap(WatchTicker::getTicker, w -> w, (a, b) -> a));
        for (String ticker : universe) {
            WatchTicker w = existingMap.get(ticker);
            if (w == null) {
                added.add(new MarketSlackNotifier.Change(ticker, universeName.get(ticker),
                        "신규 편입 — " + universeIndex.get(ticker)));
                if (!dryRun) {
                    repo.save(WatchTicker.builder()
                            .ticker(ticker)
                            .stockName(universeName.getOrDefault(ticker, ticker))
                            .marketIndex(universeIndex.get(ticker))
                            .status(Status.ACTIVE)
                            .addedAt(LocalDateTime.now())
                            .lastReviewedAt(LocalDateTime.now())
                            .pendingRemoval(false)
                            .build());
                }
            } else if (w.getStatus() == Status.REMOVED) {
                added.add(new MarketSlackNotifier.Change(ticker, w.getStockName(),
                        "재편입 — " + universeIndex.get(ticker)));
                if (!dryRun) {
                    w.setStatus(Status.ACTIVE);
                    w.setMarketIndex(universeIndex.get(ticker));
                    w.setRemovalReason(null);
                    w.setPendingRemoval(false);
                    w.setLastReviewedAt(LocalDateTime.now());
                    repo.save(w);
                }
            }
        }

        slackNotifier.sendQuarterlyRebalance(dryRun, added, removed, null);
        log.info("[Watchlist-Quarterly] dryRun: {}, added: {}, removed: {}",
                dryRun, added.size(), removed.size());
        result.put("added", added.size());
        result.put("removed", removed.size());
        return result;
    }

    private void applyAutoExclude(WatchTicker w, String reason, Set<String> activePositions) {
        if (w.getStatus() == Status.EXCLUDED_MANUAL) return;
        if (activePositions.contains(w.getTicker())) {
            w.setPendingRemoval(true);
            w.setRemovalReason("[자동] " + reason + " (보유 종목 — 청산 후 처리)");
        } else {
            w.setStatus(Status.EXCLUDED_AUTO);
            w.setRemovalReason("[자동] " + reason);
            w.setPendingRemoval(false);
        }
        w.setLastReviewedAt(LocalDateTime.now());
        repo.save(w);
    }

    private void applyAutoRestore(WatchTicker w) {
        w.setStatus(Status.ACTIVE);
        w.setRemovalReason(null);
        w.setPendingRemoval(false);
        w.setLastReviewedAt(LocalDateTime.now());
        repo.save(w);
    }

    private BigDecimal computeAvgTurnover20d(String ticker, LocalDate from, LocalDate to) {
        List<DailyCandle> candles = dailyCandleRepository
                .findByTickerAndTradeDateBetweenOrderByTradeDateAsc(ticker, from, to);
        if (candles.isEmpty()) return BigDecimal.ZERO;
        int n = Math.min(20, candles.size());
        List<DailyCandle> recent = candles.subList(candles.size() - n, candles.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (DailyCandle c : recent) {
            sum = sum.add(c.getClosePrice().multiply(BigDecimal.valueOf(c.getVolume())));
        }
        return sum.divide(BigDecimal.valueOf(n), 0, RoundingMode.HALF_UP);
    }

    private String toEok(BigDecimal krw) {
        if (krw == null) return "0";
        return krw.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP).toPlainString();
    }

    /** 통계 */
    public Map<String, Long> counts() {
        Map<String, Long> result = new HashMap<>();
        for (Status s : Status.values()) {
            result.put(s.name(), repo.countByStatus(s));
        }
        return result;
    }
}
