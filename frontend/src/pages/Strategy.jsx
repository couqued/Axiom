import { useState, useEffect, useCallback } from 'react'
import { getMarketState, refreshMarketState, runStrategy, getPortfolio, getAdminStatus, getTrailingStopStatus, getTimeCutStatus, getEvalRanking, getStockPrice, getRunHistory } from '../api/stockApi'

const STRATEGY_KO = {
  'golden-cross': '골든크로스',
  'rsi-bollinger': 'RSI+볼린저',
  'volatility-breakout': '변동성 돌파',
}

const MARKET_KO = { BULLISH: '상승장', SIDEWAYS: '횡보장' }

const RESULT_STYLE = {
  '매수': { color: '#e74c3c', fontWeight: 700 },
  '한도초과': { color: '#7f8c8d' },
  '예산부족': { color: '#e67e22' },
  '이미보유': { color: '#27ae60' },
  '대기': { color: '#95a5a6' },
}

const STRATEGIES_BY_STATE = {
  BULLISH: ['변동성 돌파', '골든크로스'],
  SIDEWAYS: ['RSI + 볼린저밴드'],
}

function fmt(n) {
  if (n == null) return '—'
  return Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

export default function Strategy({ liveAdminConfig }) {
  const [marketState, setMarketState] = useState(null)
  const [indexSnap, setIndexSnap] = useState({})  // { yesterdayClose, ma20, todayOpenIndex }
  const [positions, setPositions] = useState([])
  const [adminConfig, setAdminConfig] = useState(null)
  const [tsStatus, setTsStatus] = useState({})
  const [tcStatus, setTcStatus] = useState({})
  const [stockNames, setStockNames] = useState({})
  const [ranking, setRanking] = useState([])
  const [rankingEvalAt, setRankingEvalAt] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [runHistory, setRunHistory] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [running, setRunning] = useState(false)
  const [runMsg, setRunMsg] = useState(null)

  const loadRanking = () =>
    getEvalRanking().then(data => {
      setRanking(data.items || [])
      setRankingEvalAt(data.evaluatedAt || null)
    }).catch(() => {})

  const loadRunHistory = useCallback(async () => {
    const data = await getRunHistory().catch(() => null)
    if (data) setRunHistory(data)
  }, [])

  useEffect(() => {
    setLoading(true)
    Promise.all([
      getMarketState(),
      getPortfolio(),
      getAdminStatus(),
      getTrailingStopStatus().catch(() => ({})),
      getTimeCutStatus().catch(() => ({})),
      getEvalRanking().catch(() => ({ items: [], evaluatedAt: null })),
      getRunHistory().catch(() => null),
    ])
      .then(([ms, p, cfg, ts, tc, rank, history]) => {
        setMarketState(ms.state)
        setIndexSnap({ yesterdayClose: ms.yesterdayClose, ma20: ms.ma20, todayOpenIndex: ms.todayOpenIndex })
        setPositions(p)
        setAdminConfig(cfg)
        setTsStatus(ts)
        setTcStatus(tc)
        setRanking(rank.items || [])
        setRankingEvalAt(rank.evaluatedAt || null)
        if (history) setRunHistory(history)
        if (p.length > 0) {
          Promise.all(p.map(item => getStockPrice(item.ticker).catch(() => null)))
            .then(results => {
              const nameMap = {}
              p.forEach((item, i) => {
                if (results[i]?.stockName) nameMap[item.ticker] = results[i].stockName
              })
              setStockNames(nameMap)
            })
        }
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (liveAdminConfig) setAdminConfig(liveAdminConfig)
  }, [liveAdminConfig])

  // 타임컷·트레일링스탑 상태 5분마다 자동 갱신
  useEffect(() => {
    const interval = setInterval(async () => {
      try {
        const [ts, tc] = await Promise.all([
          getTrailingStopStatus().catch(() => ({})),
          getTimeCutStatus().catch(() => ({})),
        ])
        setTsStatus(ts)
        setTcStatus(tc)
      } catch {}
    }, 5 * 60 * 1000)
    return () => clearInterval(interval)
  }, [])

  const handleRefresh = async () => {
    setRefreshing(true)
    setError(null)
    try {
      const res = await refreshMarketState()
      setMarketState(res.state)
      setIndexSnap({ yesterdayClose: res.yesterdayClose, ma20: res.ma20, todayOpenIndex: res.todayOpenIndex })
    } catch (e) {
      setError('시장 상태 갱신 실패: ' + e.message)
    } finally {
      setRefreshing(false)
    }
  }

  const handleRun = async () => {
    setRunning(true)
    setRunMsg(null)
    try {
      const res = await runStrategy()
      setRunMsg({ ok: true, text: res.result })
      const [p, ts, tc] = await Promise.all([
        getPortfolio(),
        getTrailingStopStatus().catch(() => ({})),
        getTimeCutStatus().catch(() => ({})),
      ])
      setPositions(p)
      setTsStatus(ts)
      setTcStatus(tc)
      await loadRanking()
      await loadRunHistory()
      if (p.length > 0) {
        Promise.all(p.map(item => getStockPrice(item.ticker).catch(() => null)))
          .then(results => {
            const nameMap = {}
            p.forEach((item, i) => {
              if (results[i]?.stockName) nameMap[item.ticker] = results[i].stockName
            })
            setStockNames(nameMap)
          })
      }
    } catch (e) {
      setRunMsg({ ok: false, text: '실행 오류: ' + e.message })
    } finally {
      setRunning(false)
    }
  }

  if (loading) return <div className="loading">로딩 중...</div>

  const isBullish = marketState === 'BULLISH'
  const activeStrategies = STRATEGIES_BY_STATE[marketState] ?? []
  const maxPositions = adminConfig?.maxPositions ?? '-'
  const positionRatio = adminConfig ? positions.length / adminConfig.maxPositions : 0
  const isFull = adminConfig ? positions.length >= adminConfig.maxPositions : false

  return (
    <div className="page">
      <h2>자동매매 전략</h2>

      {error && <div className="error">{error}</div>}

      {/* 시장 상태 + 지수 정보 */}
      <div className={`market-state-card ${isBullish ? 'bullish' : 'sideways'}`}>
        <div className="market-state-header">
          <div>
            <div className="market-state-label">시장 상태 (코스피 MA20 기준)</div>
            <div className="market-state-value">
              {isBullish ? '상승장' : '횡보장'}
              <span className={`state-badge ${isBullish ? 'bullish' : 'sideways'}`}>
                {marketState ?? '—'}
              </span>
            </div>
          </div>
          <button className="refresh-btn small" onClick={handleRefresh} disabled={refreshing}>
            {refreshing ? '갱신 중' : '갱신'}
          </button>
        </div>

        {/* 지수 정보 행 */}
        <div className="index-info-row">
          <div className="index-info-item">
            <span className="index-info-label">어제 종가</span>
            <span className="index-info-value">{fmt(indexSnap.yesterdayClose)}</span>
          </div>
          <div className="index-info-item">
            <span className="index-info-label">MA20</span>
            <span className="index-info-value">{fmt(indexSnap.ma20)}</span>
          </div>
          <div className="index-info-item">
            <span className="index-info-label">오늘 9:05</span>
            <span className="index-info-value">{fmt(indexSnap.todayOpenIndex)}</span>
          </div>
        </div>

        <div className="active-strategies">
          <span className="strategies-label">활성 전략</span>
          <div className="strategy-badges">
            {activeStrategies.map(s => (
              <span key={s} className="strategy-badge">{s}</span>
            ))}
          </div>
        </div>

        <p className="section-note">매일 08:30 자동 갱신 · 수동 갱신 시 즉시 반영</p>
      </div>

      {/* 포지션 현황 */}
      <div className="position-card">
        <div className="position-header">
          <span>보유 포지션</span>
          <span className="position-count">
            <strong className={isFull ? 'full' : ''}>{positions.length}</strong>
            {' / '}{maxPositions}
          </span>
        </div>
        <div className="position-bar">
          <div
            className={`position-bar-fill ${isFull ? 'full' : ''}`}
            style={{ width: `${Math.min(positionRatio * 100, 100)}%` }}
          />
        </div>

        {positions.length === 0 ? (
          <p className="empty small">보유 포지션 없음</p>
        ) : (
          <div className="position-detail-list">
            {positions.map(p => {
              const ts = tsStatus[p.ticker]
              const tc = tcStatus[p.ticker]
              return (
                <div key={p.ticker} className="position-detail-card">
                  <div className="position-detail-header">
                    <span className="holding-name">{/^\d+$/.test(p.stockName) ? (stockNames[p.ticker] || p.stockName) : p.stockName}</span>
                    <span className="holding-ticker">{p.ticker}</span>
                  </div>
                  <div className="position-detail-row">
                    {p.quantity}주 · 평균 {Number(p.avgPrice).toLocaleString()}원
                  </div>
                  <div className="position-detail-row secondary">
                    투자금 {Number(p.totalInvest).toLocaleString()}원
                    {tc && <span> · {tc.elapsed}거래일 경과</span>}
                  </div>
                  {ts && (
                    <div className="position-risk ts">
                      트레일링 스탑: {Number(ts.stopPrice).toLocaleString()}원
                    </div>
                  )}
                  {tc && (
                    <div className="position-risk tc">
                      타임컷: <strong>{tc.remaining}거래일</strong> 남음
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* 전략 제어 */}
      <div className="strategy-control">
        <h3>전략 제어</h3>
        <div className="control-buttons">
          <button className="run-btn" onClick={handleRun} disabled={running}>
            {running ? '실행 중...' : '▶ 전략 즉시 실행'}
          </button>
        </div>
        {runMsg && (
          <p className={`result-msg ${runMsg.ok ? 'success' : 'fail'}`}>{runMsg.text}</p>
        )}
      </div>

      {/* 시간별 실행 이력 */}
      {runHistory?.hours?.length > 0 && (
        <div className="run-history-card">
          <h3>시간별 실행 이력 <span className="section-sub">당일 · 서버 재시작 시 초기화</span></h3>
          <table className="run-history-table">
            <thead>
              <tr><th>시간대</th><th>실행</th><th>평가</th><th>매수</th><th>매도</th><th>주요 내용</th></tr>
            </thead>
            <tbody>
              {runHistory.hours.map(h => (
                <tr key={h.hour}>
                  <td>{String(h.hour).padStart(2, '0')}:xx</td>
                  <td>{h.runCount}회</td>
                  <td>{h.evaluated}개</td>
                  <td className={h.bought > 0 ? 'up' : ''}>{h.bought}건</td>
                  <td className={h.sold > 0 ? 'down' : ''}>{h.sold}건</td>
                  <td className="run-history-detail">
                    {h.boughtTickers?.length > 0 && (
                      <span className="up">{h.boughtTickers.join(', ')}</span>
                    )}
                    {h.skippedList?.length > 0 && (
                      <span className="skip-hint">
                        {h.skippedList.map(s => s.stockName).join(', ')} 스킵
                      </span>
                    )}
                    {h.noSignalCount === h.runCount && h.bought === 0 && (
                      <span className="empty-hint">BUY 신호 없음</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* BUY 신호 랭킹 */}
      <div className="skipped-card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <h3>BUY 신호 랭킹 <span className="section-sub">score 기준 상위 30개</span></h3>
          {rankingEvalAt && (
            <span className="section-note" style={{ margin: 0 }}>
              {new Date(rankingEvalAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })} 기준
            </span>
          )}
        </div>
        {ranking.length === 0 ? (
          <p className="empty small">전략 실행 후 랭킹이 표시됩니다</p>
        ) : (
          <div className="ranking-list">
            {ranking.map(item => (
              <div key={item.ticker} className="ranking-item">
                <span className="ranking-rank">#{item.rank}</span>
                <div className="ranking-info">
                  <span className="ranking-name">{item.stockName || item.ticker}</span>
                  <span className="ranking-ticker">{item.ticker}</span>
                  <span className="ranking-strategy">{STRATEGY_KO[item.strategyName] ?? item.strategyName}</span>
                </div>
                <div className="ranking-right">
                  <span className="ranking-score">{item.score.toFixed(1)}점</span>
                  {item.result && (
                    <span className="ranking-result" style={RESULT_STYLE[item.result] || {}}>
                      {item.result}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 전략 설정 */}
      <div className="config-card">
        <h3>전략 설정</h3>
        <div className="config-grid">
          <div className="config-item">
            <span className="config-label">1회 매수금액</span>
            <span className="config-value">{adminConfig ? adminConfig.investAmountKrw.toLocaleString() + '원' : '—'}</span>
          </div>
          <div className="config-item">
            <span className="config-label">최대 보유 종목</span>
            <span className="config-value">{adminConfig ? adminConfig.maxPositions + '종목' : '—'}</span>
          </div>
          <div className="config-item">
            <span className="config-label">트레일링 스탑</span>
            <span className="config-value">{adminConfig ? `고점 -${adminConfig.trailingStopPct}%` : '—'}</span>
          </div>
          <div className="config-item">
            <span className="config-label">타임 컷</span>
            <span className="config-value">{adminConfig ? adminConfig.timeCutDays + '거래일' : '—'}</span>
          </div>
          <div className="config-item">
            <span className="config-label">감시 유니버스</span>
            <span className="config-value">코스피200 + 코스닥150</span>
          </div>
          <div className="config-item">
            <span className="config-label">실행 주기</span>
            <span className="config-value">1분 (09:00~15:20)</span>
          </div>
        </div>
        <p className="section-note">투자 설정(매수금액·최대종목)은 관리자 패널에서 변경 가능</p>
      </div>
    </div>
  )
}
