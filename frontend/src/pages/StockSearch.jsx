import { useState } from 'react'
import { searchStocks, getStockPrice } from '../api/stockApi'

export default function StockSearch() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [price, setPrice] = useState(null)
  const [selectedTicker, setSelectedTicker] = useState(null)
  const [loading, setLoading] = useState(false)

  const handleSearch = async (e) => {
    e.preventDefault()
    if (!query.trim()) return
    setLoading(true)
    try {
      setResults(await searchStocks(query))
      setPrice(null)
    } finally {
      setLoading(false)
    }
  }

  const handleSelectStock = async (ticker) => {
    setSelectedTicker(ticker)
    setLoading(true)
    try {
      setPrice(await getStockPrice(ticker))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="page">
      <h2>종목 검색</h2>
      <form className="search-form" onSubmit={handleSearch}>
        <input
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="종목명 또는 종목코드 입력 (예: 삼성전자, 005930)"
        />
        <button type="submit">검색</button>
      </form>

      {loading && <div className="loading">조회 중...</div>}

      {results.length > 0 && (
        <ul className="search-results">
          {results.map(stock => (
            <li
              key={stock.ticker}
              className={selectedTicker === stock.ticker ? 'selected' : ''}
              onClick={() => handleSelectStock(stock.ticker)}
            >
              <span className="stock-name">{stock.stockName}</span>
              <span className="ticker">{stock.ticker}</span>
              <span className="market">{stock.market}</span>
            </li>
          ))}
        </ul>
      )}

      {price && (
        <div className="price-card">
          <h3>{price.stockName} ({price.ticker})</h3>
          <div className="current-price">{Number(price.currentPrice).toLocaleString()}원</div>
          <div className={`change ${price.changeAmount >= 0 ? 'up' : 'down'}`}>
            {price.changeAmount >= 0 ? '▲' : '▼'}
            {Math.abs(price.changeAmount).toLocaleString()}원
            ({price.changeRate}%)
          </div>
          <div className="price-detail">
            <span>시가 {Number(price.openPrice).toLocaleString()}</span>
            <span>고가 {Number(price.highPrice).toLocaleString()}</span>
            <span>저가 {Number(price.lowPrice).toLocaleString()}</span>
            <span>거래량 {Number(price.volume).toLocaleString()}</span>
          </div>
          {price.mock && <p className="mock-badge">MOCK 데이터</p>}
        </div>
      )}
    </div>
  )
}
