package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.fixture.CandleFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RsiBollingerStrategyTest {

    private RsiBollingerStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RsiBollingerStrategy();
    }

    @Test
    void getName_returnsRsiBollinger() {
        assertThat(strategy.getName()).isEqualTo("rsi-bollinger");
    }

    @Test
    void minimumCandles_returns21() {
        assertThat(strategy.minimumCandles()).isEqualTo(21);
    }

    @Test
    void evaluate_oversold_returnsBuy() {
        // RSI=0, close < 볼린저 하단 → BUY
        List<CandleDto> candles = CandleFixture.rsiBollingerOversoldCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.BUY);
        assertThat(signal.getScore()).isGreaterThan(0);
        assertThat(signal.getReason()).contains("과매도");
    }

    @Test
    void evaluate_overbought_returnsSell() {
        // RSI=100, close >= 볼린저 중심선 → SELL
        List<CandleDto> candles = CandleFixture.rsiBollingerOverboughtCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.SELL);
    }

    @Test
    void evaluate_neutral_returnsHold() {
        // RSI≈50 (30~70 범위) → HOLD
        List<CandleDto> candles = CandleFixture.rsiBollingerHoldCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.HOLD);
        assertThat(signal.getReason()).contains("관망");
    }

    @Test
    void evaluate_oversold_scoreIsWithinBounds() {
        List<CandleDto> candles = CandleFixture.rsiBollingerOversoldCandles("005930");
        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getScore()).isBetween(0.0, 100.0);
    }
}
