"""
axiom ml-service — XGBoost 기반 ML 예측 전략 마이크로서비스.

주요 엔드포인트:
  GET  /health           — 헬스체크
  GET  /model/status     — 학습 메타정보 + 피처 중요도 Top-10
  POST /train            — watch-tickers 전체 재학습
  POST /predict          — 단일 종목 추론 → TradePlan
  POST /predict/batch    — 일괄 추론
"""

from __future__ import annotations

import asyncio
import logging
import math
import threading
import time
from typing import Any

import httpx
import numpy as np
import pandas as pd
import yfinance as yf
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from . import config
from .features import (
    FEATURE_NAMES, build_feature_vector, feature_matrix, to_df, to_flow_df,
)
from .labels import label_triple_barrier
from .model import models

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
log = logging.getLogger("ml-service.main")

app = FastAPI(title="axiom ml-service", version="0.1.0")


# ── 응답/요청 모델 ──────────────────────────────────────────────────────────

class Candle(BaseModel):
    tradeDate: str | None = None
    openPrice: float | None = None
    highPrice: float | None = None
    lowPrice:  float | None = None
    closePrice: float | None = None
    volume:    int | None = None


class PredictRequest(BaseModel):
    ticker: str
    candles: list[dict[str, Any]] = Field(default_factory=list)
    indexCandles: list[dict[str, Any]] = Field(default_factory=list)
    marketBreadth: float = Field(default=0.5, ge=0.0, le=1.0)
    investorFlows: list[dict[str, Any]] = Field(default_factory=list)   # 과거 rolling용
    todayInvestorFlow: dict[str, Any] | None = None                     # 당일 실시간용


class BatchPredictRequest(BaseModel):
    candles: dict[str, list[dict[str, Any]]] = Field(default_factory=dict)
    indexCandles: list[dict[str, Any]] = Field(default_factory=list)
    marketBreadth: float = Field(default=0.5, ge=0.0, le=1.0)
    investorFlows: dict[str, list[dict[str, Any]]] = Field(default_factory=dict)    # ticker → flows
    todayInvestorFlows: dict[str, dict[str, Any]] = Field(default_factory=dict)     # ticker → today


class TradePlanResponse(BaseModel):
    ticker: str
    confidence: float
    mlScore: float
    entryPrice: float | None = None
    takeProfitPrice: float | None = None
    stopLossPrice: float | None = None
    expectedDays: int = 5
    maxDays: int = config.HORIZON_DAYS
    reason: str = ""
    features: dict[str, float] | None = None


# ── 글로벌 지수/환율 캐시 ────────────────────────────────────────────────────

_global_cache: dict[str, pd.DataFrame] = {}
_global_cache_ts: float = 0.0
_global_lock = threading.Lock()
_train_lock = asyncio.Lock()


def _fetch_global_data() -> dict[str, pd.DataFrame]:
    """NASDAQ, S&P500, USD/KRW 데이터를 yfinance에서 가져와 캐싱.

    TTL(GLOBAL_DATA_CACHE_TTL) 내 재호출은 캐시 반환.
    """
    global _global_cache, _global_cache_ts
    with _global_lock:
        now = time.time()
        if _global_cache and (now - _global_cache_ts) < config.GLOBAL_DATA_CACHE_TTL:
            return _global_cache

        result: dict[str, pd.DataFrame] = {}
        for key, symbol in config.GLOBAL_TICKERS.items():
            try:
                raw = yf.download(symbol, period="10y", auto_adjust=True, progress=False)
                if raw.empty:
                    log.warning("글로벌 데이터 없음: %s (%s)", key, symbol)
                    result[key] = pd.DataFrame(columns=["date", "close"])
                    continue
                # yfinance 버전에 따라 MultiIndex 컬럼일 수 있음
                if isinstance(raw.columns, pd.MultiIndex):
                    raw.columns = raw.columns.droplevel(1)
                df = raw[["Close"]].copy()
                df.columns = ["close"]
                # 타임존 제거 → date 비교 통일
                df.index = pd.to_datetime(df.index)
                if df.index.tz is not None:
                    df.index = df.index.tz_localize(None)
                df["date"] = df.index.normalize()
                df = df.reset_index(drop=True)[["date", "close"]].dropna()
                result[key] = df
                log.info("글로벌 데이터 로드: %s (%s) %d행", key, symbol, len(df))
            except Exception as e:
                log.warning("글로벌 데이터 조회 실패 (%s=%s): %s", key, symbol, e)
                result[key] = pd.DataFrame(columns=["date", "close"])

        _global_cache = result
        _global_cache_ts = time.time()
        return result


