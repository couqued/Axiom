package com.axiom.portfolio.kafka;

import com.axiom.portfolio.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final PortfolioService portfolioService;

    @KafkaListener(topics = "order-events", groupId = "portfolio-service")
    public void consume(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        if (!"ORDER_FILLED".equals(eventType)) return;

        String ticker = (String) event.get("ticker");
        String stockName = (String) event.get("stockName");
        String orderType = (String) event.get("orderType");
        int quantity = ((Number) event.get("quantity")).intValue();
        BigDecimal price = new BigDecimal(event.get("price").toString());

        log.info("[Kafka] 주문 체결 이벤트 수신 - ticker: {}, type: {}, qty: {}, price: {}",
                ticker, orderType, quantity, price);

        if ("BUY".equals(orderType)) {
            portfolioService.addPosition(ticker, stockName, quantity, price);
        } else if ("SELL".equals(orderType)) {
            portfolioService.reducePosition(ticker, quantity, price);
        }
    }
}
