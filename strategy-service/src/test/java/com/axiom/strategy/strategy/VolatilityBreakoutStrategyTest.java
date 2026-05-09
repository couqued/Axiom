package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;
import com.axiom.strategy.fixture.CandleFixture;
import com.axiom.strategy.persistence.StrategyStateStore;
import com.axiom.strategy.service.EntryQualityEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VolatilityBreakoutStrategyTest {

    @Mock
    private StrategyStateStore stateStore;

    @Mock
    private EntryQualityEvaluator entryQualityEvaluator;

    private VolatilityBreakoutStrategy strategy;

    @BeforeEach
    void setUp() {
        when(stateStore.loadAllTodayBought()).thenReturn(Collections.emptyMap());
        lenient().when(entryQualityEvaluator.evaluate(anyString(), any(BigDecimal.class), anyList()))
                .thenReturn(1.0);
        strategy = new VolatilityBreakoutStrategy(stateStore, entryQualityEvaluator);
        strategy.initFromDb();
    }

    @Test
    void getName_returnsVolatilityBreakout() {
        assertThat(strategy.getName()).isEqualTo("volatility-breakout");
    }

    @Test
    void minimumCandles_returns3() {
        assertThat(strategy.minimumCandles()).isEqualTo(3);
    }

    @Test
    void evaluate_targetReached_returnsBuy() {
        // 어제: range=20, 오늘: open=105, target=115, close=120 → BUY
        List<CandleDto> candles = CandleFixture.volatilityBreakoutBuyCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.BUY);
        assertThat(signal.getScore()).isGreaterThan(0);
        assertThat(signal.getReason()).contains("변동성 돌파");
    }

    @Test
    void evaluate_targetNotReached_returnsHold() {
        // 어제: range=20, 오늘: open=105, target=115, close=108 → HOLD
        List<CandleDto> candles = CandleFixture.volatilityBreakoutHoldCandles("005930");

        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getAction()).isEqualTo(SignalDto.Action.HOLD);
        assertThat(signal.getReason()).contains("목표가 미달성");
    }

    @Test
    void evaluate_alreadyBoughtToday_returnsHold() {
        // 당일 이미 매수한 종목 → 중복 매수 방지
        List<CandleDto> candles = CandleFixture.volatilityBreakoutBuyCandles("005930");

        // 첫 번째 평가로 BUY 신호 확인
        SignalDto first = strategy.evaluate("005930", candles);
        assertThat(first.getAction()).isEqualTo(SignalDto.Action.BUY);

        // markBought 등록
        strategy.markBought("005930");

        // 두 번째 평가: 이미 매수됨 → HOLD
        SignalDto second = strategy.evaluate("005930", candles);
        assertThat(second.getAction()).isEqualTo(SignalDto.Action.HOLD);
        assertThat(second.getReason()).contains("오늘 이미 매수됨");
    }

    @Test
    void evaluate_buy_scoreIsWithinBounds() {
        List<CandleDto> candles = CandleFixture.volatilityBreakoutBuyCandles("005930");
        SignalDto signal = strategy.evaluate("005930", candles);

        assertThat(signal.getScore()).isBetween(0.0, 100.0);
    }
}
