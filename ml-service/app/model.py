"""
3개의 XGBoost 모델 (cls/ret/days) 학습·추론·저장.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.metrics import mean_absolute_error, roc_auc_score

from . import config
from .features import FEATURE_NAMES

log = logging.getLogger("ml-service.model")


class MlModels:
    """3모델 묶음 — 디스크에 개별 저장."""

    def __init__(self):
        self.cls: xgb.XGBClassifier | None = None
        self.ret: xgb.XGBRegressor  | None = None
        self.days: xgb.XGBRegressor | None = None
        self.meta: dict = {}

    # ── 학습 ──────────────────────────────────────────────────────────────
    def fit(self, X: pd.DataFrame, y_cls: pd.Series, y_ret: pd.Series, y_days: pd.Series):
        if len(X) < 200:
            raise ValueError(f"학습 샘플 부족: {len(X)} (최소 200 필요)")

        # 시계열 holdout: 마지막 20% 검증
        split = int(len(X) * 0.8)
        X_tr, X_va = X.iloc[:split], X.iloc[split:]
        y_cls_tr, y_cls_va = y_cls.iloc[:split], y_cls.iloc[split:]
        y_ret_tr, y_ret_va = y_ret.iloc[:split], y_ret.iloc[split:]
        y_days_tr, y_days_va = y_days.iloc[:split], y_days.iloc[split:]

        common = dict(n_estimators=300, max_depth=5, learning_rate=0.05,
                      subsample=0.85, colsample_bytree=0.85,
                      tree_method="hist", n_jobs=2)

        # 분류
        pos = int((y_cls_tr == 1).sum()); neg = len(y_cls_tr) - pos
        scale_pos_weight = (neg / pos) if pos > 0 else 1.0
        self.cls = xgb.XGBClassifier(
            **common, scale_pos_weight=scale_pos_weight,
            eval_metric="auc", use_label_encoder=False,
        )
        self.cls.fit(X_tr, y_cls_tr, eval_set=[(X_va, y_cls_va)], verbose=False)

        # 수익률 회귀
        self.ret = xgb.XGBRegressor(**common, eval_metric="mae")
        self.ret.fit(X_tr, y_ret_tr, eval_set=[(X_va, y_ret_va)], verbose=False)

        # 일수 회귀
        self.days = xgb.XGBRegressor(**common, eval_metric="mae")
        self.days.fit(X_tr, y_days_tr, eval_set=[(X_va, y_days_va)], verbose=False)

        # 검증 지표
        try:
            auc = float(roc_auc_score(y_cls_va, self.cls.predict_proba(X_va)[:, 1]))
        except Exception:
            auc = None
        mae_ret  = float(mean_absolute_error(y_ret_va,  self.ret.predict(X_va)))
        mae_days = float(mean_absolute_error(y_days_va, self.days.predict(X_va)))

        # 피처 중요도 Top-10
        importance = dict(zip(FEATURE_NAMES, self.cls.feature_importances_))
        top = sorted(importance.items(), key=lambda kv: kv[1], reverse=True)[:10]

        self.meta = {
            "trained_at": datetime.now().isoformat(timespec="seconds"),
            "samples":    int(len(X)),
            "val_auc":    auc,
            "val_mae_ret":  mae_ret,
            "val_mae_days": mae_days,
            "feature_importance_top10": [[k, float(v)] for k, v in top],
        }
        log.info(
            "[fit] samples=%d auc=%s mae_ret=%.4f mae_days=%.2f",
            len(X), f"{auc:.4f}" if auc is not None else "-", mae_ret, mae_days,
        )

    # ── 추론 ──────────────────────────────────────────────────────────────
    def predict_one(self, features: dict) -> dict:
        if not self.is_ready():
            raise RuntimeError("모델이 아직 학습되지 않았습니다. POST /train 먼저 호출하세요.")
        X = pd.DataFrame([[features.get(n, 0.0) for n in FEATURE_NAMES]], columns=FEATURE_NAMES)
        conf = float(self.cls.predict_proba(X)[0, 1])
        ret  = float(self.ret.predict(X)[0])
        days = float(self.days.predict(X)[0])
        return {
            "confidence":     conf,
            "expected_return": ret,
            "expected_days":  max(1, min(7, int(round(days)))),
        }

    def is_ready(self) -> bool:
        return self.cls is not None and self.ret is not None and self.days is not None

    # ── I/O ───────────────────────────────────────────────────────────────
    def save(self):
        if not self.is_ready(): return
        self.cls.save_model(str(config.MODEL_CLS_PATH))
        self.ret.save_model(str(config.MODEL_RET_PATH))
        self.days.save_model(str(config.MODEL_DAYS_PATH))
        Path(config.META_PATH).write_text(json.dumps(self.meta, ensure_ascii=False, indent=2))

    def load(self) -> bool:
        if not config.MODEL_CLS_PATH.exists(): return False
        try:
            self.cls = xgb.XGBClassifier()
            self.cls.load_model(str(config.MODEL_CLS_PATH))
            self.ret = xgb.XGBRegressor()
            self.ret.load_model(str(config.MODEL_RET_PATH))
            self.days = xgb.XGBRegressor()
            self.days.load_model(str(config.MODEL_DAYS_PATH))
            if config.META_PATH.exists():
                self.meta = json.loads(config.META_PATH.read_text(encoding="utf-8"))
            log.info("[load] 모델 로드 완료 — meta=%s", self.meta.get("trained_at"))
            return True
        except Exception as e:
            log.warning("[load] 모델 로드 실패: %s", e)
            self.cls = self.ret = self.days = None
            return False


# 싱글톤 인스턴스
models = MlModels()
