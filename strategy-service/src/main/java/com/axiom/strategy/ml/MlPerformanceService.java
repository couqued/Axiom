package com.axiom.strategy.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlPerformanceService {

    private final MlModelSnapshotRepository snapshotRepo;
    private final MlTradeResultRepository   tradeResultRepo;

    @Transactional
    public void saveSnapshotIfNew(String trainedAt, Integer samples,
                                   Double valAuc, Double valMaeRet, Double valMaeDays) {
        if (trainedAt == null || trainedAt.isBlank()) return;
        if (snapshotRepo.existsByTrainedAt(trainedAt)) return;
        snapshotRepo.save(new MlModelSnapshot(trainedAt, samples, valAuc, valMaeRet, valMaeDays));
        log.info("[MlSnapshot] 새 모델 스냅샷 저장 — trainedAt: {}, valAuc: {}", trainedAt, valAuc);
    }

    /**
     * ML 포지션 청산 시 호출.
     * @param tag MlExitService 에서 넘어오는 원본 tag ("ML TP" | "ML SL" | "ML 최대보유")
     */
    @Transactional
    public void recordTradeResult(String ticker, String stockName,
                                   BigDecimal entryPrice, BigDecimal exitPrice,
                                   double confidence, BigDecimal takeProfitPrice,
                                   String tag, LocalDate entryDate) {
        if (entryPrice == null || exitPrice == null
                || entryPrice.compareTo(BigDecimal.ZERO) == 0) return;

        double actualReturn = exitPrice.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .doubleValue() * 100.0;

        double predictedReturn = 0.0;
        if (takeProfitPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
            predictedReturn = takeProfitPrice.subtract(entryPrice)
                    .divide(entryPrice, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;
        }

        String modelTrainedAt = snapshotRepo.findTopByOrderByRecordedAtDesc()
                .map(MlModelSnapshot::getTrainedAt)
                .orElse(null);

        boolean isWin = "ML TP".equals(tag);

        MlTradeResult result = MlTradeResult.builder()
                .ticker(ticker)
                .stockName(stockName)
                .modelTrainedAt(modelTrainedAt)
                .entryPrice(entryPrice)
                .exitPrice(exitPrice)
                .actualReturnPct(actualReturn)
                .predictedReturnPct(predictedReturn)
                .confidence(confidence)
                .closeReason(tag)
                .isWin(isWin)
                .entryDate(entryDate)
                .exitAt(LocalDateTime.now())
                .build();

        tradeResultRepo.save(result);
        log.info("[MlPerformance] 매매결과 저장 — ticker: {}, actual: {}%, predicted: {}%, win: {}",
                ticker,
                String.format("%.2f", actualReturn),
                String.format("%.2f", predictedReturn),
                isWin);
    }

    @Transactional(readOnly = true)
    public MlSummaryDto getSummary() {
        MlModelSnapshot latest = snapshotRepo.findTopByOrderByRecordedAtDesc().orElse(null);
        List<MlTradeResult> all = tradeResultRepo.findAll();

        long totalTrades = all.size();
        long winCount    = all.stream().filter(r -> Boolean.TRUE.equals(r.getIsWin())).count();
        double winRate   = totalTrades > 0 ? (winCount * 100.0 / totalTrades) : 0.0;
        double avgReturn = all.stream()
                .filter(r -> r.getActualReturnPct() != null)
                .mapToDouble(MlTradeResult::getActualReturnPct)
                .average().orElse(0.0);

        return new MlSummaryDto(
                latest != null ? latest.getTrainedAt()  : null,
                latest != null ? latest.getSamples()    : null,
                latest != null ? latest.getValAuc()     : null,
                latest != null ? latest.getValMaeRet()  : null,
                latest != null ? latest.getValMaeDays() : null,
                (int) totalTrades,
                (int) winCount,
                (int) (totalTrades - winCount),
                winRate,
                avgReturn
        );
    }

    @Transactional(readOnly = true)
    public Page<MlTradeResult> getTradeHistory(int page, int size) {
        return tradeResultRepo.findAllByOrderByExitAtDesc(PageRequest.of(page, size));
    }
}
