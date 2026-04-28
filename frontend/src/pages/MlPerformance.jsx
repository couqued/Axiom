import { useState, useEffect, useRef } from 'react'
import { getMlPerformanceSummary, getMlTradeHistory, getMlConfidenceTiers, mlDryRun, mlRetrain } from '../api/stockApi'

const CLOSE_REASON_KO = {
  'ML TP':       { label: 'TP 달성', color: '#4caf50' },
  'ML SL':       { label: 'SL 손절', color: '#f44336' },
  'ML 최대보유': { label: '기간만료', color: '#ff9800' },
}

function fmtPct(v, digits = 1) {
  if (v == null) return '-'
  return `${v >= 0 ? '+' : ''}${v.toFixed(digits)}%`
}

function fmtDate(s) {
  if (!s) return '-'
  // 'Z' 없으면 UTC로 명시해야 브라우저가 KST로 올바르게 변환
  const normalized = s.includes('T') && !s.endsWith('Z') ? s + 'Z' : s + 'T00:00:00Z'
  const d = new Date(normalized)
  const yy  = String(d.getFullYear()).slice(2)
  const mm  = String(d.getMonth() + 1).padStart(2, '0')
  const dd  = String(d.getDate()).padStart(2, '0')
  const hh  = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${yy}.${mm}.${dd} ${hh}:${min}`
}

// reason 문자열에서 expRet / days 파싱
// 예: "ML conf=78.5% expRet=4.2% days=3"
function parseReason(reason) {
  const ret  = reason?.match(/expRet=([\d.]+)%/)
  const days = reason?.match(/days=(\d+)/)
  return {
    expRet: ret  ? parseFloat(ret[1])  : null,
    days:   days ? parseInt(days[1])   : null,
  }
}

export default function MlPerformance() {
  const [summary, setSummary]         = useState(null)
  const [trades, setTrades]           = useState([])
  const [tiers, setTiers]             = useState([])
  const [page, setPage]               = useState(0)
  const [totalPages, setTotalPages]   = useState(0)
  const [loading, setLoading]         = useState(true)
  const [error, setError]             = useState(null)

  // 예측 섹션 상태
  const [predictions, setPredictions]     = useState([])
  const [predLoading, setPredLoading]     = useState(false)
  const [predError, setPredError]         = useState(null)
  const [predFetched, setPredFetched]     = useState(false)

  // 재학습 상태
  const [retraining, setRetraining]       = useState(false)
  const [retrainMsg, setRetrainMsg]       = useState(null)
  const pollRef                           = useRef(null)

  const stopPolling = () => {
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null }
  }

  useEffect(() => stopPolling, [])  // 언마운트 시 정리

  const startPolling = (preTrainedAt) => {
    stopPolling()
    pollRef.current = setInterval(async () => {
      try {
        const s = await getMlPerformanceSummary()
        if (s.modelTrainedAt && s.modelTrainedAt !== preTrainedAt) {
          stopPolling()
          setSummary(s)
          setRetrainMsg({ ok: true, text: '학습 완료!' })
        }
      } catch {}
    }, 20000)
  }

  const loadAll = (p = 0) => {
    setLoading(true)
    setError(null)
    Promise.all([
      getMlPerformanceSummary(),
      getMlTradeHistory(p, 20),
      getMlConfidenceTiers(),
    ])
      .then(([s, t, tr]) => {
        setSummary(s)
        setTrades(t.content ?? [])
        setTotalPages(t.totalPages ?? 0)
        setTiers(tr ?? [])
        setPage(p)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }

  const handleRetrain = async () => {
    setRetraining(true)
    setRetrainMsg(null)
    stopPolling()
    const preTrainedAt = summary?.modelTrainedAt ?? null
    try {
      await mlRetrain()
      setRetrainMsg({ ok: true, text: '학습 시작됨 — 완료 시 자동으로 표시됩니다' })
      startPolling(preTrainedAt)
    } catch (e) {
      setRetrainMsg({ ok: false, text: '재학습 요청 실패: ' + e.message })
    } finally {
      setRetraining(false)
    }
  }

  const loadPredictions = () => {
    setPredLoading(true)
    setPredError(null)
    mlDryRun()
      .then(data => {
        setPredictions(data ?? [])
        setPredFetched(true)
      })
      .catch(e => setPredError(e.message))
      .finally(() => setPredLoading(false))
  }

  useEffect(() => { loadAll(0) }, [])

  const winRateColor = summary
    ? summary.winRate >= 55 ? '#4caf50' : summary.winRate >= 40 ? '#ff9800' : '#f44336'
    : '#888'

  const candidateCount = predictions.filter(p => p.aboveThreshold && !p.deferred && !p.alreadyHeld).length

  return (
    <div className="page">
      <div className="page-header">
        <h2>ML 성과</h2>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button
            className="refresh-btn"
            onClick={handleRetrain}
            disabled={retraining}
          >
            {retraining ? '학습 중...' : 'ML 재학습'}
          </button>
          <button className="refresh-btn" onClick={() => loadAll(0)}>새로고침</button>
        </div>
      </div>
      {retrainMsg && (
        <p style={{ margin: '0 0 8px', fontSize: 12, color: retrainMsg.ok ? '#4caf50' : '#f44336' }}>
          {retrainMsg.text}
        </p>
      )}

      {/* ── 현재 ML 예측 ─────────────────────────────────────── */}
      <section className="card" style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <h3 style={{ margin: 0, fontSize: 14, color: '#aaa' }}>현재 ML 예측</h3>
          <button
            className="refresh-btn"
            onClick={loadPredictions}
            disabled={predLoading}
          >
            {predLoading ? '조회 중...' : '조회'}
          </button>
        </div>

        {!predFetched && !predLoading && (
          <p style={{ color: '#666', fontSize: 12, margin: 0 }}>
            전체 감시 종목 예측 조회 — 약 10~20초 소요됩니다.
          </p>
        )}
        {predLoading && (
          <p style={{ color: '#888', fontSize: 12, margin: 0 }}>전체 종목 ML 추론 중...</p>
        )}
        {predError && <div className="error" style={{ marginTop: 8 }}>오류: {predError}</div>}

        {predFetched && !predLoading && predictions.length > 0 && (
          <>
            <div style={{ marginBottom: 8, fontSize: 12, color: '#888' }}>
              총 {predictions.length}종목 중{' '}
              <span style={{ color: '#4caf50', fontWeight: 700 }}>{candidateCount}종목</span>{' '}
              실제 매수 후보 (임계값 이상 · EntryQuality 통과)
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto', overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <thead style={{ position: 'sticky', top: 0, background: '#121212', zIndex: 1 }}>
                  <tr style={{ borderBottom: '1px solid #333', color: '#888' }}>
                    <th style={th}>종목</th>
                    <th style={{ ...th, textAlign: 'right' }}>ML확신도</th>
                    <th style={{ ...th, textAlign: 'right' }}>진입배수</th>
                    <th style={{ ...th, textAlign: 'right' }}>실효점수</th>
                    <th style={{ ...th, textAlign: 'right' }}>예측수익</th>
                    <th style={{ ...th, textAlign: 'right' }}>예상일</th>
                    <th style={{ ...th, textAlign: 'center' }}>상태</th>
                  </tr>
                </thead>
                <tbody>
                  {predictions.map(p => {
                    const { expRet, days } = parseReason(p.reason)
                    const isCandidate = p.aboveThreshold && !p.deferred && !p.alreadyHeld
                    const rowBg = isCandidate
                      ? 'rgba(76,175,80,0.07)'
                      : p.alreadyHeld ? 'rgba(100,181,246,0.05)' : 'transparent'
                    return (
                      <tr key={p.ticker} style={{ borderBottom: '1px solid #222', background: rowBg }}>
                        <td style={td}>
                          <div style={{ fontWeight: isCandidate ? 600 : 400 }}>
                            {p.stockName ?? p.ticker}
                          </div>
                          <div style={{ color: '#666', fontSize: 11 }}>{p.ticker}</div>
                        </td>
                        <td style={{ ...td, textAlign: 'right',
                          color: p.aboveThreshold ? '#4caf50' : '#aaa',
                          fontWeight: p.aboveThreshold ? 700 : 400,
                        }}>
                          {(p.confidence * 100).toFixed(1)}%
                        </td>
                        <td style={{ ...td, textAlign: 'right',
                          color: p.deferred ? '#f44336'
                            : !p.aboveThreshold ? '#555'
                            : p.entryMultiplier >= 1.0 ? '#4caf50'
                            : p.entryMultiplier >= 0.85 ? '#ff9800' : '#f44336',
                        }}>
                          {p.deferred ? 'DEFER'
                            : !p.aboveThreshold ? '—'
                            : `×${p.entryMultiplier.toFixed(2)}`}
                        </td>
                        <td style={{ ...td, textAlign: 'right',
                          color: isCandidate ? '#fff' : '#555',
                          fontWeight: isCandidate ? 700 : 400,
                        }}>
                          {isCandidate ? p.effectiveScore.toFixed(1) : '—'}
                        </td>
                        <td style={{ ...td, textAlign: 'right', color: '#64b5f6' }}>
                          {expRet != null ? `+${expRet.toFixed(1)}%` : '-'}
                        </td>
                        <td style={{ ...td, textAlign: 'right', color: '#aaa' }}>
                          {days != null ? `${days}일` : '-'}
                        </td>
                        <td style={{ ...td, textAlign: 'center' }}>
                          {p.deferred
                            ? <span style={{ color: '#f44336', fontWeight: 700 }}>DEFER</span>
                            : p.alreadyHeld
                            ? <span style={{ color: '#64b5f6' }}>보유중</span>
                            : isCandidate
                            ? <span style={{ color: '#4caf50', fontWeight: 700 }}>▲ 매수후보</span>
                            : <span style={{ color: '#555' }}>—</span>
                          }
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </>
        )}
        {predFetched && !predLoading && predictions.length === 0 && (
          <p style={{ color: '#888', fontSize: 12, margin: '8px 0 0' }}>
            모델이 학습되지 않았거나 감시 종목이 없습니다.
          </p>
        )}
      </section>

      {loading && <div className="loading">로딩 중...</div>}
      {error   && <div className="error">오류: {error}</div>}

      {!loading && !error && summary && (
        <>
          {/* ── 모델 정보 ─────────────────────────────────────── */}
          <section className="card" style={{ marginBottom: 12 }}>
            <h3 style={{ margin: '0 0 10px', fontSize: 14, color: '#aaa' }}>모델 정보</h3>
            {summary.modelTrainedAt ? (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 16px', fontSize: 13 }}>
                <div><span style={{ color: '#888' }}>학습일시</span></div>
                <div style={{ textAlign: 'right' }}>{fmtDate(summary.modelTrainedAt)}</div>
                <div><span style={{ color: '#888' }}>학습 샘플</span></div>
                <div style={{ textAlign: 'right' }}>{summary.samples?.toLocaleString() ?? '-'}개</div>
                <div><span style={{ color: '#888' }}>분류 AUC</span></div>
                <div style={{ textAlign: 'right', color: '#64b5f6' }}>
                  {summary.valAuc != null ? `${(summary.valAuc * 100).toFixed(1)}%` : '-'}
                </div>
                <div><span style={{ color: '#888' }}>수익률 예측 오차</span></div>
                <div style={{ textAlign: 'right' }}>
                  {summary.valMaeRet != null ? `±${(summary.valMaeRet * 100).toFixed(2)}%` : '-'}
                </div>
              </div>
            ) : (
              <p style={{ color: '#888', fontSize: 13, margin: 0 }}>
                아직 모델이 없습니다. 금요일 16시 자동 학습 후 표시됩니다.
              </p>
            )}
          </section>

          {/* ── ML 매매 성과 요약 ─────────────────────────────── */}
          <section className="card" style={{ marginBottom: 12 }}>
            <h3 style={{ margin: '0 0 10px', fontSize: 14, color: '#aaa' }}>ML 매매 성과</h3>
            {summary.totalTrades === 0 ? (
              <p style={{ color: '#888', fontSize: 13, margin: 0 }}>아직 ML 전략 매매 이력이 없습니다.</p>
            ) : (
              <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
                <StatBox label="총 거래" value={`${summary.totalTrades}건`} />
                <StatBox label="승" value={`${summary.winCount}건`} color="#4caf50" />
                <StatBox label="패" value={`${summary.lossCount}건`} color="#f44336" />
                <StatBox
                  label="승률"
                  value={`${summary.winRate.toFixed(1)}%`}
                  color={winRateColor}
                  big
                />
                <StatBox
                  label="평균 실제 수익률"
                  value={fmtPct(summary.avgActualReturnPct, 2)}
                  color={summary.avgActualReturnPct >= 0 ? '#4caf50' : '#f44336'}
                />
              </div>
            )}
          </section>

          {/* ── 확신도 구간별 성과 ────────────────────────────── */}
          <section className="card" style={{ marginBottom: 12 }}>
            <h3 style={{ margin: '0 0 10px', fontSize: 14, color: '#aaa' }}>확신도 구간별 성과</h3>
            {tiers.length === 0 ? (
              <p style={{ color: '#888', fontSize: 13, margin: 0 }}>아직 ML 매매 이력이 없습니다.</p>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #333', color: '#888' }}>
                    <th style={th}>confidence 구간</th>
                    <th style={{ ...th, textAlign: 'right' }}>거래수</th>
                    <th style={{ ...th, textAlign: 'right' }}>승수</th>
                    <th style={{ ...th, textAlign: 'right' }}>승률</th>
                    <th style={{ ...th, textAlign: 'right' }}>평균수익률</th>
                  </tr>
                </thead>
                <tbody>
                  {tiers.map(t => {
                    const wrColor = t.winRate >= 60 ? '#4caf50' : t.winRate >= 40 ? '#ff9800' : '#f44336'
                    return (
                      <tr key={t.tier} style={{ borderBottom: '1px solid #222' }}>
                        <td style={{ ...td, fontWeight: 600 }}>{t.tier}</td>
                        <td style={{ ...td, textAlign: 'right', color: '#aaa' }}>
                          {t.tradeCount === 0 ? <span style={{ color: '#555' }}>-</span> : `${t.tradeCount}건`}
                        </td>
                        <td style={{ ...td, textAlign: 'right', color: '#aaa' }}>
                          {t.tradeCount === 0 ? <span style={{ color: '#555' }}>-</span> : `${t.winCount}건`}
                        </td>
                        <td style={{ ...td, textAlign: 'right', color: wrColor, fontWeight: 700 }}>
                          {t.tradeCount === 0 ? <span style={{ color: '#555' }}>-</span> : `${t.winRate.toFixed(1)}%`}
                        </td>
                        <td style={{ ...td, textAlign: 'right',
                          color: t.avgReturnPct >= 0 ? '#4caf50' : '#f44336' }}>
                          {t.tradeCount === 0 ? <span style={{ color: '#555' }}>-</span> : fmtPct(t.avgReturnPct, 2)}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}
          </section>

          {/* ── 거래 이력 테이블 ──────────────────────────────── */}
          {trades.length > 0 && (
            <section style={{ marginBottom: 12 }}>
              <h3 style={{ margin: '0 0 8px', fontSize: 14, color: '#aaa' }}>거래 이력</h3>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #333', color: '#888' }}>
                      <th style={th}>종목</th>
                      <th style={{ ...th, textAlign: 'right' }}>매수가</th>
                      <th style={{ ...th, textAlign: 'right' }}>매도가</th>
                      <th style={{ ...th, textAlign: 'right' }}>실제</th>
                      <th style={{ ...th, textAlign: 'right' }}>예측</th>
                      <th style={{ ...th, textAlign: 'right' }}>confidence</th>
                      <th style={th}>결과</th>
                      <th style={th}>날짜</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trades.map(t => {
                      const reason = CLOSE_REASON_KO[t.closeReason] ?? { label: t.closeReason, color: '#888' }
                      return (
                        <tr key={t.id} style={{ borderBottom: '1px solid #222' }}>
                          <td style={td}>
                            <div>{t.stockName ?? t.ticker}</div>
                            <div style={{ color: '#666', fontSize: 11 }}>{t.ticker}</div>
                          </td>
                          <td style={{ ...td, textAlign: 'right' }}>
                            {t.entryPrice?.toLocaleString()}
                          </td>
                          <td style={{ ...td, textAlign: 'right' }}>
                            {t.exitPrice?.toLocaleString()}
                          </td>
                          <td style={{ ...td, textAlign: 'right',
                            color: (t.actualReturnPct ?? 0) >= 0 ? '#4caf50' : '#f44336' }}>
                            {fmtPct(t.actualReturnPct)}
                          </td>
                          <td style={{ ...td, textAlign: 'right', color: '#64b5f6' }}>
                            {fmtPct(t.predictedReturnPct)}
                          </td>
                          <td style={{ ...td, textAlign: 'right' }}>
                            {t.confidence != null ? `${(t.confidence * 100).toFixed(0)}%` : '-'}
                          </td>
                          <td style={td}>
                            <span style={{ color: reason.color, fontWeight: 600, fontSize: 11 }}>
                              {reason.label}
                            </span>
                          </td>
                          <td style={{ ...td, color: '#888', whiteSpace: 'nowrap' }}>
                            {t.entryDate ?? '-'}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              {totalPages > 1 && (
                <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 10 }}>
                  <button className="refresh-btn" disabled={page === 0}
                    onClick={() => loadAll(page - 1)}>이전</button>
                  <span style={{ lineHeight: '30px', fontSize: 13, color: '#888' }}>
                    {page + 1} / {totalPages}
                  </span>
                  <button className="refresh-btn" disabled={page >= totalPages - 1}
                    onClick={() => loadAll(page + 1)}>다음</button>
                </div>
              )}
            </section>
          )}
        </>
      )}
    </div>
  )
}

function StatBox({ label, value, color = '#fff', big = false }) {
  return (
    <div style={{
      background: '#1e1e1e',
      borderRadius: 8,
      padding: '8px 14px',
      textAlign: 'center',
      minWidth: 70,
    }}>
      <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: big ? 20 : 15, fontWeight: 700, color }}>{value}</div>
    </div>
  )
}

const th = { padding: '6px 8px', textAlign: 'left', fontWeight: 500 }
const td = { padding: '7px 8px', verticalAlign: 'middle' }
