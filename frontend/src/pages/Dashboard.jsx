import { useState, useEffect } from 'react'
import { getPortfolio, getBalance, getStockPrice, getTrailingStopStatus, getTimeCutStatus } from '../api/stockApi'

export default function Dashboard() {
  const [portfolio, setPortfolio] = useState([])
  const [balance, setBalance] = useState(null)
  const [prices, setPrices] = useState({})         // { ticker: currentPrice }
  const [tsStatus, setTsStatus] = useState({})     // { ticker: { peakPrice, stopPrice } }
  const [tcStatus, setTcStatus] = useState({})     // { ticker: { buyDate, elapsed, remaining } }
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    setLoading(true)
    Promise.all([
      getPortfolio(),
      getBalance(),
      getTrailingStopStatus().catch(() => ({})),  // 실패해도 빈 객체로 처리
      getTimeCutStatus().catch(() => ({})),        // 실패해도 빈 객체로 처리
    ])
      .then(([p, b, ts, tc]) => {
        setPortfolio(p)
        setBalance(b)
        setTsStatus(ts)
        setTcStatus(tc)
        // 보유 종목별 현재가 병렬 조회
        if (p.length > 0) {
          return Promise.all(p.map(item => getStockPrice(item.ticker).catch(() => null)))
            .then(priceResults => {
              const priceMap = {}
              p.forEach((item, i) => {
                const pd = priceResults[i]
                if (pd?.currentPrice) priceMap[item.ticker] = Number(pd.currentPrice)
              })
              setPrices(priceMap)
            })
        }
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="loading">로딩 중...</div>
  if (error) return <div className="error">오류: {error}</div>

  return (
    <div className="page">
      <h2>대시보드</h2>

      {/* 잔고 카드 */}
      {balance && (
        <div className="balance-card">
          <div className="balance-row">
            <span>총 자산</span>
            <strong>{Number(balance.totalBalance).toLocaleString()}원</strong>
          </div>
          <div className="balance-row">
            <span>현금</span>
            <span>{Number(balance.cashBalance).toLocaleString()}원</span>
          </div>
          <div className="balance-row">
            <span>주식 평가액</span>
            <span>{Number(balance.stockBalance).toLocaleString()}원</span>
          </div>
          <div className="balance-row">
            <span>손익</span>
            <span className={balance.profitLoss >= 0 ? 'up' : 'down'}>
              {balance.profitLoss >= 0 ? '+' : ''}{Number(balance.profitLoss).toLocaleString()}원
              ({balance.profitLossRate}%)
            </span>
          </div>
          {balance.mock && <p className="mock-badge">MOCK 데이터</p>}
        </div>
      )}

      {/* 보유 종목 */}
      <h3>보유 종목</h3>
      {portfolio.length === 0 ? (
        <div className="holding-empty">
          <div className="holding-empty-header">
            <span>종목</span><span>수량</span><span>평균단가</span><span>현재가</span><span>평가손익</span>
          </div>
          <p className="empty" style={{ padding: '20px 0', fontSize: '13px' }}>
            자동매매 시작 시 보유 종목이 표시됩니다
          </p>
        </div>
      ) : (
        <div className="holding-list">
          {portfolio.map(item => {
            const currentPrice = prices[item.ticker]
            const avgPrice = Number(item.avgPrice)
            const qty = item.quantity
            const pnl = currentPrice != null ? (currentPrice - avgPrice) * qty : null
            const pnlRate = currentPrice != null ? ((currentPrice - avgPrice) / avgPrice * 100) : null
            const ts = tsStatus[item.ticker]
            const tc = tcStatus[item.ticker]

            // 트레일링 스탑 남은 금액/% (현재가 있을 때만)
            let remainingAmt = null
            let remainingPct = null
            if (ts && currentPrice) {
              const stopPrice = Number(ts.stopPrice)
              remainingAmt = currentPrice - stopPrice
              remainingPct = (remainingAmt / currentPrice) * 100
            }

            return (
              <div key={item.ticker} className="holding-card">
                <div className="holding-header">
                  <span className="holding-name">{item.stockName}</span>
                  <span className="holding-ticker">{item.ticker}</span>
                </div>
                <div className="holding-row">
                  <span className="holding-meta">
                    {qty}주 · 평균 {avgPrice.toLocaleString()}원
                    {currentPrice != null && ` · 현재 ${currentPrice.toLocaleString()}원`}
                  </span>
                </div>
                {pnl != null && (
                  <div className={`holding-pnl ${pnl >= 0 ? 'up' : 'down'}`}>
                    <span>평가손익 {pnl >= 0 ? '+' : ''}{Math.round(pnl).toLocaleString()}원</span>
                    <span className="holding-rate">
                      수익률 {pnlRate >= 0 ? '+' : ''}{pnlRate.toFixed(1)}% {pnl >= 0 ? '▲' : '▼'}
                    </span>
                  </div>
                )}
                {ts && (
                  <div className="holding-risk ts">
                    트레일링 스탑: {Number(ts.stopPrice).toLocaleString()}원
                    {remainingAmt != null && (
                      <span className="risk-remain">
                        {' '}│ {Math.round(remainingAmt).toLocaleString()}원({remainingPct.toFixed(1)}%) 남음
                      </span>
                    )}
                    <span className="risk-note"> [5분 주기]</span>
                  </div>
                )}
                {tc && (
                  <div className="holding-risk tc">
                    타임컷: <strong>{tc.remaining}거래일</strong> 남음 (경과 {tc.elapsed}일)
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
