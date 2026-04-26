package com.axiom.strategy.ml;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(schema = "strategy", name = "ml_trade_results")
@Getter
@NoArgsConstructor
public class MlTradeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(length = 50)
    private String stockName;

    /** ml_model_snapshots.trained_at — 매매에 사용된 모델 버전 */
    @Column(length = 50)
    private String modelTrainedAt;

    @Column(precision = 15, scale = 2)
    private BigDecimal entryPrice;

    @Column(precision = 15, scale = 2)
    private BigDecimal exitPrice;

    private Double actualReturnPct;

    private Double predictedReturnPct;

    private Double confidence;

    /** "ML TP" | "ML SL" | "ML 최대보유" */
    @Column(length = 30)
    private String closeReason;

    private Boolean isWin;

    private LocalDate entryDate;

    private LocalDateTime exitAt;

    @Builder
    public MlTradeResult(String ticker, String stockName, String modelTrainedAt,
                         BigDecimal entryPrice, BigDecimal exitPrice,
                         Double actualReturnPct, Double predictedReturnPct,
                         Double confidence, String closeReason, Boolean isWin,
                         LocalDate entryDate, LocalDateTime exitAt) {
        this.ticker             = ticker;
        this.stockName          = stockName;
        this.modelTrainedAt     = modelTrainedAt;
        this.entryPrice         = entryPrice;
        this.exitPrice          = exitPrice;
        this.actualReturnPct    = actualReturnPct;
        this.predictedReturnPct = predictedReturnPct;
        this.confidence         = confidence;
        this.closeReason        = closeReason;
        this.isWin              = isWin;
        this.entryDate          = entryDate;
        this.exitAt             = exitAt;
    }
}
