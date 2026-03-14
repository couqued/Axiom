package com.axiom.strategy.service;

/**
 * 시장 상태.
 * 코스피 지수의 20일 이평선 대비 현재가 위치로 판별.
 */
public enum MarketState {
    /** 상승장: 현재 종가 > 20일 이평선 → 변동성 돌파 + 골든크로스 전략 */
    BULLISH,
    /** 횡보장: MA20 * 0.97 <= 현재 종가 <= MA20 */
    SIDEWAYS,
    /** 하락장: 현재 종가 < MA20 * 0.97 (MA20 대비 3% 이상 하락) */
    BEARISH
}
