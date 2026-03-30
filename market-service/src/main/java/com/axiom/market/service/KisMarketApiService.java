package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.StockPriceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class KisMarketApiService {

    private final WebClient kisWebClient;
    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;

    public KisMarketApiService(WebClient kisWebClient,
                               KisApiConfig kisApiConfig,
                               KisTokenService kisTokenService) {
        this.kisWebClient = kisWebClient;
        this.kisApiConfig = kisApiConfig;
        this.kisTokenService = kisTokenService;
    }

    /** ticker → 한글 종목명 캐시 (서비스 재시작 전까지 유지, 종목명은 변하지 않음) */
    private final Map<String, String> stockNameCache = new ConcurrentHashMap<>();

    public StockPriceDto getCurrentPrice(String ticker) {
        return getKisPrice(ticker);
    }

    /**
     * real 서버 캔들 API(FHKST03010100) output1에서 종목명 조회.
     */
    @SuppressWarnings("unchecked")
    private String fetchStockNameFromRealApi(String ticker) {
        KisApiConfig.ModeConfig config = kisApiConfig.getReal();
        String token = kisTokenService.getAccessToken();
        if (token == null) {
            log.warn("[KIS] 토큰 없음 — 종목명 조회 스킵: {}", ticker);
            return ticker;
        }
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        try {
            Map<String, Object> response = kisWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", ticker)
                            .queryParam("FID_INPUT_DATE_1", today)
                            .queryParam("FID_INPUT_DATE_2", today)
                            .queryParam("FID_PERIOD_DIV_CODE", "D")
                            .queryParam("FID_ORG_ADJ_PRC", "0")
                            .build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey",    config.getAppKey())
                    .header("appsecret", config.getAppSecret())
                    .header("tr_id",     "FHKST03010100")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            Map<String, String> output1 = (Map<String, String>) response.get("output1");
            if (output1 != null) {
                String name = output1.getOrDefault("hts_kor_isnm", "").strip();
                if (!name.isEmpty()) {
                    log.info("[KIS] 종목명 조회 완료 (캔들 API) — ticker: {}, name: {}", ticker, name);
                    return name;
                }
                log.warn("[KIS] 캔들 API output1에 hts_kor_isnm 없음 — keys: {}", output1.keySet());
            }
        } catch (Exception e) {
            log.warn("[KIS] 종목명 조회 실패 (캔들 API) — ticker: {}, error: {}", ticker, e.getMessage());
        }
        return ticker;
    }

    // ── Real ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private StockPriceDto getKisPrice(String ticker) {
        KisApiConfig.ModeConfig config = kisApiConfig.getReal();
        String token = kisTokenService.getAccessToken();

        log.info("[KIS] 현재가 조회 - ticker: {}", ticker);

        Map<String, Object> response = kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("fid_cond_mrkt_div_code", "J")
                        .queryParam("fid_input_iscd", ticker)
                        .build())
                .header("authorization", "Bearer " + token)
                .header("appkey",    config.getAppKey())
                .header("appsecret", config.getAppSecret())
                .header("tr_id",     "FHKST01010100")
                .retrieve()
                .bodyToMono(Map.class)
                .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(1))
                        .doBeforeRetry(s -> log.warn("[KIS] 현재가 조회 재시도 - ticker: {}", ticker)))
                .block();

        Map<String, String> output = (Map<String, String>) response.get("output");

        BigDecimal currentPrice = new BigDecimal(output.get("stck_prpr"));
        BigDecimal changeAmount = new BigDecimal(output.get("prdy_vrss"));
        BigDecimal changeRate   = new BigDecimal(output.get("prdy_ctrt"));

        String stockName = output.getOrDefault("hts_kor_isnm", "").strip();
        if (stockName.isEmpty()) {
            stockName = stockNameCache.get(ticker);
            if (stockName == null) {
                stockName = fetchStockNameFromRealApi(ticker);
                if (!stockName.equals(ticker)) {
                    stockNameCache.put(ticker, stockName);
                }
            }
        }

        String marketWarnCode = output.getOrDefault("mrkt_warn_cls_code", "00");

        return StockPriceDto.builder()
                .ticker(ticker)
                .stockName(stockName)
                .currentPrice(currentPrice)
                .changeAmount(changeAmount)
                .changeRate(changeRate)
                .highPrice(new BigDecimal(output.get("stck_hgpr")))
                .lowPrice(new BigDecimal(output.get("stck_lwpr")))
                .openPrice(new BigDecimal(output.get("stck_oprc")))
                .volume(Long.parseLong(output.get("acml_vol")))
                .fetchedAt(LocalDateTime.now())
                .marketWarnCode(marketWarnCode)
                .build();
    }
}
