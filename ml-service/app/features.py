"""
36개 피처 엔지니어링.

A. 개별 종목 기술적 지표 (18)  ← OBV 2개 + rolling VWAP 1개 추가
B. 시장 regime (5)
C. 섹터 상대강도 (3) — 데이터 부족 시 0 충전 (향후 stock-universe 섹터 필드 추가 필요)
D. 시간/캘린더 (5)
E. 글로벌 지수/환율 (5) — NASDAQ, S&P500, USD/KRW

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
    # A. 기술 (18)
    "rsi14", "macd_hist", "bb_pctb", "bb_bandwidth",
    "sma5_sma20_ratio", "close_vs_sma20", "close_vs_sma60",
    "vol_ratio", "ret_5d", "ret_10d", "ret_20d",
    "daily_range_pct", "close_pos_in_range",
    "atr14_pct", "vol_slope_5d",
    "obv_vs_ema20", "obv_slope_5d", "close_vs_vwap20",
    # B. 시장 regime (5)
    "kospi_ma20_ratio", "kospi_ret_5d", "kospi_atr_pct",
    "kospi_above_ma60", "market_breadth",
    # C. 섹터 상대강도 (3) — 현재는 0 placeholder
    "sector_rs_5d", "sector_rank_pct", "sector_momentum",
    # D. 시간 (5)
    "day_of_week", "day_of_month", "days_to_month_end",
    "days_since_earnings", "is_options_expiry_week",
    # E. 글로벌 지수/환율 (5)
    "nasdaq_ret_1d", "nasdaq_vs_ma20",
    "sp500_ret_1d",
    "usdkrw_ret_5d", "usdkrw_vs_ma20",
    # F. 외국인·기관 순매수 (10)
    "frgn_net_ratio_5d", "frgn_net_ratio_20d",
    "orgn_net_ratio_5d", "orgn_net_ratio_20d",
    "frgn_cum_slope_5d", "orgn_cum_slope_5d",
    "frgn_orgn_aligned", "combined_net_ratio_5d",
    "frgn_today_ratio",  "orgn_today_ratio",
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


def to_flow_df(flows: list[dict]) -> pd.DataFrame:
    """InvestorFlowDto 목록 → 분석용 DataFrame.

    컬럼: date, frgn_ntby_qty, orgn_ntby_qty, total_vol
    """
    cols = ["date", "frgn_ntby_qty", "orgn_ntby_qty", "total_vol"]
    if not flows:
        return pd.DataFrame(columns=cols)
    df = pd.DataFrame(flows)
    rename_map = {
        "tradeDate":    "date",
        "frgnNtbyQty":  "frgn_ntby_qty",
        "orgnNtbyQty":  "orgn_ntby_qty",
        "totalVol":     "total_vol",
    }
    df = df.rename(columns=rename_map)
    if "date" in df.columns:
        def _parse_date(v):
            if isinstance(v, list) and len(v) == 3:
                return pd.Timestamp(year=int(v[0]), month=int(v[1]), day=int(v[2]))
            return pd.to_datetime(v)
        df["date"] = df["date"].apply(_parse_date)
    for c in ("frgn_ntby_qty", "orgn_ntby_qty", "total_vol"):
        if c in df.columns:
            df[c] = pd.to_numeric(df[c], errors="coerce").fillna(0)
    return df.sort_values("date").reset_index(drop=True)


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


def _obv(df: pd.DataFrame) -> pd.Series:
    """On-Balance Volume: 상승일 +vol, 하락일 -vol 누적합."""
    close = df["close"]
    vol = df["volume"].astype("float64")
    direction = np.sign(close.diff().fillna(0))
    return (direction * vol).cumsum()


def _rolling_vwap(df: pd.DataFrame, period: int = 20) -> pd.Series:
    """period일 롤링 VWAP = sum(typical_price × vol) / sum(vol)."""
    typical = (df["high"] + df["low"] + df["close"]) / 3
    vol = df["volume"].astype("float64").replace(0, np.nan)
    tp_vol = typical * vol
    return tp_vol.rolling(period).sum() / vol.rolling(period).sum()


def compute_stock_features(df: pd.DataFrame, at_idx: int) -> dict[str, float]:
    """df[at_idx] 시점 기준의 개별 종목 피처 18개 계산."""
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

    # OBV 지표
    obv_s = _obv(df)
    obv_ema20 = obv_s.ewm(span=20, adjust=False).mean().iloc[at_idx]
    obv_curr = float(obv_s.iloc[at_idx])
    obv_vs_ema20 = float(obv_curr / obv_ema20) if obv_ema20 != 0 else 1.0
    # OBV slope를 평균 거래량으로 정규화 → 종목 간 비교 가능
    obv_slope_raw = _rolling_slope(obv_s, 5).iloc[at_idx] if at_idx >= 4 else 0.0
    obv_slope_5d = float(obv_slope_raw / vol_ma20) if vol_ma20 and vol_ma20 > 0 else 0.0

    # 20일 롤링 VWAP
    vwap20_s = _rolling_vwap(df)
    vwap20 = vwap20_s.iloc[at_idx]
    vwap20_f = float(vwap20) if not pd.isna(vwap20) else 0.0
    close_vs_vwap20 = (float(curr) - vwap20_f) / vwap20_f * 100 if vwap20_f != 0 else 0.0

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
        "obv_vs_ema20":       _f(obv_vs_ema20, 1.0),
        "obv_slope_5d":       _f(obv_slope_5d),
        "close_vs_vwap20":    _f(close_vs_vwap20),
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


def compute_global_features(
    global_df_map: dict[str, pd.DataFrame] | None,
    at_date: pd.Timestamp,
) -> dict[str, float]:
    """NASDAQ, S&P500, USD/KRW 글로벌 피처 5개.

    at_date 미만(strictly <) 데이터만 사용 — 한국 장 시작 시점에
    당일 미국 종가는 아직 미확정이므로 전일 종가를 기준으로 함.
    """
    defaults = {
        "nasdaq_ret_1d": 0.0, "nasdaq_vs_ma20": 1.0,
        "sp500_ret_1d": 0.0,
        "usdkrw_ret_5d": 0.0, "usdkrw_vs_ma20": 1.0,
    }
    if not global_df_map:
        return defaults

    result: dict[str, float] = {}

    def _usable(key: str, n_min: int = 2) -> pd.Series | None:
        df = global_df_map.get(key)
        if df is None or df.empty:
            return None
        sub = df[df["date"] < at_date]
        return sub["close"] if len(sub) >= n_min else None

    # NASDAQ
    nasdaq_c = _usable("nasdaq")
    if nasdaq_c is not None and len(nasdaq_c) >= 2:
        last, prev = float(nasdaq_c.iloc[-1]), float(nasdaq_c.iloc[-2])
        ma20 = nasdaq_c.rolling(20).mean().iloc[-1]
        result["nasdaq_ret_1d"] = float(last / prev - 1) if prev else 0.0
        result["nasdaq_vs_ma20"] = float(last / ma20) if ma20 and not pd.isna(ma20) else 1.0
    else:
        result["nasdaq_ret_1d"] = defaults["nasdaq_ret_1d"]
        result["nasdaq_vs_ma20"] = defaults["nasdaq_vs_ma20"]

    # S&P 500
    sp500_c = _usable("sp500")
    if sp500_c is not None and len(sp500_c) >= 2:
        last, prev = float(sp500_c.iloc[-1]), float(sp500_c.iloc[-2])
        result["sp500_ret_1d"] = float(last / prev - 1) if prev else 0.0
    else:
        result["sp500_ret_1d"] = defaults["sp500_ret_1d"]

    # USD/KRW
    usdkrw_c = _usable("usdkrw", n_min=6)
    if usdkrw_c is not None and len(usdkrw_c) >= 6:
        last = float(usdkrw_c.iloc[-1])
        prev5 = float(usdkrw_c.iloc[-6])
        ma20 = usdkrw_c.rolling(20).mean().iloc[-1]
        result["usdkrw_ret_5d"] = float(last / prev5 - 1) if prev5 else 0.0
        result["usdkrw_vs_ma20"] = float(last / ma20) if ma20 and not pd.isna(ma20) else 1.0
    else:
        result["usdkrw_ret_5d"] = defaults["usdkrw_ret_5d"]
        result["usdkrw_vs_ma20"] = defaults["usdkrw_vs_ma20"]

    return result


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


def compute_investor_features(
    flow_df: pd.DataFrame | None,
    at_date: pd.Timestamp,
    today_flow: dict | None = None,
) -> dict[str, float]:
    """외국인·기관 순매수 피처 10개.

    flow_df: to_flow_df()로 변환된 DataFrame (컬럼: date, frgn_ntby_qty, orgn_ntby_qty, total_vol).
    at_date: 피처 계산 기준 날짜 (이 날짜까지의 데이터만 사용).
    today_flow: 당일 실시간 데이터 dict (추론 시 전달). None이면 flow_df에서 at_date 행을 사용.
    """
    defaults = {
        "frgn_net_ratio_5d":  0.0, "frgn_net_ratio_20d": 0.0,
        "orgn_net_ratio_5d":  0.0, "orgn_net_ratio_20d": 0.0,
        "frgn_cum_slope_5d":  0.0, "orgn_cum_slope_5d":  0.0,
        "frgn_orgn_aligned":  0.0, "combined_net_ratio_5d": 0.0,
        "frgn_today_ratio":   0.0, "orgn_today_ratio":   0.0,
    }

    if flow_df is None or flow_df.empty:
        return defaults

    usable = flow_df[flow_df["date"] <= at_date].copy()
    if len(usable) < 5:
        return defaults

    frgn = usable["frgn_ntby_qty"].astype(float)
    orgn = usable["orgn_ntby_qty"].astype(float)
    vol  = usable["total_vol"].astype(float).replace(0, np.nan)

    def _ratio(net: pd.Series, window: int) -> float:
        if len(net) < window:
            return 0.0
        net_sum = net.iloc[-window:].sum()
        vol_sum = vol.iloc[-window:].sum(skipna=True)
        if not vol_sum or np.isnan(vol_sum):
            return 0.0
        return float(net_sum / vol_sum)

    frgn_5  = _ratio(frgn, 5)
    frgn_20 = _ratio(frgn, 20) if len(usable) >= 20 else 0.0
    orgn_5  = _ratio(orgn, 5)
    orgn_20 = _ratio(orgn, 20) if len(usable) >= 20 else 0.0

    vol_ma5 = float(vol.iloc[-5:].mean(skipna=True)) if len(vol) >= 5 else 1.0
    if not vol_ma5 or np.isnan(vol_ma5):
        vol_ma5 = 1.0

    frgn_slope_s = _rolling_slope(frgn.cumsum(), 5)
    orgn_slope_s = _rolling_slope(orgn.cumsum(), 5)
    frgn_slope = float(frgn_slope_s.iloc[-1] / vol_ma5) if not frgn_slope_s.empty else 0.0
    orgn_slope = float(orgn_slope_s.iloc[-1] / vol_ma5) if not orgn_slope_s.empty else 0.0

    if frgn_5 > 0 and orgn_5 > 0:
        aligned = 1.0
    elif frgn_5 < 0 and orgn_5 < 0:
        aligned = -1.0
    else:
        aligned = 0.0

    combined_5 = _ratio(frgn + orgn, 5)

    # 당일 실시간 피처: today_flow 우선, 없으면 at_date 당일 행 사용
    frgn_today = 0.0
    orgn_today = 0.0
    if today_flow is not None:
        t_frgn = float(today_flow.get("frgnNtbyQty") or 0)
        t_orgn = float(today_flow.get("orgnNtbyQty") or 0)
        t_vol  = float(today_flow.get("totalVol") or 0)
        if t_vol > 0:
            frgn_today = t_frgn / t_vol
            orgn_today = t_orgn / t_vol
    else:
        today_rows = usable[usable["date"] == at_date]
        if not today_rows.empty:
            row = today_rows.iloc[-1]
            t_vol = float(row["total_vol"])
            if t_vol > 0:
                frgn_today = float(row["frgn_ntby_qty"]) / t_vol
                orgn_today = float(row["orgn_ntby_qty"]) / t_vol

    def _safe(v: float) -> float:
        if np.isnan(v) or np.isinf(v):
            return 0.0
        return float(np.clip(v, -1.0, 1.0))

    return {
        "frgn_net_ratio_5d":     _safe(frgn_5),
        "frgn_net_ratio_20d":    _safe(frgn_20),
        "orgn_net_ratio_5d":     _safe(orgn_5),
        "orgn_net_ratio_20d":    _safe(orgn_20),
        "frgn_cum_slope_5d":     _safe(frgn_slope),
        "orgn_cum_slope_5d":     _safe(orgn_slope),
        "frgn_orgn_aligned":     aligned,
        "combined_net_ratio_5d": _safe(combined_5),
        "frgn_today_ratio":      _safe(frgn_today),
        "orgn_today_ratio":      _safe(orgn_today),
    }


def build_feature_vector(ticker: str,
                         df: pd.DataFrame,
                         idx_df: pd.DataFrame,
                         at_idx: int,
                         market_breadth: float = 0.5,
                         global_df_map: dict | None = None,
                         flow_df: pd.DataFrame | None = None,
                         today_flow: dict | None = None) -> dict[str, float]:
    """df[at_idx] 시점 기준 46개 피처 벡터."""
    stock = compute_stock_features(df, at_idx)
    at_date = df["date"].iloc[at_idx]
    kospi = compute_kospi_features(idx_df, at_date, market_breadth)
    sector = compute_sector_features(ticker, df, at_idx)
    time_f = compute_time_features(at_date)
    global_f = compute_global_features(global_df_map, at_date)
    investor_f = compute_investor_features(flow_df, at_date, today_flow)
    return {**stock, **kospi, **sector, **time_f, **global_f, **investor_f}


def feature_matrix(ticker: str,
                   df: pd.DataFrame,
                   idx_df: pd.DataFrame,
                   min_idx: int = 60,
                   breadth_by_date: dict | None = None,
                   global_df_map: dict | None = None,
                   flow_df: pd.DataFrame | None = None) -> tuple[pd.DataFrame, pd.Series]:
    """학습용 — df 전체에 대해 at_idx=min_idx..len-1 피처·date 반환.

    breadth_by_date: {date → float} 날짜별 market_breadth. None이면 0.5 고정.
    global_df_map: {key → DataFrame} NASDAQ/S&P/FX 데이터. None이면 default값 사용.
    flow_df: to_flow_df()로 변환된 투자자 데이터. None이면 investor 피처 0.0 충전.
    """
    rows = []
    dates = []
    n = len(df)
    for i in range(min_idx, n):
        at_date = df["date"].iloc[i]
        mb = breadth_by_date.get(at_date, 0.5) if breadth_by_date else 0.5
        feat = build_feature_vector(ticker, df, idx_df, i, mb, global_df_map, flow_df)
        rows.append(feat)
        dates.append(at_date)
    return pd.DataFrame(rows, columns=FEATURE_NAMES), pd.Series(dates, name="date")
