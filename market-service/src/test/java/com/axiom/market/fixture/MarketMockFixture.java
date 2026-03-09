package com.axiom.market.fixture;

import com.axiom.market.dto.StockPriceDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * market-service 단위 테스트에서 사용하는 mock 데이터 헬퍼.
 * KisMarketApiService.getMockPrice()와 동일한 종목/기본가 데이터를 상수로 제공한다.
 */
public class MarketMockFixture {

    /** mock 모드에서 지원하는 종목코드 → [종목명, 기본가] 매핑 */
    public static final Map<String, Object[]> MOCK_STOCK_DATA = Map.of(
            "005930", new Object[]{"삼성전자",   75000},
            "000660", new Object[]{"SK하이닉스", 185000},
            "035420", new Object[]{"NAVER",       220000},
            "051910", new Object[]{"LG화학",      320000},
            "006400", new Object[]{"삼성SDI",     280000}
    );

    /**
     * 지정 ticker에 대한 고정 mock 가격 DTO 생성.
     * 서비스의 getMockPrice()와 동일한 필드 구조를 반환한다.
     */
    public static StockPriceDto createMockPrice(String ticker) {
        Object[] data = MOCK_STOCK_DATA.getOrDefault(ticker, new Object[]{"알 수 없는 종목", 50000});
        String stockName = (String) data[0];
        int basePrice    = (int) data[1];

        return StockPriceDto.builder()
                .ticker(ticker)
                .stockName(stockName)
                .currentPrice(BigDecimal.valueOf(basePrice))
                .changeAmount(BigDecimal.ZERO)
                .changeRate(BigDecimal.ZERO)
                .highPrice(BigDecimal.valueOf(basePrice + 500))
                .lowPrice(BigDecimal.valueOf(basePrice - 500))
                .openPrice(BigDecimal.valueOf(basePrice))
                .volume(1_000_000L)
                .fetchedAt(LocalDateTime.now())
                .mock(true)
                .marketWarnCode("00")
                .build();
    }
}
