package com.axiom.strategy.ml;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ML 예측 시점의 36개 피처 벡터 스냅샷.
 *
 * <p>매수 체결(activate) 시 저장, 청산(recordTradeResult) 후 mlTradeResultId 연결.
 * features_json 컬럼 분석으로 "어떤 피처 조합에서 승률이 높은가" 파악 가능.
 */
@Entity
@Table(schema = "strategy", name = "ml_prediction_features")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MlPredictionFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(nullable = false)
    private LocalDateTime predictedAt;

    private Double confidence;

    /** Jackson 직렬화한 36개 피처 key-value JSON */
    @Column(columnDefinition = "TEXT")
    private String featuresJson;

    /** 청산 후 ml_trade_results.id 연결 — null이면 아직 청산 전 */
    private Long mlTradeResultId;

    public MlPredictionFeature(String ticker, LocalDateTime predictedAt,
                                double confidence, String featuresJson) {
        this.ticker      = ticker;
        this.predictedAt = predictedAt;
        this.confidence  = confidence;
        this.featuresJson = featuresJson;
    }

    public void linkTradeResult(Long mlTradeResultId) {
        this.mlTradeResultId = mlTradeResultId;
    }
}
