import os
from pathlib import Path

MARKET_SERVICE_URL = os.getenv("MARKET_SERVICE_URL", "http://localhost:8081")
MODEL_DIR = Path(os.getenv("MODEL_DIR", "./models"))
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# 모델 파일 경로
MODEL_CLS_PATH  = MODEL_DIR / "model_cls.json"
MODEL_RET_PATH  = MODEL_DIR / "model_ret.json"
MODEL_DAYS_PATH = MODEL_DIR / "model_days.json"
META_PATH       = MODEL_DIR / "meta.json"

# 학습/추론 파라미터
HISTORY_DAYS       = 750        # 학습용 과거 캔들 일수
INFERENCE_CANDLES  = 80         # 추론 시 필요한 최소 캔들
HORIZON_DAYS       = 7          # triple-barrier 최대 보유 거래일
TP_BASE_PCT        = 0.03       # TP 기본 +3%
SL_BASE_PCT        = 0.02       # SL 기본 -2%
VOL_SCALE_MIN      = 1.0
VOL_SCALE_MAX      = 2.5
ATR_BASE           = 0.02       # ATR_pct 기준값

# 시장 지수 코드 (코스피)
KOSPI_INDEX_CODE = "0001"
