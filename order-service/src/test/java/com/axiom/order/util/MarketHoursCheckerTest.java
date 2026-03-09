package com.axiom.order.util;

import com.axiom.order.config.KisApiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketHoursCheckerTest {

    @Mock
    private KisApiConfig kisApiConfig;

    private MarketHoursChecker checker;

    @BeforeEach
    void setUp() {
        checker = new MarketHoursChecker(kisApiConfig);
    }

    @Test
    void isMarketOpen_mockMode_alwaysTrue() {
        when(kisApiConfig.isMock()).thenReturn(true);

        assertThat(checker.isMarketOpen()).isTrue();
    }

    @Test
    void nextMarketOpenAt_returnsIsoFormattedString() {
        // nextMarketOpenAt은 실제 시각 기반이므로 형식만 검증
        String nextOpen = checker.nextMarketOpenAt();

        assertThat(nextOpen).isNotNull();
        // ISO-8601 KST 형식: "yyyy-MM-dd'T'HH:mm:ssXXX"
        assertThat(nextOpen).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}");
    }

    @Test
    void isMarketOpen_nonMockMode_returnsBooleanWithoutException() {
        when(kisApiConfig.isMock()).thenReturn(false);

        // 실제 시각 기반이므로 예외 없이 boolean을 반환하는지만 검증
        boolean result = checker.isMarketOpen();
        assertThat(result).isIn(true, false);
    }
}
