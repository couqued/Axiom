package com.axiom.market.service;

import com.axiom.market.dto.StockInfoDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockSearchServiceTest {

    private StockSearchService service;

    @BeforeEach
    void setUp() {
        service = new StockSearchService();
    }

    @Test
    void search_byStockName_returnsMatch() {
        List<StockInfoDto> result = service.search("삼성전자");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTicker()).isEqualTo("005930");
        assertThat(result.get(0).getStockName()).isEqualTo("삼성전자");
    }

    @Test
    void search_byTicker_returnsMatch() {
        List<StockInfoDto> result = service.search("000660");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStockName()).isEqualTo("SK하이닉스");
    }

    @Test
    void search_bySector_returnsAllInSector() {
        List<StockInfoDto> result = service.search("반도체");

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result).extracting(StockInfoDto::getSector)
                .allMatch(s -> s.contains("반도체"));
    }

    @Test
    void search_noMatch_returnsEmpty() {
        List<StockInfoDto> result = service.search("존재하지않는종목XYZ");

        assertThat(result).isEmpty();
    }

    @Test
    void search_partialName_returnsMultiple() {
        List<StockInfoDto> result = service.search("삼성");

        // 삼성전자, 삼성SDI, 삼성물산, 삼성바이오로직스
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void findByTicker_known_returnsCorrectInfo() {
        StockInfoDto info = service.findByTicker("005930");

        assertThat(info.getTicker()).isEqualTo("005930");
        assertThat(info.getStockName()).isEqualTo("삼성전자");
        assertThat(info.getMarket()).isEqualTo("KOSPI");
        assertThat(info.getSector()).isEqualTo("반도체");
    }

    @Test
    void findByTicker_unknown_returnsDefaultInfo() {
        StockInfoDto info = service.findByTicker("999999");

        assertThat(info.getTicker()).isEqualTo("999999");
        assertThat(info.getStockName()).isEqualTo("알 수 없는 종목");
        assertThat(info.getMarket()).isEqualTo("UNKNOWN");
    }

    @Test
    void search_caseInsensitiveSector() {
        List<StockInfoDto> lower = service.search("it");
        List<StockInfoDto> upper = service.search("IT");

        assertThat(lower).hasSameSizeAs(upper);
        assertThat(lower).isNotEmpty();
    }
}
