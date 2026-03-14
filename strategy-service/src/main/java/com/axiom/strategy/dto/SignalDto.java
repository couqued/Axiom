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
    private String reason;          // 신호 발생 이유
    private LocalDateTime signalAt;
    private double score;           // BUY 신호 강도 점수 (0~100). SELL/HOLD = 0
    private Integer buyStage;       // 1: 1차(BB), 2: 2차(RSI) 또는 통합매수

    public boolean isTradeSignal() {
        return action == Action.BUY || action == Action.SELL;
    }
}
