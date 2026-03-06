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
export const getOrders = () => request('/api/orders')

// 포트폴리오
export const getPortfolio = () => request('/api/portfolio')

// 계좌 잔고
export const getBalance = () => request('/api/portfolio/balance')

// 시장 상태 조회
export const getMarketState = () => request('/api/strategy/market-state')

// 시장 상태 수동 갱신
export const refreshMarketState = () =>
  request('/api/strategy/refresh-market-state', { method: 'POST' })

// 전략 즉시 실행
export const runStrategy = () =>
  request('/api/strategy/run', { method: 'POST' })

// Slack 알림 테스트
export const testSlack = () =>
  request('/api/strategy/test-slack', { method: 'POST' })

// 관리자 — 현재 상태 조회
export const getAdminStatus = () =>
  request('/api/strategy/admin/status')

// 관리자 — 매매 중단
export const pauseTrading = () =>
  request('/api/strategy/admin/pause', { method: 'POST' })

// 관리자 — 매매 재개
export const resumeTrading = () =>
  request('/api/strategy/admin/resume', { method: 'POST' })

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
