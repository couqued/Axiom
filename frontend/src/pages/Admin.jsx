import { useState, useEffect } from 'react'
import { getAdminStatus, pauseTrading, resumeTrading, updateAdminConfig, manualExit, markSold } from '../api/stockApi'

export default function Admin({ onClose, onConfigUpdated }) {
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [toggling, setToggling] = useState(false)
  const [switching, setSwitching] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState(null)

  const [exitTickers, setExitTickers] = useState('')
  const [exitResult, setExitResult] = useState(null)
  const [exiting, setExiting] = useState(false)

  const [markTickers, setMarkTickers] = useState('')
  const [markResult, setMarkResult] = useState(null)
  const [marking, setMarking] = useState(false)

  // 설정 탭: 어떤 모드의 설정을 편집 중인지
  const [settingsTab, setSettingsTab] = useState('paper')

  // 입력 값: paper/real 각각 독립
  const [fields, setFields] = useState({
    paper: { invest: '', maxPos: '', trailing: '', timeCut: '', indexDrop: '', volBreakoutDaily: '', goldenCrossDaily: '', bollingerDaily: '' },
    real:  { invest: '', maxPos: '', trailing: '', timeCut: '', indexDrop: '', volBreakoutDaily: '', goldenCrossDaily: '', bollingerDaily: '' },
  })

  const initFields = (s) => ({
    paper: {
      invest:           String(s.paper.investAmountKrw),
      maxPos:           String(s.paper.maxPositions),
      trailing:         String(s.paper.trailingStopPct),
      timeCut:          String(s.paper.timeCutDays),
      indexDrop:        String(s.paper.indexDropBlockPct),
      volBreakoutDaily: String(s.paper.volatilityBreakoutDailyLimit),
      goldenCrossDaily: String(s.paper.goldenCrossDailyLimit),
      bollingerDaily:   String(s.paper.bollingerDailyLimit),
    },
    real: {
      invest:           String(s.real.investAmountKrw),
      maxPos:           String(s.real.maxPositions),
      trailing:         String(s.real.trailingStopPct),
      timeCut:          String(s.real.timeCutDays),
      indexDrop:        String(s.real.indexDropBlockPct),
      volBreakoutDaily: String(s.real.volatilityBreakoutDailyLimit),
      goldenCrossDaily: String(s.real.goldenCrossDailyLimit),
      bollingerDaily:   String(s.real.bollingerDailyLimit),
    },
  })

  useEffect(() => {
    getAdminStatus()
      .then(s => {
        setStatus(s)
        setSettingsTab(s.tradingMode || 'paper')
        setFields(initFields(s))
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const setField = (mode, key, val) =>
    setFields(prev => ({ ...prev, [mode]: { ...prev[mode], [key]: val } }))

  // 활성 모드 매매 중단/재개
  const handleToggle = async () => {
    if (!status) return
    const activeMode = status.tradingMode
    const isPaused = status[activeMode]?.paused
    setToggling(true)
    setError(null)
    try {
      const res = isPaused ? await resumeTrading(activeMode) : await pauseTrading(activeMode)
      setStatus(res)
    } catch (e) {
      setError('상태 변경 실패: ' + e.message)
    } finally {
      setToggling(false)
    }
  }

  // 전략 선택 방식 변경
  const handleStrategyModeChange = async (mode) => {
    if (mode === status?.strategyMode) return
    try {
      const res = await updateAdminConfig({ strategyMode: mode })
      setStatus(res)
    } catch (e) {
      setError('전략 모드 변경 실패: ' + e.message)
    }
  }

  // 거래 모드 전환
  const handleModeSwitch = async (newMode) => {
    if (newMode === status?.tradingMode) return
    if (newMode === 'real') {
      const ok = window.confirm('실제 계좌로 거래됩니다. 운영 모드로 전환하시겠습니까?')
      if (!ok) return
    }
    setSwitching(true)
    setError(null)
    try {
      const res = await updateAdminConfig({ tradingMode: newMode })
      setStatus(res)
      setSettingsTab(newMode)
      onConfigUpdated?.(res)
    } catch (e) {
      setError('모드 전환 실패: ' + e.message)
    } finally {
      setSwitching(false)
    }
  }

  // 특정 모드의 설정 저장
  const handleSaveConfig = async () => {
    const f = fields[settingsTab]
    const invest          = parseInt(f.invest, 10)
    const maxPos          = parseInt(f.maxPos, 10)
    const trailing        = parseFloat(f.trailing)
    const timeCut         = parseInt(f.timeCut, 10)
    const indexDrop       = parseFloat(f.indexDrop)
    const volBreakoutDaily = parseInt(f.volBreakoutDaily, 10)
    const goldenCrossDaily = parseInt(f.goldenCrossDaily, 10)
    const bollingerDaily   = parseInt(f.bollingerDaily, 10)
    if (isNaN(invest) || invest < 1 || isNaN(maxPos) || maxPos < 1
        || isNaN(trailing) || trailing <= 0 || isNaN(timeCut) || timeCut < 1
        || isNaN(indexDrop) || indexDrop < 0) {
      setSaveMsg({ ok: false, text: '올바른 숫자를 입력하세요' })
      return
    }
    if (isNaN(volBreakoutDaily) || volBreakoutDaily < 0 || isNaN(goldenCrossDaily) || goldenCrossDaily < 0 || isNaN(bollingerDaily) || bollingerDaily < 0) {
      setSaveMsg({ ok: false, text: '일일 한도는 0 이상의 숫자를 입력하세요' })
      return
    }
    setSaving(true)
    setSaveMsg(null)
    try {
      const res = await updateAdminConfig({
        targetMode: settingsTab,
        investAmountKrw: invest,
        maxPositions: maxPos,
        trailingStopPct: trailing,
        timeCutDays: timeCut,
        indexDropBlockPct: indexDrop,
        volatilityBreakoutDailyLimit: volBreakoutDaily,
        goldenCrossDailyLimit: goldenCrossDaily,
        bollingerDailyLimit: bollingerDaily,
      })
      setStatus(res)
      setSaveMsg({ ok: true, text: `[${settingsTab === 'paper' ? '모의투자' : '운영'}] 설정이 저장되었습니다` })
      onConfigUpdated?.(res)
    } catch (e) {
      setSaveMsg({ ok: false, text: '저장 실패: ' + e.message })
    } finally {
      setSaving(false)
    }
  }

  // 설정 탭의 모드 중단/재개
  const handleTabToggle = async (mode) => {
    if (!status) return
    const isPaused = status[mode]?.paused
    setToggling(true)
    try {
      const res = isPaused ? await resumeTrading(mode) : await pauseTrading(mode)
      setStatus(res)
    } catch (e) {
      setError('상태 변경 실패: ' + e.message)
    } finally {
      setToggling(false)
    }
  }

  const handleMarkSold = async () => {
    const tickers = markTickers.split(',').map(t => t.trim()).filter(Boolean)
    if (!tickers.length) return
    const ok = window.confirm(`${tickers.join(', ')} — MTS에서 직접 매도 완료된 종목입니다.\n포트폴리오 DB와 전략 상태를 정리하시겠습니까?\n(실제 매도 주문은 발생하지 않습니다)`)
    if (!ok) return
    setMarking(true)
    setMarkResult(null)
    try {
      const res = await markSold(tickers)
      setMarkResult(res)
    } catch (e) {
      setMarkResult({ error: e.message })
    } finally {
      setMarking(false)
    }
  }

  const handleManualExit = async () => {
    const tickers = exitTickers.split(',').map(t => t.trim()).filter(Boolean)
    if (!tickers.length) return
    const ok = window.confirm(`${tickers.join(', ')} 즉시 시장가 매도하시겠습니까?`)
    if (!ok) return
    setExiting(true)
    setExitResult(null)
    try {
      const res = await manualExit(tickers)
      setExitResult(res)
    } catch (e) {
      setExitResult({ error: e.message })
    } finally {
      setExiting(false)
    }
  }

  const activeSettings = status ? status[status.tradingMode] : null
  const tabSettings    = status ? status[settingsTab] : null

  return (
    <div className="admin-overlay" onClick={onClose}>
      <div className="admin-panel" onClick={e => e.stopPropagation()}>
        <div className="admin-panel-header">
          <span className="admin-panel-title">관리</span>
          <button className="admin-close-btn" onClick={onClose}>✕</button>
        </div>

        {loading && <div className="loading">로딩 중...</div>}
        {error && <div className="error">{error}</div>}

        {status && (
          <>
            {/* 거래 모드 전환 */}
            <div className="admin-section">
              <h3 className="admin-section-title">거래 모드</h3>
              <div className="mode-toggle-row">
                <button
                  className={`mode-toggle-btn ${status.tradingMode === 'paper' ? 'active' : ''}`}
                  onClick={() => handleModeSwitch('paper')}
                  disabled={switching || status.tradingMode === 'paper'}
                >
                  모의투자
                </button>
                <button
                  className={`mode-toggle-btn real ${status.tradingMode === 'real' ? 'active' : ''}`}
                  onClick={() => handleModeSwitch('real')}
                  disabled={switching || status.tradingMode === 'real'}
                >
                  운영 (실계좌)
                </button>
              </div>
              {switching && <p className="admin-note">모드 전환 중...</p>}
            </div>

            {/* 전략 선택 방식 */}
            <div className="admin-section">
              <h3 className="admin-section-title">전략 선택 방식</h3>
              <p className="admin-note">
                시장상황별: BULLISH→추세전략, SIDEWAYS/BEARISH→볼린저RSI<br/>
                전체동시: 시장상황 무관하게 3개 전략 모두 실행
              </p>
              <div className="mode-toggle-row">
                <button
                  className={`mode-toggle-btn ${status.strategyMode !== 'all-strategies' ? 'active' : ''}`}
                  onClick={() => handleStrategyModeChange('market-based')}
                  disabled={status.strategyMode !== 'all-strategies'}
                >
                  시장상황별 구분
                </button>
                <button
                  className={`mode-toggle-btn ${status.strategyMode === 'all-strategies' ? 'active' : ''}`}
                  onClick={() => handleStrategyModeChange('all-strategies')}
                  disabled={status.strategyMode === 'all-strategies'}
                >
                  전체 전략 동시 실행
                </button>
              </div>
            </div>

            {/* 긴급 제어 (활성 모드) */}
            <div className="admin-section">
              <h3 className="admin-section-title">
                긴급 제어
                <span className={`mode-badge ${status.tradingMode}`}>
                  {status.tradingMode === 'paper' ? '모의투자' : '운영'}
                </span>
              </h3>
              <div className="admin-status-row">
                <span className={`admin-status-dot ${activeSettings?.paused ? 'stopped' : 'running'}`} />
                <span className="admin-status-label">
                  {activeSettings?.paused ? '매매 중단 중' : '매매 실행 중'}
                </span>
              </div>
              <button
                className={`admin-toggle-btn ${activeSettings?.paused ? 'resume' : 'pause'}`}
                onClick={handleToggle}
                disabled={toggling}
              >
                {toggling
                  ? '처리 중...'
                  : activeSettings?.paused
                    ? '▶ 매매 재개'
                    : '■ 매매 중단'}
              </button>
              <p className="admin-note">중단 중에도 15:20 강제 청산은 계속 실행됩니다</p>
            </div>

            {/* 모드별 투자 설정 */}
            <div className="admin-section">
              <h3 className="admin-section-title">투자 설정</h3>
              <div className="mode-settings-tabs">
                <button
                  className={`mode-tab-btn ${settingsTab === 'paper' ? 'active' : ''}`}
                  onClick={() => setSettingsTab('paper')}
                >
                  모의투자 설정
                  {status.paper?.paused && <span className="tab-paused-dot" />}
                </button>
                <button
                  className={`mode-tab-btn real ${settingsTab === 'real' ? 'active' : ''}`}
                  onClick={() => setSettingsTab('real')}
                >
                  운영 설정
                  {status.real?.paused && <span className="tab-paused-dot" />}
                </button>
              </div>

              {/* 탭 내 매매 중단/재개 */}
              <div className="admin-status-row" style={{ marginTop: '8px' }}>
                <span className={`admin-status-dot ${tabSettings?.paused ? 'stopped' : 'running'}`} />
                <span className="admin-status-label">
                  {tabSettings?.paused ? '중단 중' : '실행 중'}
                </span>
                <button
                  className={`admin-toggle-btn small ${tabSettings?.paused ? 'resume' : 'pause'}`}
                  onClick={() => handleTabToggle(settingsTab)}
                  disabled={toggling}
                  style={{ marginLeft: 'auto' }}
                >
                  {tabSettings?.paused ? '▶ 재개' : '■ 중단'}
                </button>
              </div>

              <div className="admin-fields">
                {[
                  { key: 'invest',           label: '1회 매수금액 (원)',              min: 1,   max: undefined, step: 1 },
                  { key: 'maxPos',           label: '최대 보유 종목 수',               min: 1,   max: 20,        step: 1 },
                  { key: 'trailing',         label: '트레일링 스탑 (%)',               min: 0.1, max: 30,        step: 0.1 },
                  { key: 'timeCut',          label: '타임 컷 (거래일)',                min: 1,   max: 30,        step: 1 },
                  { key: 'indexDrop',        label: '지수 하락 매수차단 (%)',          min: 0,   max: 10,        step: 0.1 },
                  { key: 'volBreakoutDaily', label: '변동성돌파 일일 한도 (0=비활성)', min: 0,   max: 20,        step: 1 },
                  { key: 'goldenCrossDaily', label: '골든크로스 일일 한도 (0=비활성)', min: 0,   max: 20,        step: 1 },
                  { key: 'bollingerDaily',   label: '볼린저밴드 일일 한도 (0=비활성)', min: 0,   max: 20,        step: 1 },
                ].map(({ key, label, min, max, step }) => (
                  <label key={key} className="admin-field">
                    <span className="admin-field-label">{label}</span>
                    <input
                      type="number"
                      className="admin-input"
                      value={fields[settingsTab][key]}
                      onChange={e => setField(settingsTab, key, e.target.value)}
                      min={min}
                      max={max}
                      step={step}
                    />
                  </label>
                ))}
                {!status.indexDropCheckedToday
                  ? <p className="index-drop-status checking">⏳ 체크 전 (09:20 이후 확인)</p>
                  : status.indexDropBlockedToday
                    ? <p className="index-drop-status blocked">🔴 오늘 차단 발동됨</p>
                    : <p className="index-drop-status ok">✅ 미차단 (정상)</p>
                }
              </div>
              <button
                className="admin-save-btn"
                onClick={handleSaveConfig}
                disabled={saving}
              >
                {saving ? '저장 중...' : `설정 저장 (${settingsTab === 'paper' ? '모의투자' : '운영'})`}
              </button>
              {saveMsg && (
                <p className={`result-msg ${saveMsg.ok ? 'success' : 'fail'}`}>{saveMsg.text}</p>
              )}
            </div>

            {/* 수동 청산 */}
            <div className="admin-section">
              <h3 className="admin-section-title">수동 청산</h3>
              <p className="admin-note">종목코드를 쉼표로 구분하여 입력하면 즉시 시장가 매도합니다.<br/>매도 성공 시 todayBought 상태도 자동 정리됩니다.</p>
              <input
                type="text"
                className="admin-input"
                placeholder="예: 005930,293490,039200"
                value={exitTickers}
                onChange={e => setExitTickers(e.target.value)}
                style={{ width: '100%', marginBottom: '8px' }}
              />
              <button
                className="admin-toggle-btn pause"
                disabled={exiting || !exitTickers.trim()}
                onClick={handleManualExit}
              >
                {exiting ? '매도 중...' : '즉시 매도'}
              </button>
              {exitResult && (
                <div style={{ marginTop: '8px' }}>
                  {Object.entries(exitResult).map(([ticker, ok]) => (
                    <p key={ticker} className={`result-msg ${ok ? 'success' : 'fail'}`}>
                      {ticker}: {ok ? '매도 성공' : '매도 실패'}
                    </p>
                  ))}
                </div>
              )}
            </div>

            {/* MTS/외부 매도 처리 */}
            <div className="admin-section">
              <h3 className="admin-section-title">외부 매도 처리 (MTS)</h3>
              <p className="admin-note">MTS 또는 장외에서 직접 매도한 종목의 시스템 상태를 정리합니다.<br/>포트폴리오 DB에서 제거하고 전략 상태(todayBought, 트레일링스탑 등)를 초기화합니다.<br/>실제 매도 주문은 발생하지 않습니다.</p>
              <input
                type="text"
                className="admin-input"
                placeholder="예: 005930,293490,039200"
                value={markTickers}
                onChange={e => setMarkTickers(e.target.value)}
                style={{ width: '100%', marginBottom: '8px' }}
              />
              <button
                className="admin-toggle-btn"
                disabled={marking || !markTickers.trim()}
                onClick={handleMarkSold}
              >
                {marking ? '처리 중...' : '매도 처리'}
              </button>
              {markResult && (
                <div style={{ marginTop: '8px' }}>
                  {Object.entries(markResult).map(([ticker, res]) => (
                    <p key={ticker} className={`result-msg ${res === 'OK' ? 'success' : 'fail'}`}>
                      {ticker}: {res}
                    </p>
                  ))}
                </div>
              )}
            </div>

            {/* 볼린저 2차 매수 대기 현황 */}
            <div className="admin-section">
              <h3 className="admin-section-title">볼린저 2차 매수 대기 현황</h3>
              <p className="admin-note">1차 매수(BB 이탈) 후 2차 매수(RSI 과매도)를 기다리는 종목입니다. 예약금은 2차 매수 전까지 다른 종목·전략에 사용되지 않으며, 2차 매수 후 남은 금액은 자동으로 해제됩니다.</p>
              {(() => {
                const reservations = status.bollingerReservations || {}
                const entries = Object.entries(reservations)
                if (entries.length === 0) {
                  return <p className="admin-note" style={{ color: '#888' }}>대기 없음</p>
                }
                const total = entries.reduce((sum, [, entry]) => sum + entry.amount, 0)
                return (
                  <div style={{ marginTop: '6px' }}>
                    {entries.map(([ticker, entry]) => (
                      <div key={ticker} style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 0', borderBottom: '1px solid #333' }}>
                        <span style={{ fontWeight: 600 }}>{entry.stockName}({ticker})</span>
                        <span style={{ color: '#4fc3f7' }}>{entry.amount.toLocaleString()}원</span>
                      </div>
                    ))}
                    <div style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', fontWeight: 700 }}>
                      <span>합계</span>
                      <span style={{ color: '#81c784' }}>{total.toLocaleString()}원</span>
                    </div>
                  </div>
                )
              })()}
            </div>

            {/* 향후 추가 예정 */}
            <div className="admin-section admin-future">
              <h3 className="admin-section-title">향후 추가 예정</h3>
              <ul className="admin-future-list">
                <li>전략별 ON/OFF</li>
                <li>감시 종목 관리</li>
              </ul>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
