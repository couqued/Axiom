package com.axiom.order.service;

import com.axiom.order.dto.OrderRequest;
import com.axiom.order.dto.OrderResponse;
import com.axiom.order.entity.TradeOrder;
import com.axiom.order.kafka.OrderEventProducer;
import com.axiom.order.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final TradeOrderRepository orderRepository;
    private final KisOrderApiService kisOrderApiService;
    private final OrderEventProducer orderEventProducer;

    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        BigDecimal price = request.getPrice() != null ? request.getPrice() : BigDecimal.ZERO;
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(request.getQuantity()));

        TradeOrder order = TradeOrder.builder()
                .ticker(request.getTicker())
                .stockName(request.getStockName())
                .orderType(request.getOrderType())
                .quantity(request.getQuantity())
                .price(price)
                .totalAmount(totalAmount)
                .status(TradeOrder.OrderStatus.PENDING)
                .strategyName(request.getStrategyName())
                .marketState(request.getMarketState())
                .closeReason(request.getCloseReason())
                .build();

        order = orderRepository.save(order);

        try {
            String kisOrderId = kisOrderApiService.placeOrder(
                    request.getTicker(),
                    request.getOrderType().name(),
                    request.getQuantity(),
                    price
            );
            order.setKisOrderId(kisOrderId);
            order.setStatus(TradeOrder.OrderStatus.FILLED);
            order.setFilledAt(LocalDateTime.now());
            order = orderRepository.save(order);

            orderEventProducer.publishOrderFilled(order);
            log.info("주문 체결 완료 - orderId: {}, ticker: {}", order.getId(), order.getTicker());

        } catch (Exception e) {
            order.setStatus(TradeOrder.OrderStatus.FAILED);
            orderRepository.save(order);
            log.error("주문 실패 - orderId: {}, error: {}", order.getId(), e.getMessage());
            throw new RuntimeException("주문 처리 중 오류가 발생했습니다: " + e.getMessage());
        }

        return OrderResponse.from(order);
    }

    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    public List<OrderResponse> getOrdersByTicker(String ticker) {
        return orderRepository.findByTickerOrderByCreatedAtDesc(ticker).stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }
}
