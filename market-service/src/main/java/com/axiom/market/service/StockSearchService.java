package com.axiom.market.service;

import com.axiom.market.dto.StockInfoDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockSearchService {

    // 주요 종목 mock 데이터 (KIS API 연동 전까지 사용)
    private static final List<StockInfoDto> STOCK_LIST = List.of(
        StockInfoDto.builder().ticker("005930").stockName("삼성전자").market("KOSPI").sector("반도체").build(),
        StockInfoDto.builder().ticker("000660").stockName("SK하이닉스").market("KOSPI").sector("반도체").build(),
        StockInfoDto.builder().ticker("035420").stockName("NAVER").market("KOSPI").sector("IT").build(),
        StockInfoDto.builder().ticker("035720").stockName("카카오").market("KOSPI").sector("IT").build(),
        StockInfoDto.builder().ticker("051910").stockName("LG화학").market("KOSPI").sector("화학").build(),
        StockInfoDto.builder().ticker("006400").stockName("삼성SDI").market("KOSPI").sector("배터리").build(),
        StockInfoDto.builder().ticker("028260").stockName("삼성물산").market("KOSPI").sector("건설").build(),
        StockInfoDto.builder().ticker("207940").stockName("삼성바이오로직스").market("KOSPI").sector("바이오").build(),
        StockInfoDto.builder().ticker("068270").stockName("셀트리온").market("KOSPI").sector("바이오").build(),
        StockInfoDto.builder().ticker("373220").stockName("LG에너지솔루션").market("KOSPI").sector("배터리").build(),
        StockInfoDto.builder().ticker("000270").stockName("기아").market("KOSPI").sector("자동차").build(),
        StockInfoDto.builder().ticker("005380").stockName("현대차").market("KOSPI").sector("자동차").build(),
        StockInfoDto.builder().ticker("247540").stockName("에코프로비엠").market("KOSDAQ").sector("배터리소재").build(),
        StockInfoDto.builder().ticker("086520").stockName("에코프로").market("KOSDAQ").sector("배터리소재").build()
    );

    public List<StockInfoDto> search(String query) {
        String lowerQuery = query.toLowerCase();
        List<StockInfoDto> result = STOCK_LIST.stream()
                .filter(s -> s.getStockName().contains(query)
                        || s.getTicker().contains(query)
                        || s.getSector().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
        log.info("[MOCK] 종목 검색 - query: {}, 결과: {}건", query, result.size());
        return result;
    }

    public StockInfoDto findByTicker(String ticker) {
        return STOCK_LIST.stream()
                .filter(s -> s.getTicker().equals(ticker))
                .findFirst()
                .orElse(StockInfoDto.builder()
                        .ticker(ticker)
                        .stockName("알 수 없는 종목")
                        .market("UNKNOWN")
                        .sector("-")
                        .build());
    }
}
