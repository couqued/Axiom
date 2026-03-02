package com.axiom.order.service;

import com.axiom.order.config.KisApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisOrderApiService {

    private final WebClient kisWebClient;
    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;

    public String placeOrder(String ticker, String orderType, int quantity, BigDecimal price) {
        if (kisApiConfig.isMock()) {
            return placeMockOrder(ticker, orderType, quantity, price);
        }
        return placeKisOrder(ticker, orderType, quantity, price);
    }

    // ── Mock ────────────────────────────────────────────────────────────────

    private String placeMockOrder(String ticker, String orderType, int quantity, BigDecimal price) {
        String mockOrderId = "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[MOCK] 주문 접수 - ticker: {}, type: {}, qty: {}, price: {}, orderId: {}",
                ticker, orderType, quantity, price, mockOrderId);
        return mockOrderId;
    }

    // ── Paper / Real ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String placeKisOrder(String ticker, String orderType, int quantity, BigDecimal price) {
        KisApiConfig.ModeConfig active = kisApiConfig.getActive();
        String token = kisTokenService.getAccessToken();

        boolean isBuy = "BUY".equals(orderType);
        String trId = kisApiConfig.isPaper()
                ? (isBuy ? "VTTC0802U" : "VTTC0801U")
                : (isBuy ? "TTTC0802U" : "TTTC0801U");

        String[] accountParts = active.getAccountNo().split("-");

        Map<String, String> body = Map.of(
                "CANO",         accountParts[0],
                "ACNT_PRDT_CD", accountParts[1],
                "PDNO",         ticker,
                "ORD_DVSN",     "00",
                "ORD_QTY",      String.valueOf(quantity),
                "ORD_UNPR",     price.toPlainString()
        );

        log.info("[KIS-{}] 주문 요청 - ticker: {}, type: {}, qty: {}, price: {}, tr_id: {}",
                kisApiConfig.getMode().toUpperCase(), ticker, orderType, quantity, price, trId);

        Map<String, Object> response = kisWebClient.post()
                .uri("/uapi/domestic-stock/v1/trading/order-cash")
                .header("authorization", "Bearer " + token)
                .header("appkey",        active.getAppKey())
                .header("appsecret",     active.getAppSecret())
                .header("tr_id",         trId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        log.info("[KIS-{}] 주문 응답: {}", kisApiConfig.getMode().toUpperCase(), response);

        String rtCd = (String) response.get("rt_cd");
        if (!"0".equals(rtCd)) {
            String msg = (String) response.get("msg1");
            throw new RuntimeException("KIS 주문 거부: [" + rtCd + "] " + msg);
        }

        Map<String, String> output = (Map<String, String>) response.get("output");
        String orderId = output.get("ODNO");
        log.info("[KIS-{}] 주문 완료 - orderId: {}", kisApiConfig.getMode().toUpperCase(), orderId);
        return orderId;
    }
}
