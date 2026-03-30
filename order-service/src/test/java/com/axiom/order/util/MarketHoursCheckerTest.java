package com.axiom.order.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketHoursCheckerTest {

    private MarketHoursChecker checker;

    @BeforeEach
    void setUp() {
        checker = new MarketHoursChecker();
    }

    @Test
    void isMarketOpen_returnsBooleanWithoutException() {
        boolean result = checker.isMarketOpen();
        assertThat(result).isIn(true, false);
    }

    @Test
    void nextMarketOpenAt_returnsIsoFormattedString() {
        String nextOpen = checker.nextMarketOpenAt();

        assertThat(nextOpen).isNotNull();
        assertThat(nextOpen).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}");
    }
}
