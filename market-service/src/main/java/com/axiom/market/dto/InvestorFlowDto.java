package com.axiom.market.dto;

import com.axiom.market.entity.InvestorFlow;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class InvestorFlowDto {

    private LocalDate tradeDate;
    private Long frgnNtbyQty;
    private Long frgnNtbyTrPbmn;
    private Long orgnNtbyQty;
    private Long orgnNtbyTrPbmn;
    private Long totalVol;

    public static InvestorFlowDto from(InvestorFlow e) {
        return InvestorFlowDto.builder()
                .tradeDate(e.getTradeDate())
                .frgnNtbyQty(e.getFrgnNtbyQty())
                .frgnNtbyTrPbmn(e.getFrgnNtbyTrPbmn())
                .orgnNtbyQty(e.getOrgnNtbyQty())
                .orgnNtbyTrPbmn(e.getOrgnNtbyTrPbmn())
                .totalVol(e.getTotalVol())
                .build();
    }
}
