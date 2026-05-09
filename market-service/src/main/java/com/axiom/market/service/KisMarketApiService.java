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
     * 워치리스트 자동 평가용 마켓 스냅샷.
     * - mrkt_warn_cls_code: 시장경보 ("00" 정상, "01" 투자주의, "02" 투자경고, "03" 투자위험)
     * - iscd_stat_cls_code: 종목상태 ("00" 정상, "55" 거래정지, "65" 정리매매, "57" 관리종목 등)
     * - hts_avls: 시가총액 (단위: 억원, KIS 응답 그대로 — BigDecimal로 보관)
     * - acml_tr_pbmn: 누적거래대금 (단위: 원, 당일 누적)
     * 실패 시 null 반환. 호출 측에서 null 체크 후 스킵.
     */
    public MarketSnapshot getMarketSnapshot(String ticker) {
        KisApiConfig.ModeConfig config = kisApiConfig.getReal();
        String token = kisTokenService.getAccessToken();
        if (token == null) {
            log.warn("[KIS] 토큰 없음 — snapshot 조회 스킵: {}", ticker);
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
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
                    .timeout(Duration.ofSeconds(5))
                    .block();
            @SuppressWarnings("unchecked")
            Map<String, String> output = response != null ? (Map<String, String>) response.get("output") : null;
            if (output == null) return null;

            String warn = output.getOrDefault("mrkt_warn_cls_code", "00");
            String stat = output.getOrDefault("iscd_stat_cls_code", "00");
            BigDecimal marketCapEok = parseDecimal(output.get("hts_avls"));   // 억원 단위
            BigDecimal turnoverKrw  = parseDecimal(output.get("acml_tr_pbmn")); // 원 단위
            String name = output.getOrDefault("hts_kor_isnm", "").strip();
            return new MarketSnapshot(ticker, name, warn, stat, marketCapEok, turnoverKrw);
        } catch (Exception e) {
            log.warn("[KIS] snapshot 조회 실패 — ticker: {}, error: {}", ticker, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 지수 구성종목 조회 (Phase 3 — 분기 KRX 정기변경 반영용).
     *
     * ⚠️ KIS index-component endpoint는 운영 미검증 상태.
     * 첫 cron 발화 1주 전 수동 smoke test 통과 후 dry-run=false 전환할 것.
     * 호출 측은 결과가 비어있거나 예상보다 작으면 (e.g. < 100) abort 하고 DB write 금지.
     *
     * @param indexCode KIS 지수 코드 — KOSPI200: "1028", KOSDAQ150: "2002" (검증 필요)
     * @return 구성종목 ticker → 종목명 맵. 실패 시 null.
     */
    public Map<String, String> fetchIndexConstituents(String indexCode) {
        KisApiConfig.ModeConfig config = kisApiConfig.getReal();
        String token = kisTokenService.getAccessToken();
        if (token == null) {
            log.warn("[KIS] 토큰 없음 — 지수 구성종목 조회 스킵: {}", indexCode);
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = kisWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-index-category-price")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                            .queryParam("FID_INPUT_ISCD", indexCode)
                            .queryParam("FID_COND_SCR_DIV_CODE", "20214")
                            .queryParam("FID_MRKT_CLS_CODE", "K")
                            .queryParam("FID_BLNG_CLS_CODE", "0")
                            .queryParam("FID_INPUT_PRICE_1", "")
                            .queryParam("FID_INPUT_PRICE_2", "")
                            .queryParam("FID_VOL_CNT", "")
                            .queryParam("FID_INPUT_DATE_1", "")
                            .build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey",    config.getAppKey())
                    .header("appsecret", config.getAppSecret())
                    .header("tr_id",     "FHPUP02140000")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (response == null) return null;
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, String>> output =
                    (java.util.List<Map<String, String>>) response.get("output");
            if (output == null || output.isEmpty()) {
                log.warn("[KIS] 지수 구성종목 응답 비어있음 — indexCode: {}", indexCode);
                return null;
            }
            Map<String, String> result = new java.util.HashMap<>();
            for (Map<String, String> row : output) {
                String ticker = row.getOrDefault("mksc_shrn_iscd",
                        row.getOrDefault("stck_shrn_iscd",
                        row.getOrDefault("shrn_iscd", "")));
                String name = row.getOrDefault("hts_kor_isnm",
                        row.getOrDefault("kor_isnm", ""));
                if (ticker != null && !ticker.isBlank()) {
                    result.put(ticker.trim(), name != null ? name.trim() : "");
                }
            }
            log.info("[KIS] 지수 구성종목 조회 완료 — indexCode: {}, count: {}", indexCode, result.size());
            return result;
        } catch (Exception e) {
            log.error("[KIS] 지수 구성종목 조회 실패 — indexCode: {}, error: {}", indexCode, e.getMessage());
            return null;
        }
    }

    /**
     * @param marketCap   시가총액 (억원 단위, KIS hts_avls)
     * @param turnoverKrw 당일 누적 거래대금 (원 단위, KIS acml_tr_pbmn)
     */
    public record MarketSnapshot(
            String ticker,
            String stockName,
            String marketWarnCode,
            String stockStatusCode,
            BigDecimal marketCap,
            BigDecimal turnoverKrw
    ) {
        public boolean isHalted() {
            return "55".equals(stockStatusCode) || "57".equals(stockStatusCode)
                    || "65".equals(stockStatusCode);
        }
        public boolean isWarned() {
            return marketWarnCode != null && !"00".equals(marketWarnCode);
        }
        /** 시총을 원 단위로 반환 (KIS hts_avls는 억원 단위) */
        public BigDecimal marketCapKrw() {
            return marketCap != null ? marketCap.multiply(new BigDecimal("100000000")) : BigDecimal.ZERO;
        }
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
