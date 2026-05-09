async function request(url, options = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const err = await res.text()
    throw new Error(err || `HTTP ${res.status}`)
  }
  return res.json()
}

// 현재가 조회
export const getStockPrice = (ticker) =>
  request(`/api/market/stocks/${ticker}/price`)

// 종목 검색
export const searchStocks = (query) =>
  request(`/api/market/stocks/search?query=${encodeURIComponent(query)}`)

// 매수 주문
export const buyStock = (body) =>
  request('/api/orders/buy', { method: 'POST', body: JSON.stringify({ ...body, orderType: 'BUY' }) })

// 매도 주문
export const sellStock = (body) =>
  request('/api/orders/sell', { method: 'POST', body: JSON.stringify({ ...body, orderType: 'SELL' }) })

// 주문 내역
export const getOrders = () =>
  request('/api/orders')

// 포트폴리오
export const getPortfolio = () =>
  request('/api/portfolio')

// 전략 메타데이터가 포함된 포트폴리오 (buyStage 등)
export const getEnrichedPortfolio = () =>
  request('/api/strategy/portfolio')

// 계좌 잔고
export const getBalance = () =>
  request('/api/portfolio/balance').then(r => ({
    totalAsset:   r.totalBalance   ?? 0,
    cash:         r.cashBalance    ?? 0,
    totalPnl:     r.profitLoss     ?? 0,
    totalPnlRate: r.profitLossRate ?? 0,
  }))

// 시장 상태 조회
export const getMarketState = () => request('/api/strategy/market-state')

// 시장 상태 수동 갱신
export const refreshMarketState = () =>
  request('/api/strategy/refresh-market-state', { method: 'POST' })

// 전략 즉시 실행 (백그라운드 시작, 202 반환)
export const runStrategy = () =>
  request('/api/strategy/run', { method: 'POST' })

// 수동 실행 상태 폴링
export const getRunStatus = () =>
  request('/api/strategy/run-status')

// 관리자 — 현재 상태 조회
export const getAdminStatus = () =>
  request('/api/strategy/admin/status')

// 관리자 — 매매 중단
export const pauseTrading = () =>
  request('/api/strategy/admin/pause', { method: 'POST' })

// 관리자 — 매매 재개
export const resumeTrading = () =>
  request('/api/strategy/admin/resume', { method: 'POST' })

// 관리자 — 매도 중지
export const pauseSellTrading = () =>
  request('/api/strategy/admin/pause-sell', { method: 'POST' })

// 관리자 — 매도 재개
export const resumeSellTrading = () =>
  request('/api/strategy/admin/resume-sell', { method: 'POST' })

// 관리자 — ML 매수 중단
export const pauseMlTrading = () =>
  request('/api/strategy/admin/pause-ml', { method: 'POST' })

// 관리자 — ML 매수 재개
export const resumeMlTrading = () =>
  request('/api/strategy/admin/resume-ml', { method: 'POST' })

// 관리자 — ML 매도 중지
export const pauseMlSellTrading = () =>
  request('/api/strategy/admin/pause-ml-sell', { method: 'POST' })

// 관리자 — ML 매도 재개
export const resumeMlSellTrading = () =>
  request('/api/strategy/admin/resume-ml-sell', { method: 'POST' })

// 관리자 — 투자 설정 변경
export const updateAdminConfig = (body) =>
  request('/api/strategy/admin/config', { method: 'PATCH', body: JSON.stringify(body) })

// 트레일링 스탑 현황 — { ticker: { peakPrice, stopPrice } }
export const getTrailingStopStatus = () =>
  request('/api/strategy/admin/trailing-stop-status')

// 타임 컷 현황 — { ticker: { buyDate, elapsed, remaining } }
export const getTimeCutStatus = () =>
  request('/api/strategy/admin/time-cut-status')

// 투자 스킵 종목 목록 (최근 N일, 기본 7일)
export const getSkippedSignals = (days = 7) =>
  request(`/api/orders/skipped?days=${days}`)

// BUY 랭킹 조회 (마지막 전략 실행 기준)
export const getEvalRanking = () => request('/api/strategy/eval-ranking')

// 당일 시간별 전략 실행 이력
export const getRunHistory = () => request('/api/strategy/run-history')

// 매수 신호 근접도 캐시 조회 ({items, computedAt, running})
export const getSignalGap = () => request('/api/strategy/signal-gap')

// 매수 신호 근접도 백그라운드 계산 트리거
export const triggerSignalGapRefresh = (top = 10) =>
  request(`/api/strategy/signal-gap/refresh?top=${top}`, { method: 'POST' })

// 관리자 — 수동 청산 (tickers: string[])
export const manualExit = (tickers) =>
  request('/api/strategy/admin/manual-exit', {
    method: 'POST',
    body: JSON.stringify({ tickers }),
  })

// 관리자 — MTS/외부 매도 처리 (포트폴리오 DB + 전략 상태 정리, KIS 주문 없음)
export const markSold = (tickers) =>
  request('/api/strategy/admin/mark-sold', {
    method: 'POST',
    body: JSON.stringify({ tickers }),
  })

export const mlDryRun = () =>
  request('/api/strategy/admin/ml/dry-run')

export const mlRetrain = () =>
  request('/api/strategy/admin/ml/retrain', { method: 'POST' })

// ML 성과 — 모델 정보 + 승률 요약
export const getMlPerformanceSummary = () =>
  request('/api/strategy/ml-performance/summary')

// ML 성과 — 거래 이력 (페이지네이션)
export const getMlTradeHistory = (page = 0, size = 20) =>
  request(`/api/strategy/ml-performance/trades?page=${page}&size=${size}`)

// ML 성과 — 확신도 구간별 통계
export const getMlConfidenceTiers = () =>
  request('/api/strategy/ml-performance/confidence-tiers')

// 워치리스트 관리
export const getWatchlist = () =>
  request('/api/strategy/admin/watchlist')

export const getWatchlistCounts = () =>
  request('/api/strategy/admin/watchlist/counts')

export const syncWatchlist = () =>
  request('/api/strategy/admin/watchlist/sync', { method: 'POST' })

export const excludeTicker = (ticker, reason) =>
  request(`/api/strategy/admin/watchlist/${ticker}/exclude`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })

export const restoreTicker = (ticker) =>
  request(`/api/strategy/admin/watchlist/${ticker}/exclude`, { method: 'DELETE' })

export const addWatchTicker = (ticker, stockName, marketIndex) =>
  request(`/api/strategy/admin/watchlist/${ticker}/add`, {
    method: 'POST',
    body: JSON.stringify({ stockName, marketIndex }),
  })

export const runDailyWatchReview = () =>
  request('/api/strategy/admin/watchlist/review/daily', { method: 'POST' })

export const runWeeklyWatchReview = () =>
  request('/api/strategy/admin/watchlist/review/weekly', { method: 'POST' })

export const runQuarterlyWatchReview = () =>
  request('/api/strategy/admin/watchlist/review/quarterly', { method: 'POST' })
