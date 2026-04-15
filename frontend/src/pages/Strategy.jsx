import { useState, useEffect, useCallback, useRef } from 'react'
import { getMarketState, refreshMarketState, runStrategy, getRunStatus, getEnrichedPortfolio, getAdminStatus, getTrailingStopStatus, getTimeCutStatus, getEvalRanking, getStockPrice, getSignalGap, triggerSignalGapRefresh } from '../api/stockApi'

const STRATEGY_KO = {
  'golden-cross': '골든크로스',
  'rsi-bollinger': 'RSI+볼린저',
  'volatility-breakout': '변동성 돌파',
}

const STRATEGY_TAG_STYLE = { color: '#4caf50', border: '1px solid #1b5e20' }

function getPositionStrategyTag(entryTag, buyStage) {
  if (entryTag === 'volatility-breakout') return { ...STRATEGY_TAG_STYLE, label: '변동성돌파' }
  if (entryTag === 'golden-cross')        return { ...STRATEGY_TAG_STYLE, label: '골든크로스' }
  if (buyStage === 1)                     return { ...STRATEGY_TAG_STYLE, label: 'BB 1차 매수' }
  return { ...STRATEGY_TAG_STYLE, label: 'BB+RSI 완료' }
}

const STRATEGIES_BY_STATE = {
  BULLISH: ['변동성 돌파', '골든크로스'],
  SIDEWAYS: ['RSI + 볼린저밴드'],
  BEARISH: ['RSI + 볼린저밴드(침체장)'],
}

