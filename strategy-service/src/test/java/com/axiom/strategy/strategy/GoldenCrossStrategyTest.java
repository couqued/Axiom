package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.fixture.CandleFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenCrossStrategyTest {

    private GoldenCrossStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new GoldenCrossStrategy();
    }

    @Test
    void getName_returnsGoldenCross() {
        assertThat(strategy.getName()).isEqualTo("golden-cross");
    }

    @Test
    void minimumCandles_returns21() {
        assertThat(strategy.minimumCandles()).isEqualTo(21);
    }

    @Test
    void evaluate_goldenCross_returnsBuy() {
        List<CandleDto> candles = CandleFixture.goldenCrossCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.BUY);
        assertThat(signal.getTicker()).isEqualTo("005930");
        assertThat(signal.getScore()).isGreaterThan(0);
        assertThat(signal.getScore()).isLessThanOrEqualTo(100);
        assertThat(signal.getReason()).contains("골든크로스");
    }

    @Test
    void evaluate_deadCross_returnsSell() {
        List<CandleDto> candles = CandleFixture.deadCrossCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.SELL);
        assertThat(signal.getReason()).contains("데드크로스");
    }

    @Test
    void evaluate_noCross_returnsHold() {
        List<CandleDto> candles = CandleFixture.holdCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.HOLD);
        assertThat(signal.getReason()).contains("관망");
    }

    @Test
    void evaluate_goldenCross_scoreIsWithinBounds() {
        List<CandleDto> candles = CandleFixture.goldenCrossCandles("005930");
        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getScore()).isBetween(0.0, 100.0);
    }
}
