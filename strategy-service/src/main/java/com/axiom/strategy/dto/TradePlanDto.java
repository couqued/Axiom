package com.axiom.strategy.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * ML 예측 전략의 단일 종목 매매 계획.
 *
 * <p>ml-service {@code POST /predict} 응답을 Java 측에서 소비하는 DTO.
 * 매수 체결 후 {@link com.axiom.strategy.service.MlPositionStore} 에서
 * TP/SL/maxDays 청산 기준으로 재사용된다.
 */
public record TradePlanDto(
        String ticker,
        double confidence,              // 상승 확률 (0~1)
        double mlScore,                 // confidence × 100
        BigDecimal entryPrice,          // 모델 평가 시점의 현재가
        BigDecimal takeProfitPrice,     // 목표가
        BigDecimal stopLossPrice,       // 손절가
        int expectedDays,               // 예상 보유 거래일
        int maxDays,                    // 최대 보유 상한 (하드 가드)
        String reason,                  // 로그/Slack 표시용
        Map<String, Double> features    // 예측 시점의 36개 피처값 (nullable)
) {
    public boolean isBuy() { return confidence > 0.0 && entryPrice != null; }
}
