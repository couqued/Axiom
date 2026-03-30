package com.axiom.order.service;

import com.axiom.order.client.PortfolioClient;
import com.axiom.order.dto.OrderRequest;
import com.axiom.order.dto.OrderResponse;
import com.axiom.order.dto.PortfolioItemDto;
import com.axiom.order.entity.TradeOrder;
import com.axiom.order.entity.TradeOrder.OrderType;
import com.axiom.order.kafka.OrderEventProducer;
import com.axiom.order.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final TradeOrderRepository orderRepository;
    private final KisOrderApiService kisOrderApiService;
    private final OrderEventProducer orderEventProducer;
    private final PortfolioClient portfolioClient;

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
            order.setFailureReason(e.getMessage());
            orderRepository.save(order);
            log.error("주문 실패 - orderId: {}, error: {}", order.getId(), e.getMessage(), e);
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

    @Transactional
    public void deleteByTicker(String ticker) {
        orderRepository.deleteByTicker(ticker);
    }

    public List<OrderResponse> sellAll() {
        List<PortfolioItemDto> positions = portfolioClient.getPositions();
        List<OrderResponse> results = new ArrayList<>();
        for (PortfolioItemDto p : positions) {
            OrderRequest req = new OrderRequest();
            req.setTicker(p.getTicker());
            req.setStockName(p.getStockName());
            req.setOrderType(OrderType.SELL);
            req.setQuantity(p.getQuantity());
            req.setPrice(BigDecimal.ZERO);
            req.setCloseReason("FORCE_EXIT");
            results.add(placeOrder(req));
        }
        log.info("[sellAll] 종목 수: {}", results.size());
        return results;
    }
}
