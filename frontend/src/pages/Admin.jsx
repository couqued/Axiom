import { useState, useEffect } from 'react'
import { getAdminStatus, pauseTrading, resumeTrading, updateAdminConfig } from '../api/stockApi'

export default function Admin({ onClose, onConfigUpdated }) {
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [toggling, setToggling] = useState(false)
  const [switching, setSwitching] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState(null)

  // 설정 탭: 어떤 모드의 설정을 편집 중인지
  const [settingsTab, setSettingsTab] = useState('paper')

  // 입력 값: paper/real 각각 독립
  const [fields, setFields] = useState({
    paper: { invest: '', maxPos: '', bollingerMaxPos: '', trailing: '', timeCut: '', indexDrop: '' },
    real:  { invest: '', maxPos: '', bollingerMaxPos: '', trailing: '', timeCut: '', indexDrop: '' },
  })

  const initFields = (s) => ({
    paper: {
      invest:          String(s.paper.investAmountKrw),
      maxPos:          String(s.paper.maxPositions),
      bollingerMaxPos: String(s.paper.bollingerMaxPositions),
      trailing:        String(s.paper.trailingStopPct),
      timeCut:         String(s.paper.timeCutDays),
      indexDrop:       String(s.paper.indexDropBlockPct),
    },
    real: {
      invest:          String(s.real.investAmountKrw),
      maxPos:          String(s.real.maxPositions),
      bollingerMaxPos: String(s.real.bollingerMaxPositions),
      trailing:        String(s.real.trailingStopPct),
      timeCut:         String(s.real.timeCutDays),
      indexDrop:       String(s.real.indexDropBlockPct),
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
    const bollingerMaxPos = parseInt(f.bollingerMaxPos, 10)
    const trailing        = parseFloat(f.trailing)
    const timeCut         = parseInt(f.timeCut, 10)
    const indexDrop       = parseFloat(f.indexDrop)
    if (isNaN(invest) || invest < 1 || isNaN(maxPos) || maxPos < 1
        || isNaN(trailing) || trailing <= 0 || isNaN(timeCut) || timeCut < 1
        || isNaN(indexDrop) || indexDrop < 0) {
      setSaveMsg({ ok: false, text: '올바른 숫자를 입력하세요' })
      return
    }
    if (isNaN(bollingerMaxPos) || bollingerMaxPos < 1 || bollingerMaxPos >= maxPos) {
      setSaveMsg({ ok: false, text: '볼린저 슬롯 수는 1 이상, 최대종목 수 미만이어야 합니다' })
      return
    }
    setSaving(true)
    setSaveMsg(null)
    try {
      const res = await updateAdminConfig({
        targetMode: settingsTab,
        investAmountKrw: invest,
        maxPositions: maxPos,
        bollingerMaxPositions: bollingerMaxPos,
        trailingStopPct: trailing,
        timeCutDays: timeCut,
        indexDropBlockPct: indexDrop,
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
                  { key: 'invest',          label: '1회 매수금액 (원)',       min: 1,   max: undefined, step: 1 },
                  { key: 'maxPos',          label: '최대 보유 종목 수',        min: 1,   max: 20,        step: 1 },
                  { key: 'bollingerMaxPos', label: '볼린저밴드 전용 슬롯',     min: 1,   max: 19,        step: 1 },
                  { key: 'trailing',        label: '트레일링 스탑 (%)',        min: 0.1, max: 30,        step: 0.1 },
                  { key: 'timeCut',         label: '타임 컷 (거래일)',         min: 1,   max: 30,        step: 1 },
                  { key: 'indexDrop',       label: '지수 하락 매수차단 (%)',   min: 0,   max: 10,        step: 0.1 },
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
                {(() => {
                  const maxPos = parseInt(fields[settingsTab].maxPos, 10)
                  const bollinger = parseInt(fields[settingsTab].bollingerMaxPos, 10)
                  const trend = (!isNaN(maxPos) && !isNaN(bollinger)) ? maxPos - bollinger : '—'
                  return <p className="admin-note" style={{ marginTop: '4px' }}>추세전략 슬롯: {trend}개 (자동)</p>
                })()}
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
