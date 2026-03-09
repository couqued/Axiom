package com.axiom.market.service;

import com.axiom.market.config.KisApiConfig;
import com.axiom.market.dto.StockPriceDto;
import com.axiom.market.fixture.MarketMockFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KisMarketApiServiceTest {

    @Mock private WebClient kisWebClient;
    @Mock private WebClient kisRealWebClient;
    @Mock private KisApiConfig kisApiConfig;
    @Mock private KisTokenService kisTokenService;

    private KisMarketApiService service;

    @BeforeEach
    void setUp() {
        when(kisApiConfig.isMock()).thenReturn(true);
        service = new KisMarketApiService(kisWebClient, kisRealWebClient, kisApiConfig, kisTokenService);
    }

    @Test
    void getCurrentPrice_mock_samsung_returnsValidDto() {
        StockPriceDto dto = service.getCurrentPrice("005930");

        assertThat(dto.getTicker()).isEqualTo("005930");
        assertThat(dto.getStockName()).isEqualTo("삼성전자");
        assertThat(dto.isMock()).isTrue();
        assertThat(dto.getMarketWarnCode()).isEqualTo("00");
        assertThat(dto.getCurrentPrice()).isPositive();
        assertThat(dto.getHighPrice()).isGreaterThanOrEqualTo(dto.getCurrentPrice());
        assertThat(dto.getLowPrice()).isLessThanOrEqualTo(dto.getCurrentPrice());
        assertThat(dto.getVolume()).isPositive();
        assertThat(dto.getFetchedAt()).isNotNull();
    }

    @Test
    void getCurrentPrice_mock_unknownTicker_returnsDefault() {
        StockPriceDto dto = service.getCurrentPrice("999999");

        assertThat(dto.getTicker()).isEqualTo("999999");
        assertThat(dto.getStockName()).isEqualTo("알 수 없는 종목");
        assertThat(dto.isMock()).isTrue();
        assertThat(dto.getCurrentPrice()).isPositive();
    }

    @Test
    void getCurrentPrice_mock_isSafe_whenWarnCode00() {
        StockPriceDto dto = service.getCurrentPrice("005930");

        assertThat(dto.isSafe()).isTrue();
    }

    @Test
    void fixture_mockStockData_containsExpectedTickers() {
        Set<String> expectedTickers = Set.of("005930", "000660", "035420", "051910", "006400");
        assertThat(MarketMockFixture.MOCK_STOCK_DATA.keySet()).containsAll(expectedTickers);
    }

    @Test
    void fixture_createMockPrice_samsung_returnsCorrectName() {
        StockPriceDto dto = MarketMockFixture.createMockPrice("005930");

        assertThat(dto.getStockName()).isEqualTo("삼성전자");
        assertThat(dto.isMock()).isTrue();
        assertThat(dto.getMarketWarnCode()).isEqualTo("00");
    }
}
