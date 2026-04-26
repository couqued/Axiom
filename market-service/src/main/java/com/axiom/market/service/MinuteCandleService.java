package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.MinuteCandleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 분봉 조회 서비스 — KIS inquire-time-itemchartprice API 기반.
 *
 * <p>Entry Quality Multiplier 계산용. 30초 TTL 메모리 캐시로 동일 종목
 * 중복 호출 시 API 부하를 줄인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinuteCandleService {

    private static final DateTimeFormatter KIS_TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");
    private static final long CACHE_TTL_MS = 30_000L;
    private static final int MAX_COUNT = 30;

    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;
    private final WebClient kisWebClient;

    private final Map<String, CachedCandles> cache = new ConcurrentHashMap<>();

    private record CachedCandles(long cachedAt, List<MinuteCandleDto> candles) {}

    /**
     * ticker 의 최근 {@code count} 개 분봉을 오래된 순으로 반환.
     * @param count 1~{@value MAX_COUNT} (초과 시 {@value MAX_COUNT} 로 clamp)
     */
    public List<MinuteCandleDto> getMinuteCandles(String ticker, int count) {
        int requested = Math.max(1, Math.min(count, MAX_COUNT));

        CachedCandles cached = cache.get(ticker);
        long now = Instant.now().toEpochMilli();
        if (cached != null && now - cached.cachedAt < CACHE_TTL_MS) {
            return tail(cached.candles, requested);
        }

        List<MinuteCandleDto> fetched = fetchFromKis(ticker);
        if (fetched.isEmpty()) {
            if (cached != null) return tail(cached.candles, requested);
            return Collections.emptyList();
        }
        cache.put(ticker, new CachedCandles(now, fetched));
        return tail(fetched, requested);
    }

    private List<MinuteCandleDto> tail(List<MinuteCandleDto> list, int n) {
        if (list.size() <= n) return list;
        return list.subList(list.size() - n, list.size());
    }

    @SuppressWarnings("unchecked")
    private List<MinuteCandleDto> fetchFromKis(String ticker) {
        KisApiConfig.ModeConfig active = kisApiConfig.getReal();
        String token = kisTokenService.getAccessToken();

        // KIS 분봉 API는 기준시각(FID_INPUT_HOUR_1) 이전의 최근 30개 분봉을 반환.
        // 현재 시각 기준으로 조회 → 최근 30개.
        String nowHms = LocalTime.now().format(KIS_TIME_FMT);

        try {
            Map<String, Object> response = kisWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                            .queryParam("FID_ETC_CLS_CODE",       "")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD",         ticker)
                            .queryParam("FID_INPUT_HOUR_1",       nowHms)
                            .queryParam("FID_PW_DATA_INCU_YN",    "Y")
                            .build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey",    active.getAppKey())
                    .header("appsecret", active.getAppSecret())
                    .header("tr_id",     "FHKST03010200")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return List.of();
            List<Map<String, String>> output2 =
                    (List<Map<String, String>>) response.get("output2");
            if (output2 == null || output2.isEmpty()) return List.of();

            List<MinuteCandleDto> candles = new ArrayList<>();
            LocalDate today = LocalDate.now();
            for (Map<String, String> row : output2) {
                String timeStr = row.get("stck_cntg_hour");
                if (timeStr == null || timeStr.isBlank()) continue;
                LocalTime t;
                try {
                    t = LocalTime.parse(timeStr, KIS_TIME_FMT);
                } catch (Exception ex) {
                    continue;
                }
                candles.add(MinuteCandleDto.builder()
                        .time(LocalDateTime.of(today, t))
                        .openPrice(parseBd(row.get("stck_oprc")))
                        .highPrice(parseBd(row.get("stck_hgpr")))
                        .lowPrice(parseBd(row.get("stck_lwpr")))
                        .closePrice(parseBd(row.get("stck_prpr")))
                        .volume(parseLong(row.get("cntg_vol")))
                        .build());
            }
            // KIS는 최신→과거 순으로 반환. 오래된 순으로 뒤집어 반환.
            Collections.reverse(candles);
            return candles;
        } catch (Exception e) {
            log.warn("[KIS] 분봉 조회 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            return List.of();
        }
    }

    private BigDecimal parseBd(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(val); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private long parseLong(String val) {
        if (val == null || val.isBlank()) return 0L;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return 0L; }
    }
}
