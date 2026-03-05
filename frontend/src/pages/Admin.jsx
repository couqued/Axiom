import { useState, useEffect } from 'react'
import { getAdminStatus, pauseTrading, resumeTrading, updateAdminConfig } from '../api/stockApi'

export default function Admin({ onClose }) {
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [toggling, setToggling] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState(null)

  const [investInput, setInvestInput] = useState('')
  const [maxPosInput, setMaxPosInput] = useState('')

  useEffect(() => {
    getAdminStatus()
      .then(s => {
        setStatus(s)
        setInvestInput(String(s.investAmountKrw))
        setMaxPosInput(String(s.maxPositions))
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const handleToggle = async () => {
    setToggling(true)
    setError(null)
    try {
      const res = status.paused ? await resumeTrading() : await pauseTrading()
      setStatus(res)
    } catch (e) {
      setError('상태 변경 실패: ' + e.message)
    } finally {
      setToggling(false)
    }
  }

  const handleSaveConfig = async () => {
    const invest = parseInt(investInput, 10)
    const maxPos = parseInt(maxPosInput, 10)
    if (isNaN(invest) || invest < 1 || isNaN(maxPos) || maxPos < 1) {
      setSaveMsg({ ok: false, text: '올바른 숫자를 입력하세요' })
      return
    }
    setSaving(true)
    setSaveMsg(null)
    try {
      const res = await updateAdminConfig({ investAmountKrw: invest, maxPositions: maxPos })
      setStatus(res)
      setSaveMsg({ ok: true, text: '설정이 저장되었습니다' })
    } catch (e) {
      setSaveMsg({ ok: false, text: '저장 실패: ' + e.message })
    } finally {
      setSaving(false)
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
            {/* 긴급 제어 */}
            <div className="admin-section">
              <h3 className="admin-section-title">긴급 제어</h3>
              <div className="admin-status-row">
                <span className={`admin-status-dot ${status.paused ? 'stopped' : 'running'}`} />
                <span className="admin-status-label">
                  {status.paused ? '매매 중단 중' : '매매 실행 중'}
                </span>
              </div>
              <button
                className={`admin-toggle-btn ${status.paused ? 'resume' : 'pause'}`}
                onClick={handleToggle}
                disabled={toggling}
              >
                {toggling
                  ? '처리 중...'
                  : status.paused
                    ? '▶ 매매 재개'
                    : '■ 매매 중단'}
              </button>
              <p className="admin-note">중단 중에도 15:20 강제 청산은 계속 실행됩니다</p>
            </div>

            {/* 투자 설정 */}
            <div className="admin-section">
              <h3 className="admin-section-title">투자 설정</h3>
              <div className="admin-fields">
                <label className="admin-field">
                  <span className="admin-field-label">1회 매수금액 (원)</span>
                  <input
                    type="number"
                    className="admin-input"
                    value={investInput}
                    onChange={e => setInvestInput(e.target.value)}
                    min="1"
                  />
                </label>
                <label className="admin-field">
                  <span className="admin-field-label">최대 보유 종목 수</span>
                  <input
                    type="number"
                    className="admin-input"
                    value={maxPosInput}
                    onChange={e => setMaxPosInput(e.target.value)}
                    min="1"
                    max="20"
                  />
                </label>
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

            {/* 향후 추가 예정 */}
            <div className="admin-section admin-future">
              <h3 className="admin-section-title">향후 추가 예정</h3>
              <ul className="admin-future-list">
                <li>트레일링 스탑 % 조정</li>
                <li>타임 컷 일수 조정</li>
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
