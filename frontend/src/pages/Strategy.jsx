import { useState, useEffect } from 'react'
import { getMarketState, refreshMarketState, runStrategy, testSlack, getPortfolio, getAdminStatus, getTrailingStopStatus, getTimeCutStatus, getSkippedSignals } from '../api/stockApi'

const SKIP_REASON_KO = {
  BUDGET_INSUFFICIENT: '투자금 부족',
  MAX_POSITIONS: '최대종목 초과',
  MARKET_WARN: '시장경보',
}

const STRATEGY_KO = {
  'golden-cross': '골든크로스',
  'rsi-bollinger': 'RSI+볼린저',
  'volatility-breakout': '변동성 돌파',
}

const MARKET_KO = { BULLISH: '상승장', SIDEWAYS: '횡보장' }

const STRATEGIES_BY_STATE = {
  BULLISH: ['변동성 돌파', '골든크로스'],
  SIDEWAYS: ['RSI + 볼린저밴드'],
}

export default function Strategy({ liveAdminConfig }) {
  const [marketState, setMarketState] = useState(null)
  const [positions, setPositions] = useState([])
  const [adminConfig, setAdminConfig] = useState(null)
  const [tsStatus, setTsStatus] = useState({})
  const [tcStatus, setTcStatus] = useState({})
  const [skipped, setSkipped] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [refreshing, setRefreshing] = useState(false)
  const [running, setRunning] = useState(false)
  const [slackTesting, setSlackTesting] = useState(false)
  const [runMsg, setRunMsg] = useState(null)
  const [slackMsg, setSlackMsg] = useState(null)

  useEffect(() => {
    setLoading(true)
    Promise.all([
      getMarketState(),
      getPortfolio(),
      getAdminStatus(),
      getTrailingStopStatus().catch(() => ({})),
      getTimeCutStatus().catch(() => ({})),
      getSkippedSignals(7).catch(() => []),
    ])
      .then(([ms, p, cfg, ts, tc, sk]) => {
        setMarketState(ms.state)
        setPositions(p)
        setAdminConfig(cfg)
        setTsStatus(ts)
        setTcStatus(tc)
        setSkipped(sk)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

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
      const [p, ts, tc, sk] = await Promise.all([
        getPortfolio(),
        getTrailingStopStatus().catch(() => ({})),
        getTimeCutStatus().catch(() => ({})),
        getSkippedSignals(7).catch(() => []),
      ])
      setPositions(p)
      setTsStatus(ts)
      setTcStatus(tc)
      setSkipped(sk)
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
          <div className="position-detail-list">
            {positions.map(p => {
              const ts = tsStatus[p.ticker]
              const tc = tcStatus[p.ticker]
              return (
                <div key={p.ticker} className="position-detail-card">
                  <div className="position-detail-header">
                    <span className="holding-name">{p.stockName}</span>
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

      {/* 투자 스킵 종목 (최근 7일) */}
      <div className="skipped-card">
        <h3>투자 스킵 종목 <span className="section-sub">최근 7일</span></h3>
        {skipped.length === 0 ? (
          <p className="empty small">스킵된 종목 없음</p>
        ) : (
          <SkippedList items={skipped} />
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

/** 날짜별로 그룹화된 스킵 종목 목록 */
function SkippedList({ items }) {
  // tradeDate 기준으로 그룹화
  const grouped = items.reduce((acc, item) => {
    const key = item.tradeDate
    if (!acc[key]) acc[key] = []
    acc[key].push(item)
    return acc
  }, {})

  const sortedDates = Object.keys(grouped).sort((a, b) => b.localeCompare(a))

  return (
    <div className="skipped-list">
      {sortedDates.map(date => (
        <div key={date} className="skipped-date-group">
          <div className="skipped-date-header">
            <span className="skipped-date">{date}</span>
            <span className="skipped-market-badge">
              {MARKET_KO[grouped[date][0]?.marketState] ?? grouped[date][0]?.marketState}
            </span>
          </div>
          <div className="skipped-items">
            {grouped[date].map(item => (
              <div key={item.id} className="skipped-item">
                <div className="skipped-item-left">
                  <span className="skipped-name">{item.stockName || item.ticker}</span>
                  <span className="skipped-ticker">{item.ticker}</span>
                </div>
                <div className="skipped-item-right">
                  <span className={`skipped-reason-badge reason-${item.skipReason}`}>
                    {SKIP_REASON_KO[item.skipReason] ?? item.skipReason}
                  </span>
                  {item.strategyName && (
                    <span className="skipped-strategy">
                      {STRATEGY_KO[item.strategyName] ?? item.strategyName}
                    </span>
                  )}
                  {item.price && (
                    <span className="skipped-price">
                      {Number(item.price).toLocaleString()}원
                    </span>
                  )}
                  {item.skipCount > 1 && (
                    <span className="skipped-count">{item.skipCount}회</span>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
