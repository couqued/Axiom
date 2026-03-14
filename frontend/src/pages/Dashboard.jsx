import { useState, useEffect, useCallback } from 'react'
import { getEnrichedPortfolio, getBalance, getTrailingStopStatus, getTimeCutStatus, getStockPrice } from '../api/stockApi'

export default function Dashboard() {
  const [portfolio, setPortfolio] = useState([])
  const [balance, setBalance] = useState({ totalAsset: 0, cash: 0, totalPnl: 0, totalPnlRate: 0 })
  const [tsStatus, setTsStatus] = useState({})
  const [tcStatus, setTcStatus] = useState({})
  const [prices, setPositionsPrices] = useState({})
  const [loading, setLoading] = useState(true)

  const fetchData = useCallback(async () => {
    try {
      const [p, b, ts, tc] = await Promise.all([
        getEnrichedPortfolio(),
        getBalance(),
        getTrailingStopStatus().catch(() => ({})),
        getTimeCutStatus().catch(() => ({})),
      ])
      setPortfolio(p)
      setBalance(b)
      setTsStatus(ts)
      setTcStatus(tc)

      // 실시간 가격 업데이트
      if (p.length > 0) {
        const pricePromises = p.map(item => getStockPrice(item.ticker).catch(() => null))
        const results = await Promise.all(pricePromises)
        const priceMap = {}
        results.forEach((res, i) => {
          if (res) priceMap[p[i].ticker] = res.currentPrice
        })
        setPositionsPrices(priceMap)
      }
    } catch (e) {
      console.error('Data fetch failed', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
    const interval = setInterval(fetchData, 10000)
    return () => clearInterval(interval)
  }, [fetchData])

  const TAG_STYLES = {
    1: { label: 'BB 1차 매수', color: '#ffd966', border: '1px solid #7a6419' },
    2: { label: 'BB+RSI 완료', color: '#ffd966', border: '1px solid #7a6419' },
    'extended': { label: '연장 홀딩', color: '#ce93d8', border: '1px solid #6a1b9a' },
  }

  if (loading && portfolio.length === 0) return <div className="loading">로딩 중...</div>

  return (
    <div className="page">
      <h2>대시보드</h2>

      <div className="balance-card">
        <div className="balance-row">
          <span>총 자산</span>
          <strong>{balance.totalAsset.toLocaleString()}원</strong>
        </div>
        <div className="balance-row">
          <span>현금</span>
          <span>{balance.cash.toLocaleString()}원</span>
        </div>
        <div className="balance-row">
          <span>손익</span>
          <span className={balance.totalPnl >= 0 ? 'up' : 'down'}>
            {balance.totalPnl >= 0 ? '+' : ''}{balance.totalPnl.toLocaleString()}원 
            ({balance.totalPnlRate >= 0 ? '+' : ''}{balance.totalPnlRate.toFixed(2)}%)
          </span>
        </div>
      </div>

      <h3>보유 종목 상태</h3>
      <div className="holding-list">
        {portfolio.length === 0 ? (
          <p className="empty">보유 종목이 없습니다.</p>
        ) : (
          portfolio.map(item => {
            const ts = tsStatus[item.ticker]
            const tc = tcStatus[item.ticker]
            const currentPrice = prices[item.ticker] || item.avgPrice
            const pnl = (currentPrice - item.avgPrice) * item.quantity
            const pnlRate = ((currentPrice - item.avgPrice) / item.avgPrice) * 100
            
            const buyStage = item.buyStage || 2;
            const stageStyle = TAG_STYLES[buyStage];
            const isExtended = tc && tc.remaining === 0 && tc.elapsed >= 3;

            return (
              <div key={item.ticker} className="holding-card">
                <div className="holding-header">
                  <span className="holding-name">{item.stockName}</span>
                  <span className="holding-ticker">{item.ticker}</span>
                </div>
                
                <div className="holding-tags">
                  <span className="history-tag" style={stageStyle}>{stageStyle.label}</span>
                  {isExtended && (
                    <span className="history-tag" style={TAG_STYLES['extended']}>연장 홀딩</span>
                  )}
                </div>

                <div className="holding-row">
                  <span className="holding-meta">
                    {item.quantity}주 · 평단 {Number(item.avgPrice).toLocaleString()}원 · 현재 {Number(currentPrice).toLocaleString()}원
                    {ts && ts.peakPrice && (
                      <span style={{ color: '#7dccff', marginLeft: 4 }}>
                        · 고점 {Number(ts.peakPrice).toLocaleString()}원
                      </span>
                    )}
                  </span>
                </div>

                <div className={`holding-pnl ${pnl >= 0 ? 'up' : 'down'}`}>
                  <span>평가손익 {pnl >= 0 ? '+' : ''}{pnl.toLocaleString()}원</span>
                  <span className="holding-rate">수익률 {pnlRate >= 0 ? '+' : ''}{pnlRate.toFixed(1)}% {pnl >= 0 ? '▲' : '▼'}</span>
                </div>

                {ts && (
                  <div className="holding-risk ts">
                    {buyStage === 1 ? (
                      <span style={{ color: '#888' }}>트레일링 스탑: 2차 매수 대기 중</span>
                    ) : (
                      <>
                        트레일링 스탑: {Number(ts.stopPrice).toLocaleString()}원
                        <span className="risk-remain"> │ {(currentPrice - ts.stopPrice).toLocaleString()}원({((currentPrice - ts.stopPrice) / currentPrice * 100).toFixed(1)}%) 남음</span>
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
