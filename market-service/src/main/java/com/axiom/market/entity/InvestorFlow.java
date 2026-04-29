package com.axiom.market.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(
    name = "investor_flows",
    schema = "market",
    uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "trade_date"})
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestorFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String ticker;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "frgn_ntby_qty", nullable = false)
    private Long frgnNtbyQty;       // 외국인 순매수량 (음수=순매도)

    @Column(name = "frgn_ntby_tr_pbmn", nullable = false)
    private Long frgnNtbyTrPbmn;    // 외국인 순매수 거래대금

    @Column(name = "orgn_ntby_qty", nullable = false)
    private Long orgnNtbyQty;       // 기관 합계 순매수량 (음수=순매도)

    @Column(name = "orgn_ntby_tr_pbmn", nullable = false)
    private Long orgnNtbyTrPbmn;    // 기관 순매수 거래대금

    @Column(name = "total_vol", nullable = false)
    private Long totalVol;          // 당일 누적거래량
}
