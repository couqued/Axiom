package com.axiom.portfolio.service;

import com.axiom.portfolio.config.KisApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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
        return getKisBalance();
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
                .bodyToMono(Map.class)
                .block();

        List<Map<String, String>> output2 = (List<Map<String, String>>) response.get("output2");
        Map<String, String> summary = output2.get(0);

        Map<String, Object> result = new HashMap<>();
        result.put("totalBalance",   parseBigDecimal(summary.get("tot_evlu_amt")));
        result.put("cashBalance",    parseBigDecimal(summary.get("dnca_tot_amt")));
        result.put("stockBalance",   parseBigDecimal(summary.get("scts_evlu_amt")));
        result.put("profitLoss",     parseBigDecimal(summary.get("evlu_pfls_smtl_amt")));
        result.put("profitLossRate", parseBigDecimal(summary.get("evlu_erng_rt")));
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
