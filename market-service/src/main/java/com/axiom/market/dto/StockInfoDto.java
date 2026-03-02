package com.axiom.market.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StockInfoDto {
    private String ticker;
    private String stockName;
    private String market;   // KOSPI, KOSDAQ
    private String sector;
}
