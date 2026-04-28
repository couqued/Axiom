package com.axiom.strategy.service;

import com.axiom.strategy.dto.TradePlanDto;
import com.axiom.strategy.ml.MlPredictionFeature;
import com.axiom.strategy.ml.MlPredictionFeatureRepository;
import com.axiom.strategy.persistence.StrategyState;
import com.axiom.strategy.persistence.StrategyStateRepository;
import com.axiom.strategy.util.TradingCalendar;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ML 전략 포지션 추적 저장소.
 *
 * <ul>
 *   <li>{@code staged} — 체결 전 BUY 신호 시점의 TradePlan (메모리 전용, TTL 10분)</li>
 *   <li>{@code active} — 체결 후 TP/SL/maxDays 감시 중인 포지션 (메모리 + DB)</li>
 * </ul>
 *
 * <p>재시작 시 DB에서 active만 복구한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MlPositionStore {

    private static final String ML_PLAN = "ML_PLAN";
    private static final long STAGE_TTL_MS = 10 * 60 * 1000L;

    private final StrategyStateRepository         repo;
    private final MlPredictionFeatureRepository   predictionFeatureRepo;
    private final ObjectMapper objectMapper;

    private final Map<String, StagedPlan> staged = new ConcurrentHashMap<>();
    private final Map<String, ActivePlan> active = new ConcurrentHashMap<>();

    public record StagedPlan(TradePlanDto plan, double multiplier, LocalDateTime stagedAt) {}
    public record ActivePlan(TradePlanDto plan, LocalDate entryDate, BigDecimal actualEntryPrice) {}

    @PostConstruct
    @Transactional(readOnly = true)
    public void initFromDb() {
        try {
            for (StrategyState s : repo.findAllByType(ML_PLAN)) {
                try {
                    ActivePlan ap = objectMapper.readValue(s.getValue(), ActivePlanRecord.class).toActivePlan();
                    active.put(s.getTicker(), ap);
                } catch (Exception ex) {
                    log.warn("[MlPositionStore] ML_PLAN 역직렬화 실패 - ticker: {}, value: {}",
                            s.getTicker(), s.getValue());
                }
            }
            log.info("[MlPositionStore] DB 복구 완료 — active: {}개", active.size());
        } catch (Exception e) {
            log.warn("[MlPositionStore] DB 복구 실패: {}", e.getMessage());
        }
    }

    // ── staged (체결 전) ─────────────────────────────────────────────────────

    /** BUY 신호 발생 시 호출 — 체결 확정 전 임시 보관. */
    public void stage(String ticker, TradePlanDto plan, double multiplier) {
        staged.put(ticker, new StagedPlan(plan, multiplier, LocalDateTime.now()));
    }

    public Optional<StagedPlan> getStaged(String ticker) {
        purgeExpiredStaged();
        return Optional.ofNullable(staged.get(ticker));
    }

    public void clearStaged(String ticker) { staged.remove(ticker); }

    private void purgeExpiredStaged() {
        long now = System.currentTimeMillis();
        staged.entrySet().removeIf(e ->
                now - java.sql.Timestamp.valueOf(e.getValue().stagedAt()).getTime() > STAGE_TTL_MS);
    }

    // ── active (체결 후) ─────────────────────────────────────────────────────

    /**
     * 매수 체결 확정 시 호출. staged → active 로 승격.
     * @param ticker 체결된 종목
     * @param actualPrice 실제 체결 단가 (null이면 TradePlan.entryPrice 사용)
     */
    @Transactional
    public void activate(String ticker, BigDecimal actualPrice) {
        StagedPlan sp = staged.remove(ticker);
        if (sp == null) {
            log.debug("[MlPositionStore] activate — staged 없음, 스킵 ticker: {}", ticker);
            return;
        }
        BigDecimal entry = (actualPrice != null) ? actualPrice : sp.plan().entryPrice();
        ActivePlan ap = new ActivePlan(sp.plan(), LocalDate.now(TradingCalendar.KST), entry);
        active.put(ticker, ap);
        try {
            String json = objectMapper.writeValueAsString(ActivePlanRecord.from(ap));
            upsert(ticker, json);
        } catch (JsonProcessingException e) {
            log.warn("[MlPositionStore] ML_PLAN 직렬화 실패 - ticker: {}, error: {}",
                    ticker, e.getMessage());
        }
        // 예측 시점의 피처 벡터 저장 (features가 있는 경우만)
        Map<String, Double> features = sp.plan().features();
        if (features != null && !features.isEmpty()) {
            try {
                String featJson = objectMapper.writeValueAsString(features);
                predictionFeatureRepo.save(
                        new MlPredictionFeature(ticker, LocalDateTime.now(), sp.plan().confidence(), featJson));
            } catch (Exception e) {
                log.warn("[MlPositionStore] 피처 벡터 저장 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            }
        }
    }

    public Optional<ActivePlan> getActive(String ticker) {
        return Optional.ofNullable(active.get(ticker));
    }

    /** staged 없이 직접 플랜 등록 — 기존 포지션 복구용. */
    @Transactional
    public void activateDirect(String ticker, TradePlanDto plan, LocalDate entryDate, BigDecimal actualEntryPrice) {
        ActivePlan ap = new ActivePlan(plan, entryDate, actualEntryPrice);
        active.put(ticker, ap);
        try {
            String json = objectMapper.writeValueAsString(ActivePlanRecord.from(ap));
            upsert(ticker, json);
            log.info("[MlPositionStore] activateDirect — ticker: {}, saved to DB", ticker);
        } catch (JsonProcessingException e) {
            log.warn("[MlPositionStore] activateDirect 직렬화 실패 - ticker: {}, error: {}", ticker, e.getMessage());
        }
    }

    public Set<Map.Entry<String, ActivePlan>> activeEntries() {
        return active.entrySet();
    }

    public boolean isActive(String ticker) {
        return active.containsKey(ticker);
    }

    @Transactional
    public void clear(String ticker) {
        active.remove(ticker);
        staged.remove(ticker);
        repo.deleteByTypeAndTicker(ML_PLAN, ticker);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void upsert(String ticker, String value) {
        repo.findByTypeAndTicker(ML_PLAN, ticker).ifPresentOrElse(
                existing -> existing.setValue(value),
                () -> repo.save(new StrategyState(ML_PLAN, ticker, value))
        );
    }

    /** DB 직렬화 전용 레코드. TradePlan record는 Jackson에서 바로 다루기 불편해 내부 DTO 사용. */
    private record ActivePlanRecord(
            String ticker,
            double confidence,
            double mlScore,
            BigDecimal entryPrice,
            BigDecimal takeProfitPrice,
            BigDecimal stopLossPrice,
            int expectedDays,
            int maxDays,
            String reason,
            LocalDate entryDate,
            BigDecimal actualEntryPrice,
            Map<String, Double> features  // nullable — ml_prediction_features 연결용
    ) {
        static ActivePlanRecord from(ActivePlan ap) {
            TradePlanDto p = ap.plan();
            return new ActivePlanRecord(p.ticker(), p.confidence(), p.mlScore(),
                    p.entryPrice(), p.takeProfitPrice(), p.stopLossPrice(),
                    p.expectedDays(), p.maxDays(), p.reason(),
                    ap.entryDate(), ap.actualEntryPrice(), p.features());
        }
        ActivePlan toActivePlan() {
            TradePlanDto p = new TradePlanDto(ticker, confidence, mlScore,
                    entryPrice, takeProfitPrice, stopLossPrice,
                    expectedDays, maxDays, reason, features);
            return new ActivePlan(p, entryDate, actualEntryPrice);
        }
    }
}
