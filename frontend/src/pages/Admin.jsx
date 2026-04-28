import { useState, useEffect } from 'react'
import { getAdminStatus, pauseTrading, resumeTrading, pauseSellTrading, resumeSellTrading, pauseMlTrading, resumeMlTrading, pauseMlSellTrading, resumeMlSellTrading, updateAdminConfig, manualExit, markSold } from '../api/stockApi'

export default function Admin({ onClose, onConfigUpdated }) {
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [toggling, setToggling] = useState(false)
  const [sellToggling, setSellToggling] = useState(false)
  const [mlToggling, setMlToggling] = useState(false)
  const [mlSellToggling, setMlSellToggling] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState(null)

  const [exitTickers, setExitTickers] = useState('')
  const [exitResult, setExitResult] = useState(null)
  const [exiting, setExiting] = useState(false)

  const [markTickers, setMarkTickers] = useState('')
  const [markResult, setMarkResult] = useState(null)
  const [marking, setMarking] = useState(false)

  const [fields, setFields] = useState({
    invest: '', maxPos: '', trailing: '', profitTake: '', timeCut: '', indexDrop: '',
    volBreakoutDaily: '', goldenCrossDaily: '', bollingerDaily: '',
    mlDaily: '', mlThreshold: '', mlEntryTiming: true,
  })

  const initFields = (s) => ({
    invest:           String(s.settings.investAmountKrw),
    maxPos:           String(s.settings.maxPositions),
    trailing:         String(s.settings.trailingStopPct),
    profitTake:       String(s.settings.profitTakePct),
    timeCut:          String(s.settings.timeCutDays),
    indexDrop:        String(s.settings.indexDropBlockPct),
    volBreakoutDaily: String(s.settings.volatilityBreakoutDailyLimit),
    goldenCrossDaily: String(s.settings.goldenCrossDailyLimit),
    bollingerDaily:   String(s.settings.bollingerDailyLimit),
    mlDaily:          String(s.settings.mlDailyLimit ?? 0),
    mlThreshold:      String(Math.round((s.settings.mlBuyThreshold ?? 0.6) * 100)),
    mlEntryTiming:    s.settings.mlEntryTimingEnabled ?? true,
  })

  useEffect(() => {
    getAdminStatus()
      .then(s => {
        setStatus(s)
        setFields(initFields(s))
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const setField = (key, val) =>
    setFields(prev => ({ ...prev, [key]: val }))

  const handleToggle = async () => {
    if (!status) return
    const isPaused = status.settings?.paused
    setToggling(true)
    setError(null)
    try {
      const res = isPaused ? await resumeTrading() : await pauseTrading()
      setStatus(res)
    } catch (e) {
      setError('상태 변경 실패: ' + e.message)
    } finally {
      setToggling(false)
    }
  }

  const handleSellToggle = async () => {
    if (!status) return
    const isSellPaused = status.settings?.sellPaused
    setSellToggling(true)
    setError(null)
    try {
      const res = isSellPaused ? await resumeSellTrading() : await pauseSellTrading()
      setStatus(res)
    } catch (e) {
      setError('매도 상태 변경 실패: ' + e.message)
    } finally {
      setSellToggling(false)
    }
  }

  const handleMlToggle = async () => {
    if (!status) return
    const isMlPaused = status.settings?.mlPaused
    setMlToggling(true)
    setError(null)
    try {
      const res = isMlPaused ? await resumeMlTrading() : await pauseMlTrading()
      setStatus(res)
    } catch (e) {
      setError('ML 매수 상태 변경 실패: ' + e.message)
    } finally {
      setMlToggling(false)
    }
  }

  const handleMlSellToggle = async () => {
    if (!status) return
    const isMlSellPaused = status.settings?.mlSellPaused
    setMlSellToggling(true)
    setError(null)
    try {
      const res = isMlSellPaused ? await resumeMlSellTrading() : await pauseMlSellTrading()
      setStatus(res)
    } catch (e) {
      setError('ML 매도 상태 변경 실패: ' + e.message)
    } finally {
      setMlSellToggling(false)
    }
  }

  const handleStrategyModeChange = async (mode) => {
    if (mode === status?.strategyMode) return
    try {
      const res = await updateAdminConfig({ strategyMode: mode })
      setStatus(res)
    } catch (e) {
      setError('전략 모드 변경 실패: ' + e.message)
    }
  }

  const handleSaveConfig = async () => {
    const invest          = parseInt(fields.invest, 10)
    const maxPos          = parseInt(fields.maxPos, 10)
    const trailing        = parseFloat(fields.trailing)
    const profitTake      = parseFloat(fields.profitTake)
    const timeCut         = parseInt(fields.timeCut, 10)
    const indexDrop       = parseFloat(fields.indexDrop)
    const volBreakoutDaily = parseInt(fields.volBreakoutDaily, 10)
    const goldenCrossDaily = parseInt(fields.goldenCrossDaily, 10)
    const bollingerDaily   = parseInt(fields.bollingerDaily, 10)
    const mlDaily          = parseInt(fields.mlDaily, 10)
    const mlThresholdPct   = parseInt(fields.mlThreshold, 10)
    if (isNaN(invest) || invest < 1 || isNaN(maxPos) || maxPos < 1
        || isNaN(trailing) || trailing <= 0 || isNaN(timeCut) || timeCut < 1
        || isNaN(indexDrop) || indexDrop < 0) {
      setSaveMsg({ ok: false, text: '올바른 숫자를 입력하세요' })
      return
    }
    if (isNaN(profitTake) || profitTake < 0) {
      setSaveMsg({ ok: false, text: '익절 비율은 0 이상의 숫자를 입력하세요 (0=비활성)' })
      return
    }
    if (isNaN(volBreakoutDaily) || volBreakoutDaily < 0 || isNaN(goldenCrossDaily) || goldenCrossDaily < 0 || isNaN(bollingerDaily) || bollingerDaily < 0) {
      setSaveMsg({ ok: false, text: '일일 한도는 0 이상의 숫자를 입력하세요' })
      return
    }
    if (isNaN(mlDaily) || mlDaily < 0) {
      setSaveMsg({ ok: false, text: 'ML 일일 한도는 0 이상의 숫자를 입력하세요' })
      return
    }
    if (isNaN(mlThresholdPct) || mlThresholdPct < 50 || mlThresholdPct > 95) {
      setSaveMsg({ ok: false, text: 'ML 매수 confidence는 50~95 사이의 정수로 입력하세요' })
      return
    }
    setSaving(true)
    setSaveMsg(null)
    try {
      const res = await updateAdminConfig({
        investAmountKrw: invest,
        maxPositions: maxPos,
        trailingStopPct: trailing,
        profitTakePct: profitTake,
        timeCutDays: timeCut,
        indexDropBlockPct: indexDrop,
        volatilityBreakoutDailyLimit: volBreakoutDaily,
        goldenCrossDailyLimit: goldenCrossDaily,
        bollingerDailyLimit: bollingerDaily,
        mlDailyLimit: mlDaily,
        mlBuyThreshold: mlThresholdPct / 100,
        mlEntryTimingEnabled: !!fields.mlEntryTiming,
      })
      setStatus(res)
      setFields(initFields(res))
      setSaveMsg({ ok: true, text: '설정이 저장되었습니다' })
      onConfigUpdated?.(res)
    } catch (e) {
      setSaveMsg({ ok: false, text: '저장 실패: ' + e.message })
    } finally {
      setSaving(false)
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

            {/* 긴급 제어 */}
            <div className="admin-section">
              <h3 className="admin-section-title">긴급 제어</h3>
              <div className="admin-emergency-grid">
                <div className="admin-emergency-col">
                  <div className="admin-status-row">
                    <span className={`admin-status-dot ${status.settings?.paused ? 'stopped' : 'running'}`} />
                    <span className="admin-status-label">
                      {status.settings?.paused ? '매수 중단 중' : '매수 실행 중'}
                    </span>
                  </div>
                  <button
                    className={`admin-toggle-btn ${status.settings?.paused ? 'resume' : 'pause'}`}
                    onClick={handleToggle}
                    disabled={toggling}
                  >
                    {toggling
                      ? '처리 중...'
                      : status.settings?.paused
                        ? '▶ 매매 재개'
                        : '■ 매매 중단'}
                  </button>
                </div>
                <div className="admin-emergency-col">
                  <div className="admin-status-row">
                    <span className={`admin-status-dot ${status.settings?.sellPaused ? 'sell-stopped' : 'running'}`} />
                    <span className="admin-status-label">
                      {status.settings?.sellPaused ? '매도 중지 중' : '매도 실행 중'}
                    </span>
                  </div>
                  <button
                    className={`admin-toggle-btn ${status.settings?.sellPaused ? 'resume-sell' : 'pause-sell'}`}
                    onClick={handleSellToggle}
                    disabled={sellToggling}
                  >
                    {sellToggling
                      ? '처리 중...'
                      : status.settings?.sellPaused
                        ? '▶ 매도 재개'
                        : '■ 매도 중지'}
                  </button>
                </div>
              </div>
              <p className="admin-note">ML 전략 제외한 기존 전략(골든크로스·변동성돌파·볼린저)에만 적용됩니다<br/>매도 중지 시 TimeCut·강제청산 포함 모든 자동 매도가 중단됩니다</p>
            </div>

            {/* 투자 설정 */}
            <div className="admin-section">
              <h3 className="admin-section-title">투자 설정</h3>
              <div className="admin-fields">
                {[
                  { key: 'invest',           label: '1회 매수금액 (원)',              min: 1,   max: undefined, step: 1 },
                  { key: 'maxPos',           label: '최대 보유 종목 수',               min: 1,   max: 20,        step: 1 },
                  { key: 'trailing',         label: '트레일링 스탑 (%)',               min: 0.1, max: 30,        step: 0.1 },
                  { key: 'profitTake',       label: '익절 비율 (%, 0=비활성)',         min: 0,   max: 30,        step: 0.1 },
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
                      value={fields[key]}
                      onChange={e => setField(key, e.target.value)}
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
                {saving ? '저장 중...' : '설정 저장'}
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

            {/* ML 전략 설정 */}
            <div className="admin-section">
              <h3 className="admin-section-title">ML 전략 설정</h3>
              <p className="admin-note">
                ML 예측 전략 전용 설정. 기존 3개 전략과 독립적으로 동작합니다.<br/>
                분봉 미세조정: 분봉 기준 눌림목 대기 + 갭업/시초가 과열 구간 매수 보류.
              </p>
              <div className="admin-emergency-grid">
                <div className="admin-emergency-col">
                  <div className="admin-status-row">
                    <span className={`admin-status-dot ${status.settings?.mlPaused ? 'stopped' : 'running'}`} />
                    <span className="admin-status-label">
                      {status.settings?.mlPaused ? 'ML 매수 중단 중' : 'ML 매수 실행 중'}
                    </span>
                  </div>
                  <button
                    className={`admin-toggle-btn ${status.settings?.mlPaused ? 'resume' : 'pause'}`}
                    onClick={handleMlToggle}
                    disabled={mlToggling}
                  >
                    {mlToggling
                      ? '처리 중...'
                      : status.settings?.mlPaused
                        ? '▶ ML 매매 재개'
                        : '■ ML 매매 중단'}
                  </button>
                </div>
                <div className="admin-emergency-col">
                  <div className="admin-status-row">
                    <span className={`admin-status-dot ${status.settings?.mlSellPaused ? 'sell-stopped' : 'running'}`} />
                    <span className="admin-status-label">
                      {status.settings?.mlSellPaused ? 'ML 매도 중지 중' : 'ML 매도 실행 중'}
                    </span>
                  </div>
                  <button
                    className={`admin-toggle-btn ${status.settings?.mlSellPaused ? 'resume-sell' : 'pause-sell'}`}
                    onClick={handleMlSellToggle}
                    disabled={mlSellToggling}
                  >
                    {mlSellToggling
                      ? '처리 중...'
                      : status.settings?.mlSellPaused
                        ? '▶ ML 매도 재개'
                        : '■ ML 매도 중지'}
                  </button>
                </div>
              </div>
              <p className="admin-note" style={{ marginBottom: '12px' }}>ML 전략에만 적용됩니다. TP/SL/최대보유일 청산 포함 ML 관련 모든 매도가 중단됩니다</p>
              <div className="admin-fields">
                <label className="admin-field">
                  <span className="admin-field-label">ML 일일 보유 한도 (0=비활성)</span>
                  <input
                    type="number"
                    className="admin-input"
                    value={fields.mlDaily}
                    onChange={e => setField('mlDaily', e.target.value)}
                    min={0}
                    max={20}
                    step={1}
                  />
                </label>
                <label className="admin-field">
                  <span className="admin-field-label">ML 매수 confidence (%, 50~95)</span>
                  <input
                    type="number"
                    className="admin-input"
                    value={fields.mlThreshold}
                    onChange={e => setField('mlThreshold', e.target.value)}
                    min={50}
                    max={95}
                    step={1}
                  />
                </label>
                <label className="admin-field" style={{ gridColumn: '1 / -1' }}>
                  <span className="admin-field-label">
                    <input
                      type="checkbox"
                      checked={!!fields.mlEntryTiming}
                      onChange={e => setField('mlEntryTiming', e.target.checked)}
                      style={{ marginRight: '6px' }}
                    />
                    분봉 미세조정 사용 (눌림목 대기 + 갭업/FOMO 가드)
                  </span>
                </label>
              </div>
              <button
                className="admin-save-btn"
                onClick={handleSaveConfig}
                disabled={saving}
              >
                {saving ? '저장 중...' : 'ML 설정 저장'}
              </button>
              {saveMsg && (
                <p className={`result-msg ${saveMsg.ok ? 'success' : 'fail'}`}>{saveMsg.text}</p>
              )}
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
