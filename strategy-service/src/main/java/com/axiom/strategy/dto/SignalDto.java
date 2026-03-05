package com.axiom.strategy.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder(toBuilder = true)
public class SignalDto {

    public enum Action { BUY, SELL, HOLD }

    private Action action;
    private String ticker;
    private String stockName;       // 종목명 (StrategyEngine이 주입)
    private BigDecimal price;       // 신호 발생 시점 가격
    private String strategyName;    // 신호를 생성한 전략명
    private String reason;          // 신호 발생 이유 (예: "MA5(210000) > MA20(205000)")
    private LocalDateTime signalAt;

    public boolean isTradeSignal() {
        return action == Action.BUY || action == Action.SELL;
    }
}
