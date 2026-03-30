import { useState, useEffect, useCallback } from 'react'
import { getEnrichedPortfolio, getBalance, getTrailingStopStatus, getTimeCutStatus, getStockPrice, getAdminStatus } from '../api/stockApi'

export default function Dashboard() {
  const [portfolio, setPortfolio] = useState([])
  const [balance, setBalance] = useState({ totalAsset: 0, cash: 0, totalPnl: 0, totalPnlRate: 0 })
  const [tsStatus, setTsStatus] = useState({})
  const [tcStatus, setTcStatus] = useState({})
  const [prices, setPositionsPrices] = useState({})
  const [adminConfig, setAdminConfig] = useState(null)
  const [loading, setLoading] = useState(true)
  const [lastUpdated, setLastUpdated] = useState(null)

  const fetchData = useCallback(async () => {
    try {
      const [p, b, ts, tc, cfg] = await Promise.all([
        getEnrichedPortfolio().catch(() => []),
        getBalance().catch(() => ({ totalAsset: 0, cash: 0, totalPnl: 0, totalPnlRate: 0 })),
        getTrailingStopStatus().catch(() => ({})),
        getTimeCutStatus().catch(() => ({})),
        getAdminStatus().catch(() => null),
      ])
      setPortfolio(p || [])
      setBalance(b)
      setTsStatus(ts || {})
      setTcStatus(tc || {})
      setAdminConfig(cfg)

      if (p && p.length > 0) {
        const results = await Promise.all(p.map(item => getStockPrice(item.ticker).catch(() => null)))
        const priceMap = {}
        results.forEach((res, i) => {
          if (res) priceMap[p[i].ticker] = res.currentPrice
        })
        setPositionsPrices(priceMap)
      }
      setLastUpdated(new Date())
    } catch (e) {
      console.error('Dashboard data fetch failed', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 10000)
    return () => clearInterval(interval)
  }, [fetchData])

  const getStrategyTag = (entryTag, stage) => {
    const style = { color: '#4caf50', border: '1px solid #1b5e20' }
    if (entryTag === 'volatility-breakout') return { ...style, label: '변동성돌파' }
    if (entryTag === 'golden-cross')        return { ...style, label: '골든크로스' }
    if (stage === 1)                        return { ...style, label: 'BB 1차 매수' }
    return { ...style, label: 'BB+RSI 완료' }
  }

  const EXTENDED_STYLE = { color: '#ce93d8', border: '1px solid #6a1b9a' }

  if (loading && portfolio.length === 0) {
    return <div className="loading">데이터를 불러오는 중입니다...</div>
  }

  return (
    <div className="page">
      <h2 style={{ marginBottom: '4px' }}>대시보드</h2>

      <div className="balance-card">
        <div className="balance-row">
          <span>총 자산</span>
          <strong>{Number(balance.totalAsset || 0).toLocaleString()}원</strong>
        </div>
        <div className="balance-row">
          <span>현금</span>
          <span>{Number(balance.cash || 0).toLocaleString()}원</span>
        </div>
        <div className="balance-row">
          <span>손익</span>
          <span className={(balance.totalPnl || 0) >= 0 ? 'up' : 'down'}>
            {(balance.totalPnl || 0) >= 0 ? '+' : ''}{Number(balance.totalPnl || 0).toLocaleString()}원 
            ({(balance.totalPnlRate || 0) >= 0 ? '+' : ''}{Number(balance.totalPnlRate || 0).toFixed(2)}%)
          </span>
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: '12px' }}>
        <h3 style={{ margin: 0 }}>보유 종목</h3>
        {lastUpdated && (
          <span className="section-note" style={{ margin: 0 }}>
            {lastUpdated.toLocaleTimeString()} 갱신
          </span>
        )}
      </div>

      <div className="holding-list">
        {portfolio.length === 0 ? (
          <p className="empty">보유 중인 종목이 없습니다.</p>
        ) : (
          portfolio.map(item => {
            const ts = tsStatus[item.ticker]
            const tc = tcStatus[item.ticker]
            const currentPrice = prices[item.ticker] || item.avgPrice || 0
            const pnl = (currentPrice - (item.avgPrice || 0)) * (item.quantity || 0)
            const pnlRate = item.avgPrice ? ((currentPrice - item.avgPrice) / item.avgPrice) * 100 : 0
            
            const stageInfo = getStrategyTag(item.entryTag, item.buyStage);
            const isExtended = tc && tc.remaining === 0 && tc.elapsed >= 3;

            return (
              <div key={item.ticker} className="holding-card">
                <div className="holding-header">
                  <span className="holding-name">{item.stockName}</span>
                  <span className="holding-ticker">{item.ticker}</span>
                </div>
                
                <div className="holding-tags">
                  <span className="history-tag" style={{ color: stageInfo.color, border: stageInfo.border, background: 'none' }}>
                    {stageInfo.label}
                  </span>
                  {isExtended && (
                    <span className="history-tag" style={{ color: EXTENDED_STYLE.color, border: EXTENDED_STYLE.border, background: 'none' }}>
                      연장 홀딩
                    </span>
                  )}
                  {item.entryTag === 'EXTREME_FEAR' && (
                    <span style={{
                      background: '#ff4d4d22',
                      color: '#ff4d4d',
                      border: '1px solid #ff4d4d',
                      borderRadius: '4px',
                      padding: '1px 6px',
                      fontSize: '10px',
                      fontWeight: 'bold',
                      marginLeft: '4px',
                    }}>극단공포</span>
                  )}
                </div>

                <div className="holding-row">
                  <span className="holding-meta">
                    {item.quantity}주 · 평단 {Number(item.avgPrice || 0).toLocaleString()}원 · 현재 {Number(currentPrice).toLocaleString()}원
                    {ts && ts.peakPrice && item.buyStage !== 1 && (
                      <span style={{ color: '#7dccff', marginLeft: 4 }}>
                        · 고점 {Number(ts.peakPrice).toLocaleString()}원
                      </span>
                    )}
                  </span>
                </div>

                <div className={`holding-pnl ${pnl >= 0 ? 'up' : 'down'}`}>
                  <span>평가손익 {pnl >= 0 ? '+' : ''}{Math.round(pnl).toLocaleString()}원</span>
                  <span className="holding-rate">수익률 {pnlRate >= 0 ? '+' : ''}{pnlRate.toFixed(1)}% {pnl >= 0 ? '▲' : '▼'}</span>
                </div>

                {ts && (
                  <div className="holding-risk ts">
                    {item.buyStage === 1 ? (
                      <span style={{ color: '#888' }}>트레일링 스탑: 2차 매수 대기 중</span>
                    ) : (
                      <>
                        트레일링 스탑: {Number(ts.stopPrice || 0).toLocaleString()}원
                        <span className="risk-remain"> │ {Math.max(0, currentPrice - (ts.stopPrice || 0)).toLocaleString()}원({currentPrice > 0 ? ((currentPrice - ts.stopPrice) / currentPrice * 100).toFixed(1) : 0}%) 남음</span>
                      </>
                    )}
                  </div>
                )}
                
                {tc && (
                  <div className="holding-risk tc">
                    타임컷: {isExtended ? 
                      <strong>조건 만족 연장 중 (D+{tc.elapsed})</strong> : 
                      <><strong>{tc.remaining}거래일</strong> 남음 (D+{tc.elapsed})</>
                    }
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
