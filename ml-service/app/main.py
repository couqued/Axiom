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
from typing import Any

import httpx
import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from . import config
from .features import (
    FEATURE_NAMES, build_feature_vector, feature_matrix, to_df,
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


class BatchPredictRequest(BaseModel):
    candles: dict[str, list[dict[str, Any]]] = Field(default_factory=dict)
    indexCandles: list[dict[str, Any]] = Field(default_factory=list)
    marketBreadth: float = Field(default=0.5, ge=0.0, le=1.0)


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
    return {"ready": models.is_ready(), "meta": models.meta}


@app.post("/predict", response_model=TradePlanResponse)
def predict(req: PredictRequest):
    return _predict_one(req.ticker, req.candles, req.indexCandles, req.marketBreadth)


@app.post("/predict/batch")
def predict_batch(req: BatchPredictRequest):
    result: dict[str, dict] = {}
    for ticker, candles in req.candles.items():
        try:
            result[ticker] = _predict_one(ticker, candles, req.indexCandles, req.marketBreadth).dict()
        except HTTPException as ex:
            result[ticker] = {
                "ticker": ticker, "confidence": 0.0, "mlScore": 0.0,
                "reason": f"ERROR: {ex.detail}",
            }
    return result


@app.post("/train")
async def train():
    try:
        meta = await _train_all()
        return {"status": "ok", "meta": meta}
    except Exception as e:
        log.exception("학습 실패")
        raise HTTPException(status_code=500, detail=str(e))


# ── 추론 ────────────────────────────────────────────────────────────────────

def _predict_one(ticker: str, candles: list[dict], index_candles: list[dict],
                 market_breadth: float = 0.5) -> TradePlanResponse:
    if not models.is_ready():
        return TradePlanResponse(ticker=ticker, confidence=0.0, mlScore=0.0,
                                 reason="모델 미학습")

    df = to_df(candles)
    idx = to_df(index_candles)
    if len(df) < 60:
        return TradePlanResponse(ticker=ticker, confidence=0.0, mlScore=0.0,
                                 reason=f"캔들 부족 ({len(df)})")

    at_idx = len(df) - 1
    feats = build_feature_vector(ticker, df, idx, at_idx, market_breadth)
    try:
        pred = models.predict_one(feats)
    except Exception as e:
        return TradePlanResponse(ticker=ticker, confidence=0.0, mlScore=0.0,
                                 reason=f"추론 오류: {e}")

    entry = float(df["close"].iloc[at_idx])
    atr_pct = feats.get("atr14_pct", 0.02)
    vol_scale = max(config.VOL_SCALE_MIN, min(config.VOL_SCALE_MAX, atr_pct / config.ATR_BASE))
    sl_pct = config.SL_BASE_PCT * vol_scale
    # TP 는 모델 예측값과 ATR 기반 기본값 중 더 큰 쪽 (최소 +2%)
    expected_ret = max(pred["expected_return"], config.TP_BASE_PCT)
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


async def _train_all() -> dict:
    async with httpx.AsyncClient(base_url=config.MARKET_SERVICE_URL) as client:
        tickers = await _fetch_watch_tickers(client)
        if not tickers:
            raise RuntimeError("watch-tickers 비어 있음 — market-service 를 먼저 기동하세요.")

        log.info("학습 시작 — %d 종목, %d일 히스토리", len(tickers), config.HISTORY_DAYS)

        idx_raw = await _fetch_candles(client, config.KOSPI_INDEX_CODE, config.HISTORY_DAYS)
        idx_df = to_df(idx_raw)

        # Phase 1: 전체 캔들 수집 (breadth 계산용)
        log.info("캔들 수집 중 — breadth 계산 포함")
        all_dfs: dict[str, pd.DataFrame] = {}
        for i, ticker in enumerate(tickers):
            try:
                raw = await _fetch_candles(client, ticker, config.HISTORY_DAYS)
                df = to_df(raw)
                if len(df) >= 80:
                    all_dfs[ticker] = df
            except Exception as e:
                log.warning("  - [%d/%d] %s 캔들 수집 실패: %s", i+1, len(tickers), ticker, e)

        # Phase 2: 날짜별 market_breadth 계산
        breadth_by_date = _compute_breadth_by_date(all_dfs)
        log.info("market_breadth 계산 완료 — %d 거래일", len(breadth_by_date))

        # Phase 3: 피처 행렬 + 레이블 생성
        X_list, y_cls_list, y_ret_list, y_days_list = [], [], [], []
        for i, (ticker, df) in enumerate(all_dfs.items()):
            try:
                X_t, dates_t = feature_matrix(ticker, df, idx_df, min_idx=60,
                                              breadth_by_date=breadth_by_date)
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
