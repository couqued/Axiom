import { useState, useEffect } from 'react'
import { getOrders } from '../api/stockApi'

const STATUS_KO = { PENDING: '대기', FILLED: '체결', CANCELLED: '취소', FAILED: '실패' }

export default function TradeHistory() {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    getOrders()
      .then(setOrders)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const refresh = () => {
    setLoading(true)
    getOrders().then(setOrders).catch(e => setError(e.message)).finally(() => setLoading(false))
  }

  if (loading) return <div className="loading">로딩 중...</div>
  if (error) return <div className="error">오류: {error}</div>

  return (
    <div className="page">
      <div className="page-header">
        <h2>매매 내역</h2>
        <button className="refresh-btn" onClick={refresh}>새로고침</button>
      </div>

      {orders.length === 0 ? (
        <p className="empty">주문 내역이 없습니다.</p>
      ) : (
        <table className="stock-table">
          <thead>
            <tr>
              <th>일시</th>
              <th>종목</th>
              <th>구분</th>
              <th>수량</th>
              <th>단가</th>
              <th>금액</th>
              <th>상태</th>
            </tr>
          </thead>
          <tbody>
            {orders.map(o => (
              <tr key={o.id}>
                <td>{new Date(o.createdAt).toLocaleString('ko-KR')}</td>
                <td>
                  <div className="stock-name">{o.stockName}</div>
                  <div className="ticker">{o.ticker}</div>
                </td>
                <td>
                  <span className={`order-type ${o.orderType === 'BUY' ? 'buy' : 'sell'}`}>
                    {o.orderType === 'BUY' ? '매수' : '매도'}
                  </span>
                </td>
                <td>{o.quantity}주</td>
                <td>{Number(o.price).toLocaleString()}원</td>
                <td>{Number(o.totalAmount).toLocaleString()}원</td>
                <td>{STATUS_KO[o.status] || o.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