# ── 시작 시 모델 로드 ──────────────────────────────────────────────────────

@app.on_event("startup")
def _load_on_startup():
    if models.load():
        log.info("기존 모델 로드 — 바로 추론 가능")
    else:
        log.warning("학습된 모델 없음 — POST /train 으로 먼저 학습하세요.")


# ── 엔드포인트 ──────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "model_ready": models.is_ready()}


@app.get("/model/status")
def model_status():
    freshness: dict = {}
    with _global_lock:
        for key, df in _global_cache.items():
            if not df.empty and "date" in df.columns:
                latest = df["date"].max()
                try:
                    freshness[key] = str(latest.date())
                except AttributeError:
                    freshness[key] = str(latest)[:10]
            else:
                freshness[key] = None
        freshness["last_fetch_ts"] = _global_cache_ts if _global_cache_ts else None
    return {"ready": models.is_ready(), "meta": models.meta, "global_data_freshness": freshness}


@app.post("/predict", response_model=TradePlanResponse)
def predict(req: PredictRequest):
    return _predict_one(
        req.ticker, req.candles, req.indexCandles, req.marketBreadth,
        req.investorFlows, req.todayInvestorFlow,
    )


@app.post("/predict/batch")
def predict_batch(req: BatchPredictRequest):
    result: dict[str, dict] = {}
    for ticker, candles in req.candles.items():
        try:
            result[ticker] = _predict_one(
                ticker, candles, req.indexCandles, req.marketBreadth,
                req.investorFlows.get(ticker, []),
                req.todayInvestorFlows.get(ticker),
            ).dict()
        except HTTPException as ex:
            result[ticker] = {
                "ticker": ticker, "confidence": 0.0, "mlScore": 0.0,
                "reason": f"ERROR: {ex.detail}",
            }
    return result


@app.post("/train")
async def train():
    if _train_lock.locked():
        return {"status": "already_running", "message": "이미 학습 중입니다."}
    async with _train_lock:
        try:
            meta = await _train_all()
            return {"status": "ok", "meta": meta}
        except Exception as e:
            log.exception("학습 실패")
            raise HTTPException(status_code=500, detail=str(e))


# ── 추론 ────────────────────────────────────────────────────────────────────

