package com.axiom.market.dto;

import com.axiom.market.entity.DailyCandle;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class CandleDto {

    private LocalDate tradeDate;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private Long volume;

    public static CandleDto from(DailyCandle candle) {
        return CandleDto.builder()
                .tradeDate(candle.getTradeDate())
                .openPrice(candle.getOpenPrice())
                .highPrice(candle.getHighPrice())
                .lowPrice(candle.getLowPrice())
                .closePrice(candle.getClosePrice())
                .volume(candle.getVolume())
                .build();
    }
}
