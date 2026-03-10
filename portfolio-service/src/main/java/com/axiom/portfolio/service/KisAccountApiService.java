package com.axiom.portfolio.service;

import com.axiom.portfolio.config.KisApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisAccountApiService {

    private final WebClient kisWebClient;
    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;

    public Map<String, Object> getBalance() {
        if (kisApiConfig.isMock()) {
            return getMockBalance();
        }
        try {
            return getKisBalance();
        } catch (Exception e) {
            log.error("[KIS-{}] 잔고 조회 실패 — fallback 반환: {}", kisApiConfig.getMode().toUpperCase(), e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("totalBalance",   java.math.BigDecimal.ZERO);
            fallback.put("cashBalance",    java.math.BigDecimal.ZERO);
            fallback.put("stockBalance",   java.math.BigDecimal.ZERO);
            fallback.put("profitLoss",     java.math.BigDecimal.ZERO);
            fallback.put("profitLossRate", java.math.BigDecimal.ZERO);
            fallback.put("mock", false);
            fallback.put("error", e.getMessage());
            return fallback;
        }
    }

    // ── Mock ────────────────────────────────────────────────────────────────

    private Map<String, Object> getMockBalance() {
        Map<String, Object> balance = new HashMap<>();
        balance.put("totalBalance",    new BigDecimal("10000000"));
        balance.put("cashBalance",     new BigDecimal("5000000"));
        balance.put("stockBalance",    new BigDecimal("5000000"));
        balance.put("profitLoss",      new BigDecimal("250000"));
        balance.put("profitLossRate",  new BigDecimal("5.26"));
        balance.put("mock", true);
        log.info("[MOCK] 계좌 잔고 조회");
        return balance;
    }

    // ── Paper / Real ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> getKisBalance() {
        KisApiConfig.ModeConfig active = kisApiConfig.getActive();
        String token = kisTokenService.getAccessToken();
        String trId  = kisApiConfig.isPaper() ? "VTTC8434R" : "TTTC8434R";
        String[] accountParts = active.getAccountNo().split("-");

        log.info("[KIS-{}] 잔고 조회 요청", kisApiConfig.getMode().toUpperCase());

        Map<String, Object> response = kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/trading/inquire-balance")
                        .queryParam("CANO",                  accountParts[0])
                        .queryParam("ACNT_PRDT_CD",          accountParts[1])
                        .queryParam("AFHR_FLPR_YN",          "N")
                        .queryParam("OFL_YN",                "")
                        .queryParam("INQR_DVSN",             "02")
                        .queryParam("UNPR_DVSN",             "01")
                        .queryParam("FUND_STTL_ICLD_YN",     "N")
                        .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                        .queryParam("PRCS_DVSN",             "01")
                        .queryParam("CTX_AREA_FK100",        "")
                        .queryParam("CTX_AREA_NK100",        "")
                        .build())
                .header("authorization", "Bearer " + token)
                .header("appkey",    active.getAppKey())
                .header("appsecret", active.getAppSecret())
                .header("tr_id",     trId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("[KIS-{}] HTTP {} 오류 응답: {}", kisApiConfig.getMode().toUpperCase(),
                                            clientResponse.statusCode().value(), body);
                                    return Mono.error(new RuntimeException(
                                            "KIS API HTTP " + clientResponse.statusCode().value() + ": " + body));
                                }))
                .bodyToMono(Map.class)
                .block();

        if (response == null) {
            throw new RuntimeException("KIS 잔고 조회 실패: 응답 없음");
        }

        // KIS API 오류 코드 체크
        String rtCd = (String) response.get("rt_cd");
        if (!"0".equals(rtCd)) {
            String msg = (String) response.getOrDefault("msg1", "알 수 없는 오류");
            log.error("[KIS-{}] 잔고 조회 오류 - rt_cd: {}, msg: {}", kisApiConfig.getMode().toUpperCase(), rtCd, msg);
            throw new RuntimeException("KIS 잔고 조회 실패: " + msg);
        }

        List<Map<String, String>> output2 = (List<Map<String, String>>) response.get("output2");
        if (output2 == null || output2.isEmpty()) {
            log.warn("[KIS-{}] output2 없음 — 빈 잔고 반환", kisApiConfig.getMode().toUpperCase());
            Map<String, Object> empty = new HashMap<>();
            empty.put("totalBalance",   BigDecimal.ZERO);
            empty.put("cashBalance",    BigDecimal.ZERO);
            empty.put("stockBalance",   BigDecimal.ZERO);
            empty.put("profitLoss",     BigDecimal.ZERO);
            empty.put("profitLossRate", BigDecimal.ZERO);
            empty.put("mock", false);
            return empty;
        }
        Map<String, String> summary = output2.get(0);

        Map<String, Object> result = new HashMap<>();
        BigDecimal profitLoss     = parseBigDecimal(summary.get("evlu_pfls_smtl_amt"));
        BigDecimal profitLossRate = parseBigDecimal(summary.get("evlu_erng_rt"));
        BigDecimal purchaseAmt    = parseBigDecimal(summary.get("pchs_amt_smtl_amt"));

        // evlu_erng_rt가 0이고 매입금액이 존재할 때 직접 계산
        if (profitLossRate.compareTo(BigDecimal.ZERO) == 0
                && purchaseAmt.compareTo(BigDecimal.ZERO) != 0) {
            profitLossRate = profitLoss
                    .divide(purchaseAmt, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }

        result.put("totalBalance",   parseBigDecimal(summary.get("tot_evlu_amt")));
        result.put("cashBalance",    parseBigDecimal(summary.get("dnca_tot_amt")));
        result.put("stockBalance",   parseBigDecimal(summary.get("scts_evlu_amt")));
        result.put("profitLoss",     profitLoss);
        result.put("profitLossRate", profitLossRate);
        result.put("mock", false);
        log.info("[KIS-{}] 잔고 조회 완료 - 총평가: {}", kisApiConfig.getMode().toUpperCase(), result.get("totalBalance"));
        return result;
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
