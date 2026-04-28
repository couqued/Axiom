package com.axiom.strategy.ml;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MlPredictionFeatureRepository extends JpaRepository<MlPredictionFeature, Long> {

    /** 청산되지 않은(mlTradeResultId=null) 피처 중 해당 종목의 가장 최근 것 */
    Optional<MlPredictionFeature> findTopByTickerAndMlTradeResultIdIsNullOrderByPredictedAtDesc(String ticker);
}
