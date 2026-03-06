import { useState, useEffect } from 'react'
import { getOrders } from '../api/stockApi'

const CLOSE_REASON_KO = {
  SIGNAL: '전략 신호',
  TRAILING_STOP: '트레일링 스탑',
  TIME_CUT: '타임컷',
  FORCE_EXIT: '강제청산 (15:20)',
}
const STRATEGY_KO = {
  'golden-cross': '골든크로스',
  'rsi-bollinger': 'RSI+볼린저',
  'volatility-breakout': '변동성 돌파',
}
const MARKET_KO = { BULLISH: '상승장', SIDEWAYS: '횡보장' }

function formatDate(dt) {
  if (!dt) return ''
  const d = new Date(dt)
  return d.toLocaleDateString('ko-KR', { month: '2-digit', day: '2-digit' }).replace('. ', '.').replace('.', '') +
    ' ' + d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })
}

export default function TradeHistory() {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const load = () => {
    setLoading(true)
    getOrders()
      .then(setOrders)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  if (loading) return <div className="loading">로딩 중...</div>
  if (error) return <div className="error">오류: {error}</div>

  return (
    <div className="page">
      <div className="page-header">
        <h2>매매 내역</h2>
        <button className="refresh-btn" onClick={load}>새로고침</button>
      </div>

      {orders.length === 0 ? (
        <p className="empty">매매 내역이 없습니다.</p>
      ) : (
        <div className="history-list">
          {orders.map(o => {
            const isBuy = o.orderType === 'BUY'
            const strategyLabel = o.strategyName ? STRATEGY_KO[o.strategyName] ?? o.strategyName : null
            const marketLabel = o.marketState ? MARKET_KO[o.marketState] ?? o.marketState : null
            const closeLabel = o.closeReason ? CLOSE_REASON_KO[o.closeReason] ?? o.closeReason : null

            return (
              <div key={o.id} className={`history-card ${isBuy ? 'buy' : 'sell'}`}>
                <div className="history-card-header">
                  <span className={`order-type ${isBuy ? 'buy' : 'sell'}`}>
                    {isBuy ? '매수' : '매도'}
                  </span>
                  <span className="history-date">{formatDate(o.createdAt)}</span>
                  <span className="history-status">{o.status === 'FILLED' ? '●체결' : o.status}</span>
                </div>
                <div className="history-stock">
                  <span className="stock-name">{o.stockName}</span>
                  <span className="ticker">{o.ticker}</span>
                </div>
                <div className="history-amount">
                  {o.quantity}주 × {Number(o.price).toLocaleString()}원 ={' '}
                  <strong>{Number(o.totalAmount).toLocaleString()}원</strong>
                </div>
                {(strategyLabel || closeLabel || marketLabel) && (
                  <div className="history-meta">
                    {isBuy && strategyLabel && (
                      <span className="history-tag strategy">{strategyLabel}</span>
                    )}
                    {!isBuy && closeLabel && (
                      <span className="history-tag close">{closeLabel}</span>
                    )}
                    {marketLabel && (
                      <span className="history-tag market">{marketLabel}</span>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
