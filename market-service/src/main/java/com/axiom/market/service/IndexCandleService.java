package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.CandleDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 코스피(0001), 코스닥(1001) 등 시장 지수의 일봉 데이터를 제공한다.
 * 시장 상태(상승장/횡보장) 판별에 사용된다.
 * <p>mode 와 무관하게 지수는 항상 real 서버에서 조회한다 (paper 서버가 지수 데이터를 잘못 반환).
 */
@Slf4j
@Service
public class IndexCandleService {

    private static final DateTimeFormatter KIS_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;
    private final WebClient kisRealWebClient;

    public IndexCandleService(KisApiConfig kisApiConfig,
                              KisTokenService kisTokenService,
                              @Qualifier("kisRealWebClient") WebClient kisRealWebClient) {
        this.kisApiConfig    = kisApiConfig;
        this.kisTokenService = kisTokenService;
        this.kisRealWebClient = kisRealWebClient;
    }

    /**
     * 지수 코드의 최근 days일치 일봉 반환.
     *
     * @param indexCode 지수 코드 ("0001" = 코스피, "1001" = 코스닥)
     * @param days      조회 일수
     */
    public List<CandleDto> getIndexCandles(String indexCode, int days) {
        if (kisApiConfig.isMock()) {
            return getMockIndexCandles(indexCode, days);
        }
        return fetchIndexFromKis(indexCode, days);
    }

    // ── KIS API ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<CandleDto> fetchIndexFromKis(String indexCode, int days) {
        // 지수는 항상 real 서버 사용 (paper 서버가 지수 데이터를 잘못 반환)
        KisApiConfig.ModeConfig realConfig = kisApiConfig.getReal();
        String token = kisTokenService.getRealAccessToken();

        if (token == null) {
            log.warn("[KIS] real 토큰 발급 실패 — 지수 mock 데이터로 폴백 (indexCode: {})", indexCode);
            return getMockIndexCandles(indexCode, days);
        }

        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(days + 10L);

        log.info("[KIS-REAL] 지수 일봉 조회 - indexCode: {}, {} ~ {}", indexCode, from, today);

        try {
            Map<String, Object> response = kisRealWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-index-daily-price")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "U")   // U = 업종/지수
                            .queryParam("FID_INPUT_ISCD",         indexCode)
                            .queryParam("FID_INPUT_DATE_1",       from.format(KIS_DATE_FMT))
                            .queryParam("FID_INPUT_DATE_2",       today.format(KIS_DATE_FMT))
                            .queryParam("FID_PERIOD_DIV_CODE",    "D")
                            .build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey",    realConfig.getAppKey())
                    .header("appsecret", realConfig.getAppSecret())
                    .header("tr_id",     "FHKUP03500100")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<Map<String, String>> output2 =
                    (List<Map<String, String>>) response.get("output2");

            if (output2 == null || output2.isEmpty()) {
                log.warn("[KIS] 지수 일봉 데이터 없음 - indexCode: {}, msg: {}. Mock 데이터로 폴백.",
                        indexCode, response.get("msg1"));
                return getMockIndexCandles(indexCode, days);
            }

            List<CandleDto> candles = new ArrayList<>();
            for (Map<String, String> row : output2) {
                String dateStr = row.get("stck_bsop_date");
                if (dateStr == null || dateStr.isBlank()) continue;
                candles.add(CandleDto.builder()
                        .tradeDate(LocalDate.parse(dateStr, KIS_DATE_FMT))
                        .openPrice(parseBd(row.get("bstp_nmix_oprc")))
                        .highPrice(parseBd(row.get("bstp_nmix_hgpr")))
                        .lowPrice(parseBd(row.get("bstp_nmix_lwpr")))
                        .closePrice(parseBd(row.get("bstp_nmix_prpr")))
                        .volume(parseLong(row.get("acml_vol")))
                        .build());
            }

            // KIS API는 최신→과거 순으로 반환 → 오름차순 정렬(과거→최신) 후 최근 days개 반환
            candles.sort(Comparator.comparing(CandleDto::getTradeDate));
            return candles.size() > days ? candles.subList(candles.size() - days, candles.size()) : candles;

        } catch (Exception e) {
            log.error("[KIS] 지수 일봉 조회 실패 - indexCode: {}, error: {}", indexCode, e.getMessage());
            return List.of();
        }
    }

    // ── Mock ─────────────────────────────────────────────────────────────────

    /**
     * Mock 지수 데이터 생성.
     * 기본값: 상승 추세(BULLISH 시뮬레이션 — 마지막 종가 > MA20).
     */
    private List<CandleDto> getMockIndexCandles(String indexCode, int days) {
        // 코스피 기준 2500~2700 범위, 코스닥 기준 800~900 범위
        Map<String, Integer> basePrices = Map.of("0001", 2600, "1001", 850);
        int base = basePrices.getOrDefault(indexCode, 2600);

        Random rand = new Random(indexCode.hashCode() + 999L);
        List<CandleDto> result = new ArrayList<>();
        LocalDate date = LocalDate.now().minusDays(days + 10L);

        // 완만한 상승 추세로 생성 (BULLISH 시뮬레이션)
        double price = base * 0.95; // 시작을 약간 낮게 → 이후 상승 → MA20 위로 올라옴
        for (int i = 0; result.size() < days; i++) {
            date = date.plusDays(1);
            if (date.getDayOfWeek().getValue() >= 6) continue; // 주말 제외

            // 완만한 상승 편향
            double change = (rand.nextDouble() - 0.45) * base * 0.01;
            price = Math.max(base * 0.80, price + change);

            double high  = price + rand.nextDouble() * base * 0.005;
            double low   = price - rand.nextDouble() * base * 0.005;
            double open  = low + rand.nextDouble() * (high - low);

            result.add(CandleDto.builder()
                    .tradeDate(date)
                    .openPrice(BigDecimal.valueOf(Math.round(open)))
                    .highPrice(BigDecimal.valueOf(Math.round(high)))
                    .lowPrice(BigDecimal.valueOf(Math.round(low)))
                    .closePrice(BigDecimal.valueOf(Math.round(price)))
                    .volume(0L)
                    .build());
        }

        log.info("[MOCK] 지수 일봉 조회 - indexCode: {}, {}일", indexCode, result.size());
        return result;
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private BigDecimal parseBd(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(val); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private long parseLong(String val) {
        if (val == null || val.isBlank()) return 0L;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return 0L; }
    }
}
