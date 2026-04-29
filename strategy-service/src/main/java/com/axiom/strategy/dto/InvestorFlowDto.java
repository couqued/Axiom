package com.axiom.strategy.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class InvestorFlowDto {

    private LocalDate tradeDate;
    private Long frgnNtbyQty;
    private Long frgnNtbyTrPbmn;
    private Long orgnNtbyQty;
    private Long orgnNtbyTrPbmn;
    private Long totalVol;
}
