package com.axiom.strategy.client;

import com.axiom.strategy.dto.OrderRequest;
import com.axiom.strategy.dto.OrderResult;
import com.axiom.strategy.dto.OrderSummaryDto;
import com.axiom.strategy.dto.SkippedSignalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderClient {

    @Qualifier("orderWebClient")
    private final WebClient orderWebClient;

    public OrderResult buy(OrderRequest request) {
        return placeOrder("/api/orders/buy", request);
    }

    public OrderResult sell(OrderRequest request) {
        return placeOrder("/api/orders/sell", request);
    }

    /** 전체 주문 이력 조회 (서비스 재시작 후 TimeCut buyDates 복구용) */
    public List<OrderSummaryDto> getFilledOrders() {
        try {
            return orderWebClient.get()
                    .uri("/api/orders")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<OrderSummaryDto>>() {})
                    .block();
        } catch (Exception e) {
            log.warn("[OrderClient] 주문 이력 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /** 스킵된 매수 신호를 order-service에 기록 (실패해도 전략 실행에 영향 없음) */
    public void recordSkipped(SkippedSignalRequest request) {
        try {
            orderWebClient.post()
                    .uri("/api/orders/skipped")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.warn("[OrderClient] 스킵 기록 실패 — ticker: {}, reason: {}, error: {}",
                    request.getTicker(), request.getSkipReason(), e.getMessage());
        }
    }

    private OrderResult placeOrder(String path, OrderRequest request) {
        try {
            Map<?, ?> response = orderWebClient.post()
                    .uri(path)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            log.info("[OrderClient] 주문 완료 - path: {}, ticker: {}, response: {}",
                    path, request.getTicker(), response);
            return OrderResult.ok();
        } catch (Exception e) {
            log.error("[OrderClient] 주문 실패 - path: {}, ticker: {}, error: {}",
                    path, request.getTicker(), e.getMessage());
            return OrderResult.fail(e.getMessage());
        }
    }
}
