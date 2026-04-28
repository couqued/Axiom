"""
Triple-barrier 라벨링.

각 기준일 t 에 대해 t+1 ~ t+HORIZON 사이에서:
  - high >= TP → label_cls=1 (WIN),  days_to_hit=i-t, max_ret = +tp_pct
  - low  <= SL → label_cls=0 (LOSS), days_to_hit=i-t, max_ret = -sl_pct
  - 둘 다 같은 날 발생: 샘플 제외
  - 미발동 (HORIZON 경과): label_cls=0 (TIME), days_to_hit=HORIZON, max_ret=close[t+H]/close[t]-1

TP/SL 폭은 ATR 변동성에 비례 (volScale).
"""

from __future__ import annotations

import pandas as pd

from . import config


def _vol_scale(atr_pct: float) -> float:
    if atr_pct is None or atr_pct <= 0:
        return config.VOL_SCALE_MIN
    s = atr_pct / config.ATR_BASE
    return max(config.VOL_SCALE_MIN, min(config.VOL_SCALE_MAX, s))


def label_triple_barrier(df: pd.DataFrame, at_idx: int, atr14_pct_at_t: float):
    """
    Returns (label_cls, days_to_hit, max_ret, keep) — keep=False 면 샘플 제외.
    df는 date 오름차순 OHLC.
    """
    n = len(df)
    if at_idx + 1 >= n:
        return None, None, None, False

    entry = float(df["close"].iloc[at_idx])
    if entry <= 0:
        return None, None, None, False

    v = _vol_scale(atr14_pct_at_t)
    tp_pct = config.TP_BASE_PCT * v
    sl_pct = config.SL_BASE_PCT * v
    tp = entry * (1 + tp_pct)
    sl = entry * (1 - sl_pct)

    horizon = min(config.HORIZON_DAYS, n - 1 - at_idx)

    for k in range(1, horizon + 1):
        i = at_idx + k
        hi = float(df["high"].iloc[i])
        lo = float(df["low"].iloc[i])
        hit_tp = hi >= tp
        hit_sl = lo <= sl
        if hit_tp and hit_sl:
            # 같은 날 동시 발생 — 샘플 제외 (불확실)
            return None, None, None, False
        if hit_tp:
            return 1, k, tp_pct, True
        if hit_sl:
            return 0, k, -sl_pct, True

    # HORIZON 경과 — TIME barrier: 방향성 불명확하므로 학습에서 제외
    return None, None, None, False
