package com.axiom.order.kafka;

import com.axiom.order.entity.TradeOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private static final String TOPIC = "order-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderFilled(TradeOrder order) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "ORDER_FILLED");
        event.put("orderId", order.getId());
        event.put("ticker", order.getTicker());
        event.put("stockName", order.getStockName());
        event.put("orderType", order.getOrderType().name());
        event.put("quantity", order.getQuantity());
        event.put("price", order.getPrice());
        event.put("totalAmount", order.getTotalAmount());
        event.put("filledAt", order.getFilledAt() != null ? order.getFilledAt().toString() : null);

        kafkaTemplate.send(TOPIC, order.getTicker(), event);
        log.info("[Kafka] 주문 체결 이벤트 발행 - orderId: {}, ticker: {}, type: {}",
                order.getId(), order.getTicker(), order.getOrderType());
    }
}
