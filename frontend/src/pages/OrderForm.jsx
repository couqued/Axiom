import { useState } from 'react'
import { searchStocks, getStockPrice, buyStock, sellStock } from '../api/stockApi'

export default function OrderForm() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [selected, setSelected] = useState(null)
  const [price, setPrice] = useState(null)
  const [quantity, setQuantity] = useState('')
  const [orderPrice, setOrderPrice] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  const handleSearch = async (e) => {
    e.preventDefault()
    if (!query.trim()) return
    setResults(await searchStocks(query))
    setSelected(null); setPrice(null)
  }

  const handleSelect = async (stock) => {
    setSelected(stock)
    setResults([])
    setQuery(stock.stockName)
    const p = await getStockPrice(stock.ticker)
    setPrice(p)
    setOrderPrice(p.currentPrice)
  }

  const handleOrder = async (type) => {
    if (!selected || !quantity) return
    setLoading(true); setError(null); setResult(null)
    try {
      const body = {
        ticker: selected.ticker,
        stockName: selected.stockName,
        quantity: parseInt(quantity),
        price: parseFloat(orderPrice),
      }
      const res = type === 'BUY' ? await buyStock(body) : await sellStock(body)
      setResult({ type, ...res })
      setQuantity(''); setOrderPrice(price?.currentPrice || '')
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  const totalAmount = quantity && orderPrice
    ? (parseInt(quantity) * parseFloat(orderPrice)).toLocaleString()
    : '-'

  return (
    <div className="page">
      <h2>주문</h2>

      <form className="search-form" onSubmit={handleSearch}>
        <input
          value={query}
          onChange={e => { setQuery(e.target.value); setSelected(null) }}
          placeholder="종목명 또는 코드 검색"
        />
        <button type="submit">검색</button>
      </form>

      {results.length > 0 && (
        <ul className="search-results">
          {results.map(s => (
            <li key={s.ticker} onClick={() => handleSelect(s)}>
              <span className="stock-name">{s.stockName}</span>
              <span className="ticker">{s.ticker}</span>
            </li>
          ))}
        </ul>
      )}

      {selected && (
        <div className="order-panel">
          <div className="order-stock-info">
            <strong>{selected.stockName}</strong>
            <span className="ticker">{selected.ticker}</span>
            {price && (
              <span className={`current-price ${price.changeAmount >= 0 ? 'up' : 'down'}`}>
                {Number(price.currentPrice).toLocaleString()}원
              </span>
            )}
            {price?.mock && <span className="mock-badge">MOCK</span>}
          </div>

          <div className="order-fields">
            <label>
              수량 (주)
              <input
                type="number"
                min="1"
                value={quantity}
                onChange={e => setQuantity(e.target.value)}
                placeholder="0"
              />
            </label>
            <label>
              주문가 (원)
              <input
                type="number"
                value={orderPrice}
                onChange={e => setOrderPrice(e.target.value)}
                placeholder="0"
              />
            </label>
          </div>

          <div className="order-total">
            예상 거래금액: <strong>{totalAmount}원</strong>
          </div>

          <div className="order-buttons">
            <button className="buy-btn" onClick={() => handleOrder('BUY')} disabled={loading}>
              매수
            </button>
            <button className="sell-btn" onClick={() => handleOrder('SELL')} disabled={loading}>
              매도
            </button>
          </div>
        </div>
      )}

      {loading && <div className="loading">주문 처리 중...</div>}

      {result && (
        <div className={`order-result ${result.type === 'BUY' ? 'buy' : 'sell'}`}>
          <strong>{result.type === 'BUY' ? '매수' : '매도'} 주문 완료</strong>
          <div>{result.stockName} {result.quantity}주 @ {Number(result.price).toLocaleString()}원</div>
          <div>주문번호: {result.kisOrderId}</div>
          <div>상태: {result.status}</div>
        </div>
      )}

      {error && <div className="error">{error}</div>}
    </div>
  )
}
