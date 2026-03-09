package com.axiom.portfolio.service;

import com.axiom.portfolio.config.KisApiConfig;
import com.axiom.portfolio.fixture.PortfolioMockFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KisAccountApiServiceTest {

    @Mock private WebClient kisWebClient;
    @Mock private KisApiConfig kisApiConfig;
    @Mock private KisTokenService kisTokenService;

    private KisAccountApiService service;

    @BeforeEach
    void setUp() {
        when(kisApiConfig.isMock()).thenReturn(true);
        service = new KisAccountApiService(kisWebClient, kisApiConfig, kisTokenService);
    }

    @Test
    void getBalance_mock_containsAllExpectedKeys() {
        Map<String, Object> balance = service.getBalance();

        assertThat(balance).containsKeys(
                "totalBalance", "cashBalance", "stockBalance",
                "profitLoss", "profitLossRate", "mock");
    }

    @Test
    void getBalance_mock_flagIsTrue() {
        Map<String, Object> balance = service.getBalance();

        assertThat(balance.get("mock")).isEqualTo(true);
    }

    @Test
    void getBalance_mock_totalBalanceMatchesFixture() {
        Map<String, Object> balance = service.getBalance();

        BigDecimal total = (BigDecimal) balance.get("totalBalance");
        assertThat(total).isEqualByComparingTo(PortfolioMockFixture.TOTAL_BALANCE);
    }

    @Test
    void getBalance_mock_cashPlusStockEqualsTotalBalance() {
        Map<String, Object> balance = service.getBalance();

        BigDecimal cash  = (BigDecimal) balance.get("cashBalance");
        BigDecimal stock = (BigDecimal) balance.get("stockBalance");
        BigDecimal total = (BigDecimal) balance.get("totalBalance");

        assertThat(cash.add(stock)).isEqualByComparingTo(total);
    }

    @Test
    void getBalance_mock_allValuesArePositive() {
        Map<String, Object> balance = service.getBalance();

        assertThat((BigDecimal) balance.get("totalBalance")).isPositive();
        assertThat((BigDecimal) balance.get("cashBalance")).isPositive();
        assertThat((BigDecimal) balance.get("stockBalance")).isPositive();
        assertThat((BigDecimal) balance.get("profitLoss")).isPositive();
        assertThat((BigDecimal) balance.get("profitLossRate")).isPositive();
    }
}
