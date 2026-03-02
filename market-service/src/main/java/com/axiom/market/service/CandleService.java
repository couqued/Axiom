package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.CandleDto;
import com.axiom.market.entity.DailyCandle;
import com.axiom.market.repository.DailyCandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleService {

    private static final DateTimeFormatter KIS_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DailyCandleRepository candleRepository;
    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;
    private final WebClient kisWebClient;

    /**
     * ticker의 최근 days일치 일봉 반환.
     * DB에 데이터가 없으면 KIS API에서 가져와 저장 후 반환.
     */
    @Transactional
    public List<CandleDto> getCandles(String ticker, int days) {
        if (kisApiConfig.isMock()) {
            return getMockCandles(ticker, days);
        }

        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(days + 30L); // 주말/공휴일 여유분 포함

        // DB에서 조회
        List<DailyCandle> dbCandles = candleRepository
                .findByTickerAndTradeDateBetweenOrderByTradeDateAsc(ticker, from, today);

        // 마지막 수집일 이후 데이터가 없으면 KIS에서 보완
        LocalDate fetchFrom = dbCandles.isEmpty()
                ? from
                : dbCandles.get(dbCandles.size() - 1).getTradeDate().plusDays(1);

        if (!fetchFrom.isAfter(today)) {
            List<DailyCandle> fetched = fetchFromKis(ticker, fetchFrom, today);
            if (!fetched.isEmpty()) {
                // 이미 DB에 있는 날짜는 제외하고 저장 (중복 방지)
                Set<LocalDate> existingDates = dbCandles.stream()
                        .map(DailyCandle::getTradeDate)
                        .collect(Collectors.toSet());
                List<DailyCandle> toSave = fetched.stream()
                        .filter(c -> !existingDates.contains(c.getTradeDate()))
                        .distinct()
                        .toList();
                if (!toSave.isEmpty()) {
                    candleRepository.saveAll(toSave);
                }
                dbCandles = candleRepository
                        .findByTickerAndTradeDateBetweenOrderByTradeDateAsc(ticker, from, today);
            }
        }

        // 최근 days개만 반환
        List<DailyCandle> result = dbCandles.size() > days
                ? dbCandles.subList(dbCandles.size() - days, dbCandles.size())
                : dbCandles;

        return result.stream().map(CandleDto::from).toList();
    }

    /**
     * 특정 날짜 하루치 일봉 수집 및 저장 (스케줄러에서 호출).
     */
    @Transactional
    public void collectCandle(String ticker, LocalDate date) {
        if (candleRepository.findByTickerAndTradeDate(ticker, date).isPresent()) {
            log.debug("[Candle] 이미 수집됨 - ticker: {}, date: {}", ticker, date);
            return;
        }
        List<DailyCandle> fetched = fetchFromKis(ticker, date, date);
        if (!fetched.isEmpty()) {
            candleRepository.saveAll(fetched);
            log.info("[Candle] 수집 완료 - ticker: {}, date: {}", ticker, date);
        }
    }

    // ── KIS API ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<DailyCandle> fetchFromKis(String ticker, LocalDate from, LocalDate to) {
        KisApiConfig.ModeConfig active = kisApiConfig.getActive();
        String token = kisTokenService.getAccessToken();

        log.info("[KIS] 일봉 조회 - ticker: {}, {} ~ {}", ticker, from, to);

        try {
            Map<String, Object> response = kisWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD",         ticker)
                            .queryParam("FID_INPUT_DATE_1",       from.format(KIS_DATE_FMT))
                            .queryParam("FID_INPUT_DATE_2",       to.format(KIS_DATE_FMT))
                            .queryParam("FID_PERIOD_DIV_CODE",    "D")
                            .queryParam("FID_ORG_ADJ_PRC",        "0")
                            .build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey",    active.getAppKey())
                    .header("appsecret", active.getAppSecret())
                    .header("tr_id",     "FHKST03010100")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<Map<String, String>> output2 =
                    (List<Map<String, String>>) response.get("output2");

            if (output2 == null || output2.isEmpty()) {
                log.warn("[KIS] 일봉 데이터 없음 - ticker: {}", ticker);
                return List.of();
            }

            List<DailyCandle> candles = new ArrayList<>();
            for (Map<String, String> row : output2) {
                String dateStr = row.get("stck_bsop_date");
                if (dateStr == null || dateStr.isBlank()) continue;
                candles.add(DailyCandle.builder()
                        .ticker(ticker)
                        .tradeDate(LocalDate.parse(dateStr, KIS_DATE_FMT))
                        .openPrice(parseBd(row.get("stck_oprc")))
                        .highPrice(parseBd(row.get("stck_hgpr")))
                        .lowPrice(parseBd(row.get("stck_lwpr")))
                        .closePrice(parseBd(row.get("stck_clpr")))
                        .volume(parseLong(row.get("acml_vol")))
                        .build());
            }
            return candles;

        } catch (Exception e) {
            log.error("[KIS] 일봉 조회 실패 - ticker: {}, error: {}", ticker, e.getMessage());
            return List.of();
        }
    }

    // ── Mock ─────────────────────────────────────────────────────────────────

    private List<CandleDto> getMockCandles(String ticker, int days) {
        Map<String, Integer> basePrices = Map.of(
                "005930", 75000, "000660", 185000, "035420", 220000,
                "051910", 320000, "006400", 280000
        );
        int base = basePrices.getOrDefault(ticker, 50000);
        Random rand = new Random(ticker.hashCode());
        List<CandleDto> result = new ArrayList<>();
        LocalDate date = LocalDate.now().minusDays(days);
        int price = base;

        for (int i = 0; i < days; i++) {
            date = date.plusDays(1);
            if (date.getDayOfWeek().getValue() >= 6) { i--; continue; } // 주말 제외
            int change = (rand.nextInt(41) - 20) * (base / 1000);
            price = Math.max(base / 2, price + change);
            int high  = price + rand.nextInt(base / 100 + 1);
            int low   = price - rand.nextInt(base / 100 + 1);
            int open  = low + rand.nextInt(Math.max(1, high - low));
            result.add(CandleDto.builder()
                    .tradeDate(date)
                    .openPrice(BigDecimal.valueOf(open))
                    .highPrice(BigDecimal.valueOf(high))
                    .lowPrice(BigDecimal.valueOf(low))
                    .closePrice(BigDecimal.valueOf(price))
                    .volume((long) (rand.nextInt(5000000) + 100000))
                    .build());
        }
        log.info("[MOCK] 일봉 조회 - ticker: {}, {}일", ticker, result.size());
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