def _predict_one(ticker: str, candles: list[dict], index_candles: list[dict],
                 market_breadth: float = 0.5,
                 investor_flows: list[dict] | None = None,
                 today_flow: dict | None = None) -> TradePlanResponse:
    if not models.is_ready():
        return TradePlanResponse(ticker=ticker, confidence=0.0, mlScore=0.0,
                                 reason="모델 미학습")

    df = to_df(candles)
    idx = to_df(index_candles)
    if len(df) < 60:
        return TradePlanResponse(ticker=ticker, confidence=0.0, mlScore=0.0,
                                 reason=f"캔들 부족 ({len(df)})")

    global_df_map = _fetch_global_data()
    flow_df = to_flow_df(investor_flows) if investor_flows else None

    at_idx = len(df) - 1
    feats = build_feature_vector(
        ticker, df, idx, at_idx, market_breadth, global_df_map, flow_df, today_flow,
    )
    try:
        pred = models.predict_one(feats)
    except Exception as e:
        return TradePlanResponse(ticker=ticker, confidence=0.0, mlScore=0.0,
                                 reason=f"추론 오류: {e}")

    entry = float(df["close"].iloc[at_idx])
    atr_pct = feats.get("atr14_pct", 0.02)
    vol_scale = max(config.VOL_SCALE_MIN, min(config.VOL_SCALE_MAX, atr_pct / config.ATR_BASE))
    tp_pct = config.TP_BASE_PCT * vol_scale   # 학습과 동일: ATR 기반 종목별 TP
    sl_pct = config.SL_BASE_PCT * vol_scale
    # 모델 예측 수익률 vs ATR 기반 TP 중 더 큰 값 사용 (종목별 차별화)
    expected_ret = max(pred["expected_return"], tp_pct)
    tp_price = entry * (1 + expected_ret)
    sl_price = entry * (1 - sl_pct)

    conf = float(pred["confidence"])
    return TradePlanResponse(
        ticker=ticker,
        confidence=round(conf, 4),
        mlScore=round(conf * 100, 2),
        entryPrice=entry,
        takeProfitPrice=round(tp_price, 2),
        stopLossPrice=round(sl_price, 2),
        expectedDays=int(pred["expected_days"]),
        maxDays=config.HORIZON_DAYS,
        reason=f"ML conf={conf*100:.1f}% expRet={expected_ret*100:.2f}% days={pred['expected_days']}",
        features={k: float(v) for k, v in feats.items()},
    )


# ── 학습 ────────────────────────────────────────────────────────────────────

def _compute_breadth_by_date(all_dfs: dict[str, pd.DataFrame]) -> dict:
    """날짜별 watch-tickers 상승 비율 계산 (전일 대비 종가 기준).

    학습 데이터에 look-ahead bias가 없도록 각 날짜의 종가를 전일 종가와 비교한다.
    """
    all_dates = sorted(set(
        d for df in all_dfs.values() for d in df["date"].tolist()
    ))
    # date → close 맵을 ticker별로 미리 준비
    close_maps: dict[str, dict] = {
        ticker: dict(zip(df["date"].tolist(), df["close"].tolist()))
        for ticker, df in all_dfs.items()
    }
    breadth: dict = {}
    for i, d in enumerate(all_dates):
        if i == 0:
            breadth[d] = 0.5
            continue
        prev_d = all_dates[i - 1]
        up, total = 0, 0
        for cm in close_maps.values():
            curr = cm.get(d)
            prev = cm.get(prev_d)
            if curr is None or prev is None or prev <= 0:
                continue
            total += 1
            if curr > prev:
                up += 1
        breadth[d] = up / total if total > 0 else 0.5
    return breadth


async def _fetch_json(client: httpx.AsyncClient, path: str):
    r = await client.get(path, timeout=30)
    r.raise_for_status()
    return r.json()


async def _fetch_watch_tickers(client: httpx.AsyncClient) -> list[str]:
    try:
        tickers = await _fetch_json(client, "/internal/screened-tickers")
        if tickers: return tickers
    except Exception as e:
        log.warning("screened-tickers 조회 실패 (%s) — fallback 목록 없음", e)
    return []


async def _fetch_candles(client: httpx.AsyncClient, ticker: str, days: int) -> list[dict]:
    return await _fetch_json(client, f"/api/stocks/{ticker}/candles?days={days}")


async def _fetch_investor_flows(client: httpx.AsyncClient, ticker: str, days: int) -> list[dict]:
    try:
        return await _fetch_json(client, f"/api/stocks/{ticker}/investor-flows?days={days}")
    except Exception as e:
        log.warning("  - %s 투자자 데이터 조회 실패: %s", ticker, e)
        return []


