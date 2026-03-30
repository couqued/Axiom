package com.axiom.order.service;

import com.axiom.order.config.KisApiConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisOrderApiService {

    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;

    private WebClient realClient;

    @PostConstruct
    void initClients() {
        realClient = WebClient.builder().baseUrl(kisApiConfig.getReal().getBaseUrl()).build();
    }

    public String placeOrder(String ticker, String orderType, int quantity, BigDecimal price) {
        return placeKisOrder(ticker, orderType, quantity, price);
    }

    // ── Real ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String placeKisOrder(String ticker, String orderType, int quantity, BigDecimal price) {
        KisApiConfig.ModeConfig config = kisApiConfig.getReal();
        String token = kisTokenService.getAccessToken();

        boolean isBuy = "BUY".equals(orderType);
        String trId = isBuy ? "TTTC0802U" : "TTTC0801U";

        String[] accountParts = config.getAccountNo().split("-");

        boolean isMarketOrder = price == null || price.compareTo(BigDecimal.ZERO) == 0;
        BigDecimal adjustedPrice = isMarketOrder ? price : roundToTickUnit(price);
        Map<String, String> body = Map.of(
                "CANO",         accountParts[0],
                "ACNT_PRDT_CD", accountParts[1],
                "PDNO",         ticker,
                "ORD_DVSN",     isMarketOrder ? "01" : "00",
                "ORD_QTY",      String.valueOf(quantity),
                "ORD_UNPR",     isMarketOrder ? "0" : adjustedPrice.toPlainString()
        );

        log.info("[KIS] 주문 요청 - ticker: {}, type: {}, qty: {}, price: {}, tr_id: {}",
                ticker, orderType, quantity, price, trId);

        String hashKey = getHashKey(realClient, config, body);

        WebClient.RequestBodySpec req = realClient.post()
                .uri("/uapi/domestic-stock/v1/trading/order-cash")
                .header("authorization", "Bearer " + token)
                .header("appkey",    config.getAppKey())
                .header("appsecret", config.getAppSecret())
                .header("tr_id",     trId)
                .header("custtype",  "P");
        if (hashKey != null) {
            req = req.header("hashkey", hashKey);
        }

        Map<String, Object> response = null;
        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            final int attemptNo = attempt;
            try {
                response = req
                        .bodyValue(body)
                        .retrieve()
                        .onStatus(
                                status -> !status.is2xxSuccessful(),
                                clientResponse -> clientResponse.bodyToMono(String.class)
                                        .flatMap(errBody -> {
                                            log.error("[KIS] HTTP 오류 응답 (시도 {}/2) — status: {}, body: {}",
                                                    attemptNo,
                                                    clientResponse.statusCode().value(), errBody);
                                            return Mono.error(new RuntimeException(
                                                    "KIS HTTP " + clientResponse.statusCode().value() + ": " + errBody));
                                        })
                        )
                        .bodyToMono(Map.class)
                        .block();
                break;
            } catch (Exception e) {
                lastException = e;
                if (attempt < 2) {
                    log.warn("[KIS] 주문 실패 — {}초 후 재시도 (1/2): {}", 3, e.getMessage());
                    try { Thread.sleep(3_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    boolean isTokenError = msg.contains("EGW00123") || msg.contains("EGW00121")
                            || msg.contains("만료된 token") || msg.contains("유효하지 않은 token");
                    String newToken = isTokenError
                            ? kisTokenService.forceRefreshAndGetToken()
                            : kisTokenService.getAccessToken();
                    req = req.header("authorization", "Bearer " + newToken);
                }
            }
        }
        if (response == null) throw new RuntimeException(lastException.getMessage(), lastException);

        log.info("[KIS] 주문 응답: {}", response);

        String rtCd = (String) response.get("rt_cd");
        if (!"0".equals(rtCd)) {
            String msgCd = (String) response.get("msg_cd");
            String msg1  = (String) response.get("msg1");
            String msg2  = (String) response.get("msg2");
            log.error("[KIS] 주문 거부 전체 응답: {}", response);
            throw new RuntimeException(
                    "KIS 주문 거부: [" + rtCd + "/" + msgCd + "] " + msg1
                            + (msg2 != null ? " / " + msg2 : ""));
        }

        Map<String, String> output = (Map<String, String>) response.get("output");
        String orderId = output.get("ODNO");
        log.info("[KIS] 주문 완료 - orderId: {}", orderId);
        return orderId;
    }

    @SuppressWarnings("unchecked")
    private String getHashKey(WebClient wc, KisApiConfig.ModeConfig config, Map<String, String> body) {
        try {
            Map<String, Object> response = wc.post()
                    .uri("/uapi/hashkey")
                    .header("appkey",    config.getAppKey())
                    .header("appsecret", config.getAppSecret())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return response != null ? (String) response.get("HASH") : null;
        } catch (Exception e) {
            log.warn("[KIS] hashkey 조회 실패 — hashkey 없이 요청 진행: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal roundToTickUnit(BigDecimal price) {
        long p = price.longValue();
        long unit;
        if (p < 2_000)        unit = 1;
        else if (p < 5_000)   unit = 5;
        else if (p < 20_000)  unit = 10;
        else if (p < 50_000)  unit = 50;
        else if (p < 200_000) unit = 100;
        else if (p < 500_000) unit = 500;
        else                  unit = 1_000;
        return BigDecimal.valueOf((p / unit) * unit);
    }
}
