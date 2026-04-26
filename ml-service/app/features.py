"""
28개 피처 엔지니어링.

A. 개별 종목 기술적 지표 (15)
B. 시장 regime (5)
C. 섹터 상대강도 (3) — 데이터 부족 시 0 충전 (향후 stock-universe 섹터 필드 추가 필요)
D. 시간/캘린더 (5)

입력: candles (list of dicts with tradeDate/openPrice/highPrice/lowPrice/closePrice/volume)
     indexCandles (KOSPI 일봉, 동일 스키마)
출력: 단일 피처 벡터 (dict)
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Iterable

import numpy as np
import pandas as pd


FEATURE_NAMES = [
    # A. 기술 (15)
    "rsi14", "macd_hist", "bb_pctb", "bb_bandwidth",
    "sma5_sma20_ratio", "close_vs_sma20", "close_vs_sma60",
    "vol_ratio", "ret_5d", "ret_10d", "ret_20d",
    "daily_range_pct", "close_pos_in_range",
    "atr14_pct", "vol_slope_5d",
    # B. 시장 regime (5)
    "kospi_ma20_ratio", "kospi_ret_5d", "kospi_atr_pct",
    "kospi_above_ma60", "market_breadth",
    # C. 섹터 상대강도 (3) — 현재는 0 placeholder
    "sector_rs_5d", "sector_rank_pct", "sector_momentum",
    # D. 시간 (5)
    "day_of_week", "day_of_month", "days_to_month_end",
    "days_since_earnings", "is_options_expiry_week",
]


def to_df(candles: list[dict]) -> pd.DataFrame:
    if not candles:
        return pd.DataFrame(columns=["date", "open", "high", "low", "close", "volume"])
    df = pd.DataFrame(candles)
    # camelCase → snake. (Java side uses openPrice 등)
    rename_map = {
        "tradeDate": "date", "openPrice": "open", "highPrice": "high",
        "lowPrice": "low", "closePrice": "close",
    }
    df = df.rename(columns=rename_map)
    if "date" in df.columns:
        # Jackson이 LocalDate를 [year, month, day] 배열로 직렬화할 경우 처리
        def _parse_date(v):
            if isinstance(v, list) and len(v) == 3:
                return pd.Timestamp(year=int(v[0]), month=int(v[1]), day=int(v[2]))
            return pd.to_datetime(v)
        df["date"] = df["date"].apply(_parse_date)
    for c in ("open", "high", "low", "close"):
        if c in df.columns:
            df[c] = pd.to_numeric(df[c], errors="coerce")
    if "volume" in df.columns:
        df["volume"] = pd.to_numeric(df["volume"], errors="coerce").fillna(0).astype("int64")
    return df.dropna(subset=["close"]).sort_values("date").reset_index(drop=True)


def _rsi_wilder(close: pd.Series, period: int = 14) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0)
    loss = -delta.clip(upper=0)
    avg_gain = gain.ewm(alpha=1 / period, adjust=False, min_periods=period).mean()
    avg_loss = loss.ewm(alpha=1 / period, adjust=False, min_periods=period).mean()
    rs = avg_gain / avg_loss.replace(0, np.nan)
    rsi = 100 - 100 / (1 + rs)
    return rsi.fillna(50.0)


def _atr(df: pd.DataFrame, period: int = 14) -> pd.Series:
    high = df["high"]; low = df["low"]; close = df["close"]
    prev_close = close.shift(1)
    tr = pd.concat([
        (high - low),
        (high - prev_close).abs(),
        (low  - prev_close).abs(),
    ], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / period, adjust=False, min_periods=period).mean()


def _macd_hist(close: pd.Series) -> pd.Series:
    ema12 = close.ewm(span=12, adjust=False).mean()
    ema26 = close.ewm(span=26, adjust=False).mean()
    macd = ema12 - ema26
    sig  = macd.ewm(span=9, adjust=False).mean()
    return macd - sig


def _bollinger(close: pd.Series, period: int = 20, k: float = 2.0):
    sma = close.rolling(period).mean()
    std = close.rolling(period).std(ddof=0)
    upper = sma + k * std
    lower = sma - k * std
    pctb  = (close - lower) / (upper - lower)
    bw    = (upper - lower) / sma
    return pctb, bw


def _rolling_slope(x: pd.Series, window: int = 5) -> pd.Series:
    """window 기간에 대한 단순 회귀 기울기 (시간축을 0..window-1 로)."""
    t = np.arange(window, dtype=np.float64)
    t_centered = t - t.mean()
    denom = (t_centered ** 2).sum()

    def _slope(v):
        y = np.asarray(v, dtype=np.float64)
        if np.isnan(y).any(): return 0.0
        return float(((y - y.mean()) * t_centered).sum() / denom)

    return x.rolling(window).apply(_slope, raw=True)


def compute_stock_features(df: pd.DataFrame, at_idx: int) -> dict[str, float]:
    """df[at_idx] 시점 기준의 개별 종목 피처 15개 계산."""
    close = df["close"]
    vol   = df["volume"].astype("float64")

    rsi14 = _rsi_wilder(close).iloc[at_idx]
    macd_hist = _macd_hist(close).iloc[at_idx]
    pctb, bw = _bollinger(close)
    bb_pctb = pctb.iloc[at_idx]
    bb_bw   = bw.iloc[at_idx]
    sma5  = close.rolling(5).mean().iloc[at_idx]
    sma20 = close.rolling(20).mean().iloc[at_idx]
    sma60 = close.rolling(60).mean().iloc[at_idx] if at_idx >= 59 else sma20
    vol_ma20 = vol.rolling(20).mean().iloc[at_idx]
    curr = close.iloc[at_idx]
    high = df["high"].iloc[at_idx]; low = df["low"].iloc[at_idx]
    atr14 = _atr(df).iloc[at_idx]

    def _safe_ret(k):
        if at_idx - k < 0: return 0.0
        prev = close.iloc[at_idx - k]
        if prev == 0 or pd.isna(prev): return 0.0
        return float(curr / prev - 1)

    vol_slope = _rolling_slope(vol, 5).iloc[at_idx] if at_idx >= 4 else 0.0

    def _f(v, default=0.0):
        try:
            x = float(v)
            if np.isnan(x) or np.isinf(x): return default
            return x
        except Exception:
            return default

    sma20_v = _f(sma20, curr)
    sma5_v  = _f(sma5, curr)
    sma60_v = _f(sma60, curr)
    return {
        "rsi14":              _f(rsi14, 50.0),
        "macd_hist":          _f(macd_hist),
        "bb_pctb":            _f(bb_pctb, 0.5),
        "bb_bandwidth":       _f(bb_bw, 0.05),
        "sma5_sma20_ratio":   sma5_v / sma20_v if sma20_v else 1.0,
        "close_vs_sma20":     (curr - sma20_v) / sma20_v * 100 if sma20_v else 0.0,
        "close_vs_sma60":     (curr - sma60_v) / sma60_v * 100 if sma60_v else 0.0,
        "vol_ratio":          (vol.iloc[at_idx] / vol_ma20) if vol_ma20 else 1.0,
        "ret_5d":             _safe_ret(5),
        "ret_10d":            _safe_ret(10),
        "ret_20d":             _safe_ret(20),
        "daily_range_pct":    (high - low) / curr if curr else 0.0,
        "close_pos_in_range": (curr - low) / (high - low) if high > low else 0.5,
        "atr14_pct":          _f(atr14 / curr if curr else 0.0, 0.02),
        "vol_slope_5d":       _f(vol_slope),
    }


def compute_kospi_features(idx_df: pd.DataFrame, at_date: pd.Timestamp,
                           market_breadth: float = 0.5) -> dict[str, float]:
    if idx_df.empty:
        return {"kospi_ma20_ratio": 1.0, "kospi_ret_5d": 0.0, "kospi_atr_pct": 0.02,
                "kospi_above_ma60": 0.0, "market_breadth": market_breadth}

    # at_date 이전까지의 자료만 사용 (look-ahead 방지)
    usable = idx_df[idx_df["date"] <= at_date]
    if len(usable) < 20:
        return {"kospi_ma20_ratio": 1.0, "kospi_ret_5d": 0.0, "kospi_atr_pct": 0.02,
                "kospi_above_ma60": 0.0, "market_breadth": market_breadth}

    close = usable["close"]
    last = close.iloc[-1]
    ma20 = close.rolling(20).mean().iloc[-1]
    ma60 = close.rolling(60).mean().iloc[-1] if len(close) >= 60 else ma20
    atr = _atr(usable).iloc[-1]

    def _ret(k):
        if len(close) <= k: return 0.0
        prev = close.iloc[-1 - k]
        return float(last / prev - 1) if prev else 0.0

    return {
        "kospi_ma20_ratio":   float(last / ma20) if ma20 else 1.0,
        "kospi_ret_5d":       _ret(5),
        "kospi_atr_pct":      float(atr / last) if last else 0.02,
        "kospi_above_ma60":   1.0 if last > ma60 else 0.0,
        "market_breadth":     float(market_breadth),
    }


def compute_time_features(at_date: pd.Timestamp | datetime | date) -> dict[str, float]:
    if isinstance(at_date, pd.Timestamp):
        d = at_date.to_pydatetime().date()
    elif isinstance(at_date, datetime):
        d = at_date.date()
    else:
        d = at_date
    # 월말까지 남은 일수
    if d.month == 12:
        next_month = date(d.year + 1, 1, 1)
    else:
        next_month = date(d.year, d.month + 1, 1)
    days_to_month_end = (next_month - d).days - 1

    # 옵션 동시만기(둘째 목요일) 주간 여부
    first_day = date(d.year, d.month, 1)
    # 해당 월 첫 목요일
    first_thursday = first_day + pd.Timedelta(days=(3 - first_day.weekday()) % 7)
    second_thursday = first_thursday + pd.Timedelta(days=7)
    st = second_thursday.date() if isinstance(second_thursday, pd.Timestamp) else second_thursday
    from datetime import timedelta
    monday = d - timedelta(days=d.weekday())
    sunday = monday + timedelta(days=6)
    is_expiry = 1.0 if (monday <= st <= sunday) else 0.0

    return {
        "day_of_week":           float(d.weekday()),
        "day_of_month":          float(d.day),
        "days_to_month_end":     float(days_to_month_end),
        "days_since_earnings":   0.0,  # 추후 외부 데이터 연결 시 채움
        "is_options_expiry_week": is_expiry,
    }


def compute_sector_features(ticker: str, df: pd.DataFrame, at_idx: int) -> dict[str, float]:
    # 향후 stock-universe.json 의 섹터 분류 확장 필요. 현재는 중립값 반환.
    return {"sector_rs_5d": 0.0, "sector_rank_pct": 0.5, "sector_momentum": 0.0}


def build_feature_vector(ticker: str,
                         df: pd.DataFrame,
                         idx_df: pd.DataFrame,
                         at_idx: int,
                         market_breadth: float = 0.5) -> dict[str, float]:
    """df[at_idx] 시점 기준 28개 피처 벡터."""
    stock = compute_stock_features(df, at_idx)
    at_date = df["date"].iloc[at_idx]
    kospi = compute_kospi_features(idx_df, at_date, market_breadth)
    sector = compute_sector_features(ticker, df, at_idx)
    time_f = compute_time_features(at_date)
    return {**stock, **kospi, **sector, **time_f}


def feature_matrix(ticker: str,
                   df: pd.DataFrame,
                   idx_df: pd.DataFrame,
                   min_idx: int = 60,
                   breadth_by_date: dict | None = None) -> tuple[pd.DataFrame, pd.Series]:
    """학습용 — df 전체에 대해 at_idx=min_idx..len-1 피처·date 반환.

    breadth_by_date: {date → float} 날짜별 market_breadth. None이면 0.5 고정.
    """
    rows = []
    dates = []
    n = len(df)
    for i in range(min_idx, n):
        at_date = df["date"].iloc[i]
        mb = breadth_by_date.get(at_date, 0.5) if breadth_by_date else 0.5
        feat = build_feature_vector(ticker, df, idx_df, i, mb)
        rows.append(feat)
        dates.append(at_date)
    return pd.DataFrame(rows, columns=FEATURE_NAMES), pd.Series(dates, name="date")
