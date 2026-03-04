package com.axiom.market.service;

import com.axiom.market.dto.StockUniverse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 코스피200 + 코스닥150 종목 유니버스를 로드하고 캐싱한다.
 * stock-universe.json에서 종목 코드를 읽어 strategy-service에 제공한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockScreenerService {

    private final ObjectMapper objectMapper;

    private volatile List<String> cachedTickers = List.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 매일 08:20(평일)에 유니버스를 재로드한다.
     * KRX 리밸런싱(6월/12월) 이후 stock-universe.json 업데이트 시 자동 반영.
     */
    @Scheduled(cron = "0 20 8 * * MON-FRI", zone = "Asia/Seoul")
    public void refresh() {
        try {
            ClassPathResource resource = new ClassPathResource("stock-universe.json");
            StockUniverse universe = objectMapper.readValue(resource.getInputStream(), StockUniverse.class);

            List<String> all = new ArrayList<>();
            if (universe.getKospi200() != null) all.addAll(universe.getKospi200());
            if (universe.getKosdaq150() != null) all.addAll(universe.getKosdaq150());

            this.cachedTickers = Collections.unmodifiableList(all);

            log.info("[Screener] 유니버스 로드 완료 — 코스피200({}개) + 코스닥150({}개) = 총 {}개 (lastUpdated: {})",
                    universe.getKospi200() != null ? universe.getKospi200().size() : 0,
                    universe.getKosdaq150() != null ? universe.getKosdaq150().size() : 0,
                    all.size(),
                    universe.getLastUpdated());
        } catch (Exception e) {
            log.error("[Screener] 유니버스 로드 실패 — {}. 이전 캐시 유지 ({}개)", e.getMessage(), cachedTickers.size());
        }
    }

    public List<String> getScreenedTickers() {
        return cachedTickers;
    }
}
