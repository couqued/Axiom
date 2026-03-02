package com.axiom.strategy.client;

import com.axiom.strategy.dto.OrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderClient {

    @Qualifier("orderWebClient")
    private final WebClient orderWebClient;

    public boolean buy(OrderRequest request) {
        return placeOrder("/api/orders/buy", request);
    }

    public boolean sell(OrderRequest request) {
        return placeOrder("/api/orders/sell", request);
    }

    private boolean placeOrder(String path, OrderRequest request) {
        try {
            Map<?, ?> response = orderWebClient.post()
                    .uri(path)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            log.info("[OrderClient] 주문 완료 - path: {}, ticker: {}, response: {}",
                    path, request.getTicker(), response);
            return true;
        } catch (Exception e) {
            log.error("[OrderClient] 주문 실패 - path: {}, ticker: {}, error: {}",
                    path, request.getTicker(), e.getMessage());
            return false;
        }
    }
}
