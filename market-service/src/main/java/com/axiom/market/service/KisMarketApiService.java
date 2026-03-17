package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.StockPriceDto;
import com.axiom.market.store.TradingModeStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class KisMarketApiService {

    private final WebClient kisWebClient;
    private final WebClient kisRealWebClient;
    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;
    private final TradingModeStore tradingModeStore;

    public KisMarketApiService(WebClient kisWebClient,
                               @Qualifier("kisRealWebClient") WebClient kisRealWebClient,
                               KisApiConfig kisApiConfig,
                               KisTokenService kisTokenService,
                               TradingModeStore tradingModeStore) {
        this.kisWebClient     = kisWebClient;
        this.kisRealWebClient = kisRealWebClient;
        this.kisApiConfig     = kisApiConfig;
        this.kisTokenService  = kisTokenService;
        this.tradingModeStore = tradingModeStore;
    }

    /** ticker → 한글 종목명 캐시 (서비스 재시작 전까지 유지, 종목명은 변하지 않음) */
    private final Map<String, String> stockNameCache = new ConcurrentHashMap<>();

    public StockPriceDto getCurrentPrice(String ticker) {
        if (tradingModeStore.isMock()) {
            return getMockPrice(ticker);
        }
        return getKisPrice(ticker);
    }

    /**
     * real 서버 캔들 API(FHKST03010100) output1에서 종목명 조회.
     * paper 서버는 hts_kor_isnm을 반환하지 않아 항상 real 서버를 사용한다.
     * 성공 시에만 stockNameCache에 저장하여 실패값(ticker 자체)이 캐시되지 않도록 한다.
     */
    @SuppressWarnings("unchecked")
    private String fetchStockNameFromRealApi(String ticker) {
        KisApiConfig.ModeConfig realConfig = kisApiConfig.getReal();
        String token = kisTokenService.getRealAccessToken();
        if (token == null) {
            log.warn("[KIS] real 토큰 없음 — 종목명 조회 스킵: {}", ticker);
            return ticker;
        }
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        try {
            Map<String, Object> response = kisRealWebClient.get()
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
                    .header("appkey",    realConfig.getAppKey())
                    .header("appsecret", realConfig.getAppSecret())
                    .header("tr_id",     "FHKST03010100")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            Map<String, String> output1 = (Map<String, String>) response.get("output1");
            if (output1 != null) {
                String name = output1.getOrDefault("hts_kor_isnm", "").strip();
                if (!name.isEmpty()) {
                    log.info("[KIS] 종목명 조회 완료 (real 캔들 API) — ticker: {}, name: {}", ticker, name);
                    return name;
                }
                log.warn("[KIS] real 캔들 API output1에 hts_kor_isnm 없음 — keys: {}", output1.keySet());
            }
        } catch (Exception e) {
            log.warn("[KIS] 종목명 조회 실패 (real 캔들 API) — ticker: {}, error: {}", ticker, e.getMessage());
        }
        return ticker;
    }

    // ── Mock ────────────────────────────────────────────────────────────────

    private StockPriceDto getMockPrice(String ticker) {
        Map<String, Object[]> mockData = Map.of(
            "005930", new Object[]{"삼성전자",   75000},
            "000660", new Object[]{"SK하이닉스", 185000},
            "035420", new Object[]{"NAVER",       220000},
            "051910", new Object[]{"LG화학",      320000},
            "006400", new Object[]{"삼성SDI",     280000}
        );

        Object[] data     = mockData.getOrDefault(ticker, new Object[]{"알 수 없는 종목", 50000});
        String stockName  = (String) data[0];
        int basePrice     = (int) data[1];
        Random rand       = new Random();
        int change        = (rand.nextInt(201) - 100) * 100;
        BigDecimal currentPrice  = BigDecimal.valueOf(basePrice + change);
        BigDecimal changeAmount  = BigDecimal.valueOf(change);
        BigDecimal changeRate    = changeAmount.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(basePrice), 2, java.math.RoundingMode.HALF_UP);

        log.info("[MOCK] 현재가 조회 - ticker: {}, price: {}", ticker, currentPrice);

        return StockPriceDto.builder()
                .ticker(ticker)
                .stockName(stockName)
                .currentPrice(currentPrice)
                .changeAmount(changeAmount)
                .changeRate(changeRate)
                .highPrice(currentPrice.add(BigDecimal.valueOf(500)))
                .lowPrice(currentPrice.subtract(BigDecimal.valueOf(500)))
                .openPrice(BigDecimal.valueOf(basePrice))
                .volume(rand.nextLong(5000000) + 100000)
                .fetchedAt(LocalDateTime.now())
                .mock(true)
                .marketWarnCode("00")
                .build();
    }

    // ── Paper / Real ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private StockPriceDto getKisPrice(String ticker) {
        boolean useReal = tradingModeStore.isReal();
        KisApiConfig.ModeConfig active = useReal ? kisApiConfig.getReal() : kisApiConfig.getPaper();
        String token = useReal ? kisTokenService.getRealAccessToken() : kisTokenService.getAccessToken();

        log.info("[KIS-{}] 현재가 조회 - ticker: {}", tradingModeStore.getMode().toUpperCase(), ticker);

        // real 모드면 kisRealWebClient(항상 real 서버), paper 모드면 kisWebClient 사용
        WebClient activeClient = useReal ? kisRealWebClient : kisWebClient;
        Map<String, Object> response = activeClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("fid_cond_mrkt_div_code", "J")
                        .queryParam("fid_input_iscd", ticker)
                        .build())
                .header("authorization", "Bearer " + token)
                .header("appkey",    active.getAppKey())
                .header("appsecret", active.getAppSecret())
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

        // 종목명: hts_kor_isnm (HTS 한글 종목명) 사용, 없으면 real 캔들 API로 fallback
        // 실패값(ticker 자체)은 캐시하지 않아 재시도 가능하도록 한다
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

        // 시장경보코드: "00"=정상, "01"=투자주의, "02"=투자경고, "03"=투자위험
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
                .mock(false)
                .marketWarnCode(marketWarnCode)
                .build();
    }
}
