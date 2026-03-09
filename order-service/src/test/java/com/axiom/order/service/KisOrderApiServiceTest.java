package com.axiom.order.service;

import com.axiom.order.config.KisApiConfig;
import com.axiom.order.fixture.OrderMockFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KisOrderApiServiceTest {

    @Mock private WebClient kisWebClient;
    @Mock private KisApiConfig kisApiConfig;
    @Mock private KisTokenService kisTokenService;

    private KisOrderApiService service;

    @BeforeEach
    void setUp() {
        when(kisApiConfig.isMock()).thenReturn(true);
        service = new KisOrderApiService(kisWebClient, kisApiConfig, kisTokenService);
    }

    @Test
    void placeOrder_mock_buy_returnsValidMockId() {
        String orderId = service.placeOrder("005930", "BUY", 10, new BigDecimal("75000"));

        assertThat(orderId).matches(OrderMockFixture.MOCK_ORDER_ID_PATTERN);
        assertThat(orderId).startsWith(OrderMockFixture.MOCK_ORDER_PREFIX);
    }

    @Test
    void placeOrder_mock_sell_returnsValidMockId() {
        String orderId = service.placeOrder("005930", "SELL", 5, new BigDecimal("76000"));

        assertThat(orderId).matches(OrderMockFixture.MOCK_ORDER_ID_PATTERN);
    }

    @Test
    void placeOrder_mock_differentCallsReturnDifferentIds() {
        String orderId1 = service.placeOrder("005930", "BUY", 1, new BigDecimal("75000"));
        String orderId2 = service.placeOrder("005930", "BUY", 1, new BigDecimal("75000"));

        assertThat(orderId1).isNotEqualTo(orderId2);
    }

    @Test
    void placeOrder_mock_idLength_is13() {
        String orderId = service.placeOrder("005930", "BUY", 1, new BigDecimal("75000"));

        // "MOCK-" (5) + 8자리 = 13자
        assertThat(orderId).hasSize(13);
    }
}