function fmt(n) {
  if (n == null) return '—'
  return Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

export default function Strategy({ liveAdminConfig }) {
  const [marketState, setMarketState] = useState(null)
  const [indexSnap, setIndexSnap] = useState({})
  const [positions, setPositions] = useState([])
  const [adminConfig, setAdminConfig] = useState(null)
  const [tsStatus, setTsStatus] = useState({})
  const [tcStatus, setTcStatus] = useState({})
  const [ranking, setRanking] = useState([])
  const [rankingEvalAt, setRankingEvalAt] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [signalGap, setSignalGap] = useState([])
  const [signalGapComputedAt, setSignalGapComputedAt] = useState(null)
  const [signalGapLoading, setSignalGapLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [running, setRunning] = useState(false)
  const [runMsg, setRunMsg] = useState(null)

  const fetchData = useCallback(async () => {
    try {
      const [ms, p, cfg, ts, tc, rank, gap] = await Promise.all([
        getMarketState(),
        getEnrichedPortfolio(),
        getAdminStatus(),
        getTrailingStopStatus().catch(() => ({})),
        getTimeCutStatus().catch(() => ({})),
        getEvalRanking().catch(() => ({ items: [], evaluatedAt: null })),
        getSignalGap().catch(() => ({ items: [] })),
      ])
      setMarketState(ms.state)
      setIndexSnap({ yesterdayClose: ms.yesterdayClose, ma20: ms.ma20, todayOpenIndex: ms.todayOpenIndex })
      setPositions(p)
      setAdminConfig(cfg)
      setTsStatus(ts)
      setTcStatus(tc)
      setRanking(rank.items || [])
      setRankingEvalAt(rank.evaluatedAt || null)
      setSignalGap(gap.items || [])
      setSignalGapComputedAt(gap.computedAt || null)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  useEffect(() => {
    if (liveAdminConfig) setAdminConfig(liveAdminConfig)
  }, [liveAdminConfig])

  const handleRefresh = async () => {
    setRefreshing(true)
    try {
      await refreshMarketState()
      await fetchData()
    } catch (e) {
      setError('시장 상태 갱신 실패: ' + e.message)
    } finally {
      setRefreshing(false)
    }
  }

  const runPollRef = useRef(null)

  const handleRun = async () => {
    setRunning(true)
    setRunMsg({ ok: true, text: '전략 실행 중...' })
    try {
      await runStrategy()
      runPollRef.current = setInterval(async () => {
        try {
          const status = await getRunStatus()
          if (!status.running) {
            clearInterval(runPollRef.current)
            runPollRef.current = null
            if (status.message) {
              setRunMsg({ ok: !status.message.startsWith('실행 오류'), text: status.message })
            } else {
              setRunMsg({ ok: true, text: '실행 완료' })
            }
            setRunning(false)
            await fetchData()
          }
        } catch (e) {
          clearInterval(runPollRef.current)
          runPollRef.current = null
          setRunMsg({ ok: false, text: '상태 조회 실패: ' + e.message })
          setRunning(false)
        }
      }, 4000)
    } catch (e) {
      setRunMsg({ ok: false, text: '실행 오류: ' + e.message })
      setRunning(false)
    }
  }

  const loadSignalGap = useCallback(async () => {
    setSignalGapLoading(true)
    try {
      await triggerSignalGapRefresh(10)
      const poll = setInterval(async () => {
        const data = await getSignalGap()
        if (!data.running) {
          setSignalGap(data.items || [])
          setSignalGapComputedAt(data.computedAt || null)
          setSignalGapLoading(false)
          clearInterval(poll)
        }
      }, 3000)
    } catch (e) {
      setError('Signal Gap 조회 실패')
      setSignalGapLoading(false)
    }
  }, [])

  if (loading) return <div className="loading">로딩 중...</div>

  const activeSettings = adminConfig?.settings
  const isBullish = marketState === 'BULLISH'
  const isBearish = marketState === 'BEARISH'
  const activeStrategies = STRATEGIES_BY_STATE[marketState] ?? []
  const maxPositions = adminConfig?.settings?.maxPositions ?? '-'

  return (
    <div className="page">
      <h2 style={{ marginBottom: '4px' }}>자동매매 전략</h2>

      {error && <div className="error">{error}</div>}

      <div className={`market-state-card ${marketState?.toLowerCase()}`}>
        <div className="market-state-header">
          <div>
            <div className="market-state-label">시장 상태 (코스피 MA20 기준)</div>
            <div className="market-state-value">
              {marketState === 'BULLISH' ? '상승장' : marketState === 'BEARISH' ? '하락장' : '횡보장'}
              <span className={`state-badge ${marketState?.toLowerCase()}`}>{marketState}</span>
            </div>
          </div>
          <button className="refresh-btn small" onClick={handleRefresh} disabled={refreshing}>갱신</button>
        </div>

        <div className="index-info-row">
          <div className="index-info-item"><span className="index-info-label">어제 종가</span><span className="index-info-value">{fmt(indexSnap.yesterdayClose)}</span></div>
          <div className="index-info-item"><span className="index-info-label">MA20</span><span className="index-info-value">{fmt(indexSnap.ma20)}</span></div>
          <div className="index-info-item"><span className="index-info-label">오늘 9:05</span><span className="index-info-value">{fmt(indexSnap.todayOpenIndex)}</span></div>
        </div>

        <div className="active-strategies">
          <span className="strategies-label">활성 전략:</span>
          <div className="strategy-badges">
            {activeStrategies.map(s => (
              <span key={s} className="strategy-badge">{s}</span>
            ))}
          </div>
        </div>

        <p className="section-note">매일 08:30 자동 갱신 · 수동 갱신 시 즉시 반영</p>
      </div>

      <div className="position-card">
        <div className="position-header"><span>보유 포지션</span><span className="position-count"><strong>{positions.length}</strong> / {maxPositions}</span></div>
        <div className="position-bar"><div className="position-bar-fill" style={{ width: `${(positions.length / (maxPositions || 1)) * 100}%` }} /></div>
        <div className="position-detail-list">
          {positions.map(p => {
            const ts = tsStatus[p.ticker]
            const tc = tcStatus[p.ticker]
            return (
              <div key={p.ticker} className="position-detail-card">
                <div className="position-detail-header"><span className="holding-name">{p.stockName}</span><span className="holding-ticker">{p.ticker}</span></div>
                {(() => {
                  const tag = getPositionStrategyTag(p.entryTag, p.buyStage)
                  return (
                    <span className="history-tag" style={{ color: tag.color, border: tag.border, background: 'none', display: 'inline-block', marginBottom: '4px' }}>
                      {tag.label}
                    </span>
                  )
                })()}
                <div className="position-detail-row">{p.quantity}주 · 평균 {Number(p.avgPrice).toLocaleString()}원</div>
                <div className="position-detail-row secondary">투자금 {Number(p.totalInvest).toLocaleString()}원</div>
                {ts && <div className="position-risk ts">트레일링 스탑: {Number(ts.stopPrice).toLocaleString()}원</div>}
                {tc && <div className="position-risk tc">타임컷: <strong>{tc.remaining}거래일</strong> 남음</div>}
              </div>
            )
          })}
        </div>
      </div>

      <div className="strategy-control">
        <h3>전략 제어</h3>
        <button className="run-btn" onClick={handleRun} disabled={running}>▶ 전략 즉시 실행</button>
        {runMsg && <p className={`result-msg ${runMsg.ok ? 'success' : 'fail'}`}>{runMsg.text}</p>}
      </div>

      {/* 4. 매수 신호 근접도 (V2 버전 적용) */}
      <div className="skipped-card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <h3>매수 신호 근접도 <span className="section-sub">진입 신호 근접도 상위 10개</span></h3>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: '8px' }}>
            {signalGapLoading
              ? <span className="section-note" style={{ color: '#ffd966' }}>조회 중...</span>
              : signalGapComputedAt && <span className="section-note">조회 완료 {new Date(signalGapComputedAt).toLocaleTimeString()}</span>
            }
            <button className="refresh-btn small" onClick={loadSignalGap} disabled={signalGapLoading}>조회</button>
          </div>
        </div>
        {(() => {
          const STRATEGY_BADGE = {
            'golden-cross': { label: '골든크로스', color: '#ffd966' },
            'volatility-breakout': { label: '변동성돌파', color: '#4fa3ff' },
            'rsi-bollinger': { label: 'RSI+볼린저밴드', color: '#a78bfa' },
          }
          return (
            <div className="signal-table">
              <div className="signal-table-header">
                <span>순위</span>
                <span className="signal-col-left">종목명<br/>(현재가)</span>
                <span>매수기준가</span>
                <span>RSI<br/>(14)</span>
                <span>청산 기준</span>
                <span>진입<br/>점수</span>
              </div>
              {signalGap.length === 0
                ? <div className="signal-empty">
                    {signalGapComputedAt
                      ? '현재 볼린저밴드 하단 조건을 만족하는 종목이 없습니다.'
                      : '조회 버튼을 눌러 데이터를 불러오세요'}
                  </div>
                : signalGap.map(item => (
                <div key={item.ticker} className="signal-table-row">
                  <span>{item.rank}</span>
                  <div className="signal-col-left">
                    <div className="signal-stock-name">{item.stockName}</div>
                    <div className="signal-stock-price">{item.currentPrice.toLocaleString()}원</div>
                    <span className="signal-strategy-badge" style={{ color: STRATEGY_BADGE[item.strategy]?.color }}>
                      {STRATEGY_BADGE[item.strategy]?.label}
                    </span>
                  </div>
                  <div>
                    <div>{Math.round(item.threshold).toLocaleString()}</div>
                    <div style={{ fontSize: '10px', color:
                      (item.strategy === 'rsi-bollinger' ? item.currentPrice < item.threshold : item.gapPct <= 0)
                      ? '#ffd966' : '#4fa3ff' }}>
                      {item.strategy === 'rsi-bollinger'
                        ? (item.currentPrice < item.threshold
                            ? (item.rsi < 30 ? '2차 조건 충족' : '1차 충족 (RSI 대기)')
                            : `-${item.gapPct.toFixed(1)}%`)
                        : item.gapPct <= 0
                        ? (item.strategy === 'golden-cross' ? '골든크로스 발생' : '목표가 돌파')
                        : `-${item.gapPct.toFixed(1)}%`}
                    </div>
                  </div>
                  <div style={{ color: item.rsi < 30 ? '#ff4d4d' : '#e0e0e0' }}>{item.rsi >= 0 ? item.rsi.toFixed(1) : '-'}</div>
                  <div>
                    <div style={{ fontSize: '11px', color: '#ff4d4d' }}>
                      {item.strategy === 'rsi-bollinger'
                        ? (item.bbUpper > 0 ? Math.round(item.bbUpper).toLocaleString() + '원' : '-')
                        : <span style={{ fontSize: '11px', color: '#aaa' }}>
                            {item.strategy === 'volatility-breakout' ? '당일 청산' : '데드크로스 매도'}
                          </span>}
                    </div>
                    <div style={{ fontSize: '10px', color: '#888' }}>{item.strategy === 'rsi-bollinger' ? 'or RSI≥70' : ''}</div>
                  </div>
                  <div><strong style={{ color: item.score >= 80 ? '#ff4d4d' : item.score > 0 ? '#ffd966' : '#444' }}>{item.score > 0 ? `${item.score.toFixed(0)}점` : '-'}</strong></div>
                </div>
              ))}
            </div>
          )
        })()}
      </div>

      {/* 5. BUY 신호 랭킹 (V2 버전 적용) */}
      <div className="skipped-card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <h3>BUY 신호 랭킹 <span className="section-sub">최근 실행 결과</span></h3>
          {rankingEvalAt && <span className="section-note">실행 완료 {new Date(rankingEvalAt).toLocaleTimeString()}</span>}
        </div>
        {ranking.length === 0
          ? <div className="signal-empty">
              {rankingEvalAt
                ? '마지막 실행에서 BUY 신호가 발생한 종목이 없습니다.'
                : <>아직 전략이 실행되지 않았습니다.<br/>위의 <strong>전략 즉시 실행</strong> 버튼을 누르거나 스케줄러 실행을 기다려주세요.</>
              }
            </div>
          : ranking.map(item => (
          <div key={item.ticker} className="holding-card" style={{ borderLeft: '4px solid #4fa3ff', position: 'relative', marginBottom: '10px' }}>
            <div style={{ position: 'absolute', top: '14px', right: '14px', fontSize: '18px', fontWeight: 'bold', color: '#ff4d4d' }}>{item.score.toFixed(0)}점</div>
            <div className="holding-header"><span style={{ color: '#4fa3ff', fontWeight: 'bold', marginRight: '8px' }}>#{item.rank}</span><span className="holding-name">{item.stockName}</span><span className="holding-ticker">{item.ticker}</span></div>
            <div style={{ fontSize: '13px', color: '#aaa', margin: '8px 0', paddingRight: '60px' }}>{item.reason}</div>
            <div className="holding-tags"><span className="history-tag strategy">{item.result || '대기'}</span></div>
          </div>
        ))}
      </div>

      <div className="config-card">
        <h3>전략 설정</h3>
        <div className="config-grid">
          <div className="config-item"><span className="config-label">1회 매수금액</span><span className="config-value">{activeSettings ? activeSettings.investAmountKrw.toLocaleString() + '원' : '—'}</span></div>
          <div className="config-item"><span className="config-label">최대 보유 종목</span><span className="config-value">{activeSettings ? activeSettings.maxPositions + '종목' : '—'}</span></div>
          <div className="config-item"><span className="config-label">변동성돌파 일일한도</span><span className="config-value">{activeSettings ? (activeSettings.volatilityBreakoutDailyLimit === 0 ? '비활성' : activeSettings.volatilityBreakoutDailyLimit + '회') : '—'}</span></div>
          <div className="config-item"><span className="config-label">골든크로스 일일한도</span><span className="config-value">{activeSettings ? (activeSettings.goldenCrossDailyLimit === 0 ? '비활성' : activeSettings.goldenCrossDailyLimit + '회') : '—'}</span></div>
          <div className="config-item"><span className="config-label">볼린저밴드 일일한도</span><span className="config-value">{activeSettings ? (activeSettings.bollingerDailyLimit === 0 ? '비활성' : activeSettings.bollingerDailyLimit + '회') : '—'}</span></div>
          <div className="config-item"><span className="config-label">트레일링 스탑</span><span className="config-value">{activeSettings ? `고점 -${activeSettings.trailingStopPct}%` : '—'}</span></div>
          <div className="config-item"><span className="config-label">익절 비율</span><span className="config-value">{activeSettings ? (activeSettings.profitTakePct > 0 ? `+${activeSettings.profitTakePct}%` : '비활성') : '—'}</span></div>
          <div className="config-item"><span className="config-label">타임 컷</span><span className="config-value">{activeSettings ? activeSettings.timeCutDays + '거래일' : '—'}</span></div>
          <div className="config-item"><span className="config-label">지수 하락 매수차단</span><span className="config-value">
            {activeSettings?.indexDropBlockPct}% {' '}
            {adminConfig && (
              !adminConfig.indexDropCheckedToday
                ? <span className="index-drop-badge checking">체크 전</span>
                : adminConfig.indexDropBlockedToday
                  ? <span className="index-drop-badge blocked">차단</span>
                  : <span className="index-drop-badge ok">미차단</span>
            )}
          </span></div>
          <div className="config-item"><span className="config-label">감시 유니버스</span><span className="config-value">코스피200 + 코스닥150</span></div>
          <div className="config-item"><span className="config-label">트레일링 스탑 실행 주기</span><span className="config-value">1분 (09:00~15:20)</span></div>
        </div>
      </div>
    </div>
  )
}
