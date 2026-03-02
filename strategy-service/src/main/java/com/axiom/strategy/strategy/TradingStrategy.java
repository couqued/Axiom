package com.axiom.strategy.strategy;

import com.axiom.strategy.dto.CandleDto;
import com.axiom.strategy.dto.SignalDto;

import java.util.List;

/**
 * 모든 매매 전략이 구현해야 하는 인터페이스.
 *
 * <p>구현 예시:
 * <ul>
 *   <li>GoldenCrossStrategy  — 이동평균 골든크로스/데드크로스</li>
 *   <li>MacdStrategy         — MACD 시그널 교차</li>
 *   <li>RsiStrategy          — RSI 과매수/과매도</li>
 *   <li>BollingerBandStrategy— 볼린저밴드 상/하단 터치</li>
 *   <li>VolatilityBreakout   — 변동성 돌파</li>
 * </ul>
 */
public interface TradingStrategy {

    /** application.yml enabled-strategies 목록과 매칭되는 전략 이름 */
    String getName();

    /**
     * 캔들 데이터를 분석해 매수/매도/관망 신호를 반환한다.
     *
     * @param ticker  종목 코드
     * @param candles 최신순 정렬된 일봉 목록 (최소 필요 개수는 전략마다 다름)
     * @return BUY / SELL / HOLD 신호
     */
    SignalDto evaluate(String ticker, List<CandleDto> candles);

    /** 전략 실행에 필요한 최소 캔들 수 */
    int minimumCandles();
}
