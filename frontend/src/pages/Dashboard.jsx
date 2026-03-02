import { useState, useEffect } from 'react'
import { getPortfolio, getBalance } from '../api/stockApi'

export default function Dashboard() {
  const [portfolio, setPortfolio] = useState([])
  const [balance, setBalance] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    Promise.all([getPortfolio(), getBalance()])
      .then(([p, b]) => { setPortfolio(p); setBalance(b) })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="loading">로딩 중...</div>
  if (error) return <div className="error">오류: {error}</div>

  return (
    <div className="page">
      <h2>대시보드</h2>

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

      <h3>보유 종목</h3>
      {portfolio.length === 0 ? (
        <p className="empty">보유 중인 종목이 없습니다.</p>
      ) : (
        <table className="stock-table">
          <thead>
            <tr>
              <th>종목</th>
              <th>수량</th>
              <th>평균단가</th>
              <th>총 투자금</th>
            </tr>
          </thead>
          <tbody>
            {portfolio.map(item => (
              <tr key={item.id}>
                <td>
                  <div className="stock-name">{item.stockName}</div>
                  <div className="ticker">{item.ticker}</div>
                </td>
                <td>{item.quantity}주</td>
                <td>{Number(item.avgPrice).toLocaleString()}원</td>
                <td>{Number(item.totalInvest).toLocaleString()}원</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