async def _train_all() -> dict:
    async with httpx.AsyncClient(base_url=config.MARKET_SERVICE_URL) as client:
        tickers = await _fetch_watch_tickers(client)
        if not tickers:
            raise RuntimeError("watch-tickers 비어 있음 — market-service 를 먼저 기동하세요.")

        log.info("학습 시작 — %d 종목, %d일 히스토리", len(tickers), config.HISTORY_DAYS)

        idx_raw = await _fetch_candles(client, config.KOSPI_INDEX_CODE, config.HISTORY_DAYS)
        idx_df = to_df(idx_raw)

        # 글로벌 지수/환율 데이터 (thread pool에서 동기 실행)
        loop = asyncio.get_event_loop()
        global_df_map = await loop.run_in_executor(None, _fetch_global_data)
        log.info("글로벌 데이터 준비 완료")

        # Phase 1: 전체 캔들 + 투자자 데이터 수집 (breadth 계산용)
        log.info("캔들+투자자 데이터 수집 중 — breadth 계산 포함")
        all_dfs: dict[str, pd.DataFrame] = {}
        all_flow_dfs: dict[str, pd.DataFrame | None] = {}
        for i, ticker in enumerate(tickers):
            try:
                raw = await _fetch_candles(client, ticker, config.HISTORY_DAYS)
                df = to_df(raw)
                if len(df) >= 80:
                    all_dfs[ticker] = df
            except Exception as e:
                log.warning("  - [%d/%d] %s 캔들 수집 실패: %s", i+1, len(tickers), ticker, e)
            flow_raw = await _fetch_investor_flows(client, ticker, config.HISTORY_DAYS)
            all_flow_dfs[ticker] = to_flow_df(flow_raw) if flow_raw else None

        # Phase 2: 날짜별 market_breadth 계산
        breadth_by_date = _compute_breadth_by_date(all_dfs)
        log.info("market_breadth 계산 완료 — %d 거래일", len(breadth_by_date))

        # Phase 3: 피처 행렬 + 레이블 생성
        X_list, y_cls_list, y_ret_list, y_days_list = [], [], [], []
        for i, (ticker, df) in enumerate(all_dfs.items()):
            try:
                X_t, dates_t = feature_matrix(ticker, df, idx_df, min_idx=60,
                                              breadth_by_date=breadth_by_date,
                                              global_df_map=global_df_map,
                                              flow_df=all_flow_dfs.get(ticker))
                if X_t.empty: continue

                # 각 row 에 대응하는 label 생성
                labels = []
                for idx_pos, at_date in enumerate(dates_t):
                    # at_date → df 상의 위치 찾기
                    df_idx = df.index[df["date"] == at_date]
                    if df_idx.empty: labels.append((None, None, None, False)); continue
                    at_idx = int(df_idx[0])
                    atr_pct = X_t.iloc[idx_pos]["atr14_pct"]
                    labels.append(label_triple_barrier(df, at_idx, atr_pct))

                keep_mask = [kp for (_,_,_, kp) in labels]
                X_kept = X_t[keep_mask].reset_index(drop=True)
                kept_labels = [l for l in labels if l[3]]
                y_cls_list.append(pd.Series([l[0] for l in kept_labels]))
                y_ret_list.append(pd.Series([l[2] for l in kept_labels]))
                y_days_list.append(pd.Series([l[1] for l in kept_labels]))
                X_list.append(X_kept)
            except Exception as e:
                log.warning("  - [%d/%d] %s 학습 데이터 생성 실패: %s", i+1, len(all_dfs), ticker, e)

        if not X_list:
            raise RuntimeError("유효 학습 데이터 없음")

        X = pd.concat(X_list, ignore_index=True)
        y_cls  = pd.concat(y_cls_list, ignore_index=True)
        y_ret  = pd.concat(y_ret_list, ignore_index=True)
        y_days = pd.concat(y_days_list, ignore_index=True)
        log.info("학습 데이터 병합 완료 — samples=%d features=%d", len(X), X.shape[1])

        models.fit(X, y_cls, y_ret, y_days)
        models.save()
        return models.meta
