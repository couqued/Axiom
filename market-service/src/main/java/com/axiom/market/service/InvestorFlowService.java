package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.InvestorFlowDto;
import com.axiom.market.entity.InvestorFlow;
import com.axiom.market.repository.InvestorFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestorFlowService {

    private static final DateTimeFormatter KIS_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long CACHE_TTL_MS = 5 * 60 * 1_000L; // 5분

    private final InvestorFlowRepository flowRepository;
    private final KisApiConfig kisApiConfig;
    private final KisTokenService kisTokenService;
    private final WebClient kisWebClient;

    // 당일 실시간 조회용 캐시: ticker → {dto, fetchedAt}
    private final ConcurrentHashMap<String, CacheEntry> todayCache = new ConcurrentHashMap<>();

    private record CacheEntry(InvestorFlowDto dto, long fetchedAt) {}

    // 캘린더 기준 KIS 단일 요청 최대 범위 (30 거래일 ≈ 40 캘린더일)
    private static final int KIS_CHUNK_DAYS = 40;

    // ── 과거 N일 데이터 ───────────────────────────────────────────────────────

    /**
     * ticker의 최근 days일치 투자자 데이터 반환.
     * DB에 없는 구간은 KIS API에서 가져와 저장 후 반환.
     */
    public List<InvestorFlowDto> getFlows(String ticker, int days) {
        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(days + 30L);

        List<InvestorFlow> dbFlows = flowRepository
                .findByTickerAndTradeDateBetweenOrderByTradeDateAsc(ticker, from, today);

        LocalDate fetchFrom = (dbFlows.isEmpty() || dbFlows.size() < days)
                ? from
                : dbFlows.get(dbFlows.size() - 1).getTradeDate().plusDays(1);

        if (!fetchFrom.isAfter(today)) {
            // KIS API 단일 요청 최대 30건 → 날짜 범위를 40일 단위로 chunking
            Set<LocalDate> existing = dbFlows.stream()
                    .map(InvestorFlow::getTradeDate)
                    .collect(Collectors.toSet());

            LocalDate chunkStart = fetchFrom;
            while (!chunkStart.isAfter(today)) {
                LocalDate chunkEnd = chunkStart.plusDays(KIS_CHUNK_DAYS - 1);
                if (chunkEnd.isAfter(today)) chunkEnd = today;

                List<InvestorFlow> fetched = fetchFromKis(ticker, chunkStart, chunkEnd);
                if (!fetched.isEmpty()) {
                    List<InvestorFlow> toSave = fetched.stream()
                            .filter(f -> !existing.contains(f.getTradeDate()))
                            .toList();
                    if (!toSave.isEmpty()) {
                        try {
                            flowRepository.saveAll(toSave);
                            toSave.forEach(f -> existing.add(f.getTradeDate()));
                        } catch (DataIntegrityViolationException e) {
                            log.debug("[Flow] 동시 삽입 무시 - ticker: {}, count: {}", ticker, toSave.size());
                            toSave.forEach(f -> existing.add(f.getTradeDate()));
                        }
                    }
                }
                chunkStart = chunkEnd.plusDays(1);
                if (!chunkStart.isAfter(today)) {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                }
            }

            dbFlows = flowRepository
                    .findByTickerAndTradeDateBetweenOrderByTradeDateAsc(ticker, from, today);
        }

        List<InvestorFlow> result = dbFlows.size() > days
                ? dbFlows.subList(dbFlows.size() - days, dbFlows.size())
                : dbFlows;

        return result.stream().map(InvestorFlowDto::from).toList();
    }

    // ── 스케줄러용 단건 수집 ─────────────────────────────────────────────────

    /**
     * 특정 날짜 하루치 투자자 데이터 수집 및 저장 (스케줄러에서 호출).
     */
    @Transactional
    public void collectFlow(String ticker, LocalDate date) {
        if (flowRepository.findByTickerAndTradeDate(ticker, date).isPresent()) {
            log.debug("[Flow] 이미 수집됨 - ticker: {}, date: {}", ticker, date);
            return;
        }
        List<InvestorFlow> fetched = fetchFromKis(ticker, date, date);
        if (!fetched.isEmpty()) {
            flowRepository.saveAll(fetched);
            log.info("[Flow] 수집 완료 - ticker: {}, date: {}", ticker, date);
        }
    }

    // ── 당일 실시간 조회 ─────────────────────────────────────────────────────

    /**
     * 오늘 장중 누적 투자자 데이터 반환 (5분 TTL 캐시).
     * 장 마감 후 호출 시 당일 전체 데이터 반환.
     * KIS API 실패 시 null 반환 → 피처가 0.0으로 채워질 뿐 전략 중단 없음.
     */
    public InvestorFlowDto getTodayFlow(String ticker) {
        long now = System.currentTimeMillis();
        CacheEntry entry = todayCache.get(ticker);
        if (entry != null && (now - entry.fetchedAt()) < CACHE_TTL_MS) {
            return entry.dto();
        }

        LocalDate today = LocalDate.now();
        List<InvestorFlow> fetched = fetchFromKis(ticker, today, today);
        if (fetched.isEmpty()) {
            log.debug("[Flow] 당일 실시간 조회 결과 없음 - ticker: {}", ticker);
            return null;
        }

        InvestorFlowDto dto = InvestorFlowDto.from(fetched.get(0));
        todayCache.put(ticker, new CacheEntry(dto, now));
        return dto;
    }

    // ── KIS API ─────────────────────────────────────────────────────────────

    private List<InvestorFlow> fetchFromKis(String ticker, LocalDate from, LocalDate to) {
        KisApiConfig.ModeConfig active = kisApiConfig.getReal();
        String token = kisTokenService.getAccessToken();

        log.info("[KIS] 투자자 매매 조회 - ticker: {}, {} ~ {}", ticker, from, to);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = kisWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-investor")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD",         ticker)
                            .queryParam("FID_INPUT_DATE_1",       from.format(KIS_DATE_FMT))
                            .queryParam("FID_INPUT_DATE_2",       to.format(KIS_DATE_FMT))
                            .queryParam("FID_PERIOD_DIV_CODE",    "D")
                            .build())
                    .header("authorization", "Bearer " + token)
                    .header("appkey",    active.getAppKey())
                    .header("appsecret", active.getAppSecret())
                    .header("tr_id",     "FHKST01010900")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                log.warn("[KIS] 투자자 응답 null - ticker: {}", ticker);
                return List.of();
            }

            // KIS FHKST01010900 응답은 output2가 아닌 output 키 사용
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> output =
                    (List<Map<String, Object>>) response.get("output");

            if (output == null || output.isEmpty()) {
                log.warn("[KIS] 투자자 output 없음 — rt_cd={}, msg={} - ticker: {}",
                        response.get("rt_cd"), response.get("msg1"), ticker);
                return List.of();
            }

            List<InvestorFlow> flows = new ArrayList<>();
            for (Map<String, Object> row : output) {
                String dateStr = String.valueOf(row.getOrDefault("stck_bsop_date", ""));
                if (dateStr.isBlank() || "null".equals(dateStr)) continue;

                // totalVol: acml_vol 없음 → 개인+외국인+기관 매수량 합으로 대체
                long totalVol = parseLong(row.get("prsn_shnu_vol"))
                              + parseLong(row.get("frgn_shnu_vol"))
                              + parseLong(row.get("orgn_shnu_vol"));

                flows.add(InvestorFlow.builder()
                        .ticker(ticker)
                        .tradeDate(LocalDate.parse(dateStr, KIS_DATE_FMT))
                        .frgnNtbyQty(parseLong(row.get("frgn_ntby_qty")))
                        .frgnNtbyTrPbmn(parseLong(row.get("frgn_ntby_tr_pbmn")))
                        .orgnNtbyQty(parseLong(row.get("orgn_ntby_qty")))
                        .orgnNtbyTrPbmn(parseLong(row.get("orgn_ntby_tr_pbmn")))
                        .totalVol(totalVol)
                        .build());
            }
            log.info("[KIS] 투자자 수집 완료 - ticker: {}, {}건", ticker, flows.size());
            return flows;

        } catch (Exception e) {
            log.error("[KIS] 투자자 조회 실패 - ticker: {}, error: {}", ticker, e.getMessage(), e);
            return List.of();
        }
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private long parseLong(Object val) {
        if (val == null) return 0L;
        String s = val.toString().replace(",", "").trim();
        if (s.isBlank()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
