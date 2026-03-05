import { useState, useEffect } from 'react'
import { getMarketState, refreshMarketState, runStrategy, testSlack, getPortfolio, getAdminStatus } from '../api/stockApi'

const STRATEGIES_BY_STATE = {
  BULLISH: ['변동성 돌파', '골든크로스'],
  SIDEWAYS: ['RSI + 볼린저밴드'],
}

export default function Strategy({ liveAdminConfig }) {
  const [marketState, setMarketState] = useState(null)
  const [positions, setPositions] = useState([])
  const [adminConfig, setAdminConfig] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [refreshing, setRefreshing] = useState(false)
  const [running, setRunning] = useState(false)
  const [slackTesting, setSlackTesting] = useState(false)
  const [runMsg, setRunMsg] = useState(null)   // { ok: bool, text: string }
  const [slackMsg, setSlackMsg] = useState(null)

  useEffect(() => {
    setLoading(true)
    Promise.all([getMarketState(), getPortfolio(), getAdminStatus()])
      .then(([ms, p, cfg]) => { setMarketState(ms.state); setPositions(p); setAdminConfig(cfg) })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  // Admin에서 저장 시 API 재조회 없이 즉시 반영
  useEffect(() => {
    if (liveAdminConfig) setAdminConfig(liveAdminConfig)
  }, [liveAdminConfig])

  const handleRefresh = async () => {
    setRefreshing(true)
    setError(null)
    try {
      const res = await refreshMarketState()
      setMarketState(res.state)
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
      const p = await getPortfolio()
      setPositions(p)
    } catch (e) {
      setRunMsg({ ok: false, text: '실행 오류: ' + e.message })
    } finally {
      setRunning(false)
    }
  }

  const handleSlack = async () => {
    setSlackTesting(true)
    setSlackMsg(null)
    try {
      const res = await testSlack()
      setSlackMsg({ ok: true, text: res.result })
    } catch (e) {
      setSlackMsg({ ok: false, text: 'Slack 전송 실패 (enabled 설정 확인)' })
    } finally {
      setSlackTesting(false)
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

      {/* 시장 상태 */}
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
          <div className="position-list">
            {positions.map(p => (
              <div key={p.ticker} className="position-item">
                <span className="stock-name">{p.stockName}</span>
                <span className="ticker">{p.ticker}</span>
                <span className="position-qty">{p.quantity}주</span>
              </div>
            ))}
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
          <button className="slack-btn" onClick={handleSlack} disabled={slackTesting}>
            {slackTesting ? '전송 중...' : 'Slack 테스트'}
          </button>
        </div>
        {runMsg && (
          <p className={`result-msg ${runMsg.ok ? 'success' : 'fail'}`}>{runMsg.text}</p>
        )}
        {slackMsg && (
          <p className={`result-msg ${slackMsg.ok ? 'success' : 'fail'}`}>{slackMsg.text}</p>
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
            <span className="config-value">5분 (09:05~15:20)</span>
          </div>
        </div>
        <p className="section-note">투자 설정(매수금액·최대종목)은 관리자 패널에서 변경 가능</p>
      </div>
    </div>
  )
}
