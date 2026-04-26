import { useState, useEffect } from 'react'
import { getMlPerformanceSummary, getMlTradeHistory } from '../api/stockApi'

const CLOSE_REASON_KO = {
  'ML TP':   { label: 'TP 달성', color: '#4caf50' },
  'ML SL':   { label: 'SL 손절', color: '#f44336' },
  'ML 최대보유': { label: '기간만료', color: '#ff9800' },
}

function fmtPct(v, digits = 1) {
  if (v == null) return '-'
  return `${v >= 0 ? '+' : ''}${v.toFixed(digits)}%`
}

function fmtDate(s) {
  if (!s) return '-'
  // "2026-04-25T09:15:30" → "26.04.25 09:15"
  const d = new Date(s + (s.includes('T') ? '' : 'T00:00:00'))
  const yy  = String(d.getFullYear()).slice(2)
  const mm  = String(d.getMonth() + 1).padStart(2, '0')
  const dd  = String(d.getDate()).padStart(2, '0')
  const hh  = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${yy}.${mm}.${dd} ${hh}:${min}`
}

export default function MlPerformance() {
  const [summary, setSummary]       = useState(null)
  const [trades, setTrades]         = useState([])
  const [page, setPage]             = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading]       = useState(true)
  const [error, setError]           = useState(null)

  const loadAll = (p = 0) => {
    setLoading(true)
    setError(null)
    Promise.all([
      getMlPerformanceSummary(),
      getMlTradeHistory(p, 20),
    ])
      .then(([s, t]) => {
        setSummary(s)
        setTrades(t.content ?? [])
        setTotalPages(t.totalPages ?? 0)
        setPage(p)
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }

  useEffect(() => { loadAll(0) }, [])

  const winRateColor = summary
    ? summary.winRate >= 55 ? '#4caf50' : summary.winRate >= 40 ? '#ff9800' : '#f44336'
    : '#888'

  return (
    <div className="page">
      <div className="page-header">
        <h2>ML 성과</h2>
        <button className="refresh-btn" onClick={() => loadAll(0)}>새로고침</button>
      </div>

      {loading && <div className="loading">로딩 중...</div>}
      {error   && <div className="error">오류: {error}</div>}

      {!loading && !error && summary && (
        <>
          {/* 모델 정보 */}
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
              <p style={{ color: '#888', fontSize: 13, margin: 0 }}>아직 모델이 없습니다. 금요일 16시 자동 학습 후 표시됩니다.</p>
            )}
          </section>

          {/* 성과 요약 */}
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

          {/* 거래 이력 테이블 */}
          {trades.length > 0 && (
            <section style={{ marginBottom: 12 }}>
              <h3 style={{ margin: '0 0 8px', fontSize: 14, color: '#aaa' }}>거래 이력</h3>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #333', color: '#888' }}>
                      <th style={th}>종목</th>
                      <th style={th}>매수가</th>
                      <th style={th}>매도가</th>
                      <th style={th}>실제</th>
                      <th style={th}>예측</th>
                      <th style={th}>신뢰도</th>
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
                            <span style={{
                              color: reason.color,
                              fontWeight: 600,
                              fontSize: 11,
                            }}>{reason.label}</span>
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
                  <button
                    className="refresh-btn"
                    disabled={page === 0}
                    onClick={() => loadAll(page - 1)}
                  >이전</button>
                  <span style={{ lineHeight: '30px', fontSize: 13, color: '#888' }}>
                    {page + 1} / {totalPages}
                  </span>
                  <button
                    className="refresh-btn"
                    disabled={page >= totalPages - 1}
                    onClick={() => loadAll(page + 1)}
                  >다음</button>
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
