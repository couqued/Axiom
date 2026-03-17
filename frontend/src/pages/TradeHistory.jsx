import { useState, useEffect } from 'react'
import { getOrders, getStockPrice, getAdminStatus } from '../api/stockApi'

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

// V2 뱃지 색상 (대시보드와 동일)
const TAG_STYLES = {
  'BB 1차 매수': { color: '#ffd966', border: '1px solid #7a6419', background: 'none' },
  'BB+RSI 완료': { color: '#ffd966', border: '1px solid #7a6419', background: 'none' },
  '연장 홀딩':   { color: '#ce93d8', border: '1px solid #6a1b9a', background: 'none' },
}

function formatDate(dt) {
  if (!dt) return ''
  const d = new Date(dt + 'Z')
  const kst = new Date(d.getTime() + 9 * 60 * 60 * 1000)
  const yy  = String(kst.getUTCFullYear()).slice(2)
  const mm  = String(kst.getUTCMonth() + 1).padStart(2, '0')
  const dd  = String(kst.getUTCDate()).padStart(2, '0')
  const hh  = String(kst.getUTCHours()).padStart(2, '0')
  const min = String(kst.getUTCMinutes()).padStart(2, '0')
  return `${yy}.${mm}.${dd}. ${hh}:${min}`
}

export default function TradeHistory() {
  const [orders, setOrders] = useState([])
  const [stockNames, setStockNames] = useState({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [activeMode, setActiveMode] = useState(null)
  const [modeFilter, setModeFilter] = useState(null)

  useEffect(() => {
    getAdminStatus()
      .then(s => {
        const mode = s.tradingMode || 'paper'
        setActiveMode(mode)
        setModeFilter(mode)
      })
      .catch(() => setModeFilter('paper'))
  }, [])

  const load = (mode) => {
    setLoading(true)
    getOrders(mode)
      .then(data => {
        setOrders(data)
        const numericTickers = [...new Set(
          data.filter(o => !o.stockName || /^\d+$/.test(o.stockName)).map(o => o.ticker)
        )]
        if (numericTickers.length > 0) {
          Promise.all(numericTickers.map(t => getStockPrice(t).catch(() => null)))
            .then(results => {
              const nameMap = {}
              numericTickers.forEach((t, i) => {
                if (results[i]?.stockName) nameMap[t] = results[i].stockName
              })
              setStockNames(nameMap)
            })
        }
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    if (modeFilter !== null) load(modeFilter)
  }, [modeFilter])

  const handleModeChange = (mode) => {
    setModeFilter(mode)
    setError(null)
  }

  if (modeFilter === null) return <div className="loading">로딩 중...</div>

  return (
    <div className="page">
      <div className="page-header">
        <h2>매매 내역</h2>
        <button className="refresh-btn" onClick={() => load(modeFilter)}>새로고침</button>
      </div>

      <div className="mode-filter-tabs">
        <button className={`mode-tab-btn ${modeFilter === 'paper' ? 'active' : ''}`} onClick={() => handleModeChange('paper')}>
          모의투자 {activeMode === 'paper' && <span className="active-dot" />}
        </button>
        <button className={`mode-tab-btn real ${modeFilter === 'real' ? 'active' : ''}`} onClick={() => handleModeChange('real')}>
          운영 (실계좌) {activeMode === 'real' && <span className="active-dot" />}
        </button>
      </div>

      {loading && <div className="loading">로딩 중...</div>}
      {error && <div className="error">오류: {error}</div>}

      {!loading && !error && (
        orders.length === 0 ? (
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
                    <span className={`order-type ${isBuy ? 'buy' : 'sell'}`}>{isBuy ? '매수' : '매도'}</span>
                    <span className="history-date">{formatDate(o.createdAt)}</span>
                    <span className="history-status">{o.status === 'FILLED' ? '●체결' : o.status}</span>
                  </div>
                  <div className="history-stock">
                    <span className="stock-name">{(!o.stockName || /^\d+$/.test(o.stockName)) ? (stockNames[o.ticker] || o.ticker) : o.stockName}</span>
                    <span className="ticker">{o.ticker}</span>
                  </div>
                  <div className="history-amount">
                    {o.quantity}주 × {Number(o.price).toLocaleString()}원 ={' '}
                    <strong>{Number(o.totalAmount).toLocaleString()}원</strong>
                  </div>
                  {(strategyLabel || closeLabel || marketLabel) && (
                    <div className="history-meta">
                      {isBuy && strategyLabel && <span className="history-tag strategy">{strategyLabel}</span>}
                      {!isBuy && closeLabel && <span className="history-tag close">{closeLabel}</span>}
                      {marketLabel && <span className="history-tag market">{marketLabel}</span>}
                      {/* V2 매수 단계 태그 표시 */}
                      {isBuy && o.reason && o.reason.includes('BB 1차') && (
                        <span className="history-tag" style={TAG_STYLES['BB 1차 매수']}>BB 1차 매수</span>
                      )}
                      {isBuy && o.reason && o.reason.includes('BB+RSI 완료') && (
                        <span className="history-tag" style={TAG_STYLES['BB+RSI 완료']}>BB+RSI 완료</span>
                      )}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )
      )}
    </div>
  )
}
