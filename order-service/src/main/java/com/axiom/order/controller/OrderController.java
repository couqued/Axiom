package com.axiom.order.controller;

import com.axiom.order.dto.OrderRequest;
import com.axiom.order.dto.OrderResponse;
import com.axiom.order.entity.TradeOrder.OrderType;
import com.axiom.order.service.OrderService;
import com.axiom.order.store.TradingModeStore;
import com.axiom.order.util.MarketHoursChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final MarketHoursChecker marketHoursChecker;
    private final TradingModeStore tradingModeStore;

    // 매수 주문: POST /api/orders/buy
    @PostMapping("/api/orders/buy")
    public ResponseEntity<?> buy(@RequestBody OrderRequest request) {
        if (!marketHoursChecker.isMarketOpen()) {
            return marketClosedResponse();
        }
        request.setOrderType(OrderType.BUY);
        return ResponseEntity.ok(orderService.placeOrder(request));
    }

    // 매도 주문: POST /api/orders/sell
    @PostMapping("/api/orders/sell")
    public ResponseEntity<?> sell(@RequestBody OrderRequest request) {
        if (!marketHoursChecker.isMarketOpen()) {
            return marketClosedResponse();
        }
        request.setOrderType(OrderType.SELL);
        return ResponseEntity.ok(orderService.placeOrder(request));
    }

    private ResponseEntity<Map<String, String>> marketClosedResponse() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error",       "MARKET_CLOSED",
                "message",     "현재 주식 시장 운영 시간이 아닙니다. (평일 09:00~15:30 KST)",
                "marketOpenAt", marketHoursChecker.nextMarketOpenAt()
        ));
    }

    // 주문 내역: GET /api/orders?mode=paper|real (없으면 전체)
    @GetMapping("/api/orders")
    public ResponseEntity<List<OrderResponse>> getOrders(
            @RequestParam(required = false) String mode) {
        return ResponseEntity.ok(orderService.getOrdersByMode(mode));
    }

    // 종목별 주문 내역: GET /api/orders/ticker/{ticker}
    @GetMapping("/api/orders/ticker/{ticker}")
    public ResponseEntity<List<OrderResponse>> getOrdersByTicker(@PathVariable String ticker) {
        return ResponseEntity.ok(orderService.getOrdersByTicker(ticker));
    }

    // 내부 엔드포인트: 거래 모드 변경 전파
    @PatchMapping("/internal/trading-mode")
    public ResponseEntity<Void> updateTradingMode(@RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        if (mode != null) {
            tradingModeStore.setMode(mode);
        }
        return ResponseEntity.ok().build();
    }
}
