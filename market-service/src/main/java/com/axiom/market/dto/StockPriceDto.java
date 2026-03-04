package com.axiom.market.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class StockPriceDto {
    private String ticker;
    private String stockName;
    private BigDecimal currentPrice;
    private BigDecimal changeAmount;     // 전일 대비 등락폭
    private BigDecimal changeRate;       // 전일 대비 등락률(%)
    private BigDecimal highPrice;        // 당일 고가
    private BigDecimal lowPrice;         // 당일 저가
    private BigDecimal openPrice;        // 시가
    private Long volume;                 // 거래량
    private LocalDateTime fetchedAt;
    private boolean mock;
    private String marketWarnCode;       // KIS mrkt_warn_cls_code: "00"=정상, "01"=투자주의, "02"=투자경고, "03"=투자위험

    public boolean isSafe() {
        return marketWarnCode == null || "00".equals(marketWarnCode);
    }
}
