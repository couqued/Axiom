package com.axiom.strategy.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * order-service 주문 이력 조회용 최소 DTO.
 * 서비스 재시작 후 TimeCutService buyDates 복구에 사용.
 */
@Getter
@NoArgsConstructor
public class OrderSummaryDto {
    private String ticker;
    private String orderType;     // "BUY" | "SELL"
    private String strategyName;
    private String status;        // "FILLED" | "FAILED" | ...
    private LocalDateTime createdAt;
}
