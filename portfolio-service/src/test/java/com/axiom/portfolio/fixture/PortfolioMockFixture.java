package com.axiom.portfolio.fixture;

import java.math.BigDecimal;

/**
 * portfolio-service 단위 테스트에서 사용하는 mock 잔고 데이터 상수.
 * KisAccountApiService.getMockBalance()의 반환값과 동일한 값을 정의한다.
 */
public class PortfolioMockFixture {

    public static final BigDecimal TOTAL_BALANCE    = new BigDecimal("10000000");
    public static final BigDecimal CASH_BALANCE     = new BigDecimal("5000000");
    public static final BigDecimal STOCK_BALANCE    = new BigDecimal("5000000");
    public static final BigDecimal PROFIT_LOSS      = new BigDecimal("250000");
    public static final BigDecimal PROFIT_LOSS_RATE = new BigDecimal("5.26");

    private PortfolioMockFixture() {}
}
