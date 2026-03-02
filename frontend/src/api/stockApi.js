const GATEWAY = 'http://localhost:8080'

async function request(url, options = {}) {
  const res = await fetch(GATEWAY + url, {
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
