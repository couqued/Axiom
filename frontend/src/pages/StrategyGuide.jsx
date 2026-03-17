export default function StrategyGuide() {
  return (
    <div className="page">
      <h2 style={{ margin: '0 0 16px' }}>전략 케이스 가이드</h2>

      {/* ───── 매수 신호 근접도 점수 체계 ───── */}
      <section style={{ marginBottom: '28px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '14px' }}>
          <span style={{ background: '#2a2a1a', color: '#ffd966', border: '1px solid #ffd966', borderRadius: '6px', padding: '3px 10px', fontSize: '13px', fontWeight: 'bold' }}>매수 신호 근접도 랭킹</span>
          <span style={{ fontSize: '12px', color: '#aaa' }}>전략 페이지 "조회" 버튼 결과</span>
        </div>

        <div className="skipped-card" style={{ marginBottom: '14px' }}>
          <h3 style={{ margin: '0 0 4px' }}>점수 구간 — RSI + 볼린저밴드</h3>
          <p style={{ margin: '0 0 14px', fontSize: '12px', color: '#aaa' }}>
            BB 이탈 종목은 항상 접근 중 종목보다 상위 보장 (51점 vs 최대 50점)<br/>
            BB 20% 초과 이격 종목은 하단 표시 (점수 없음, 근접도 기준 정렬)
          </p>

          {/* 점수 구간 요약 바 */}
          <div style={{ background: '#111', borderRadius: '8px', padding: '12px', marginBottom: '14px' }}>
            <div style={{ fontSize: '11px', color: '#666', marginBottom: '8px' }}>점수 구간</div>
            {[
              { range: '51 ~ 150점', label: 'BB 이탈 + RSI < 30', color: '#ff4d4d', desc: '2차 조건 충족' },
              { range: '51 ~ 100점', label: 'BB 이탈 + RSI ≥ 30', color: '#ffd966', desc: '1차 충족 (RSI 대기)' },
              { range: '1 ~ 50점',  label: 'BB 하단까지 0~20%',  color: '#4fa3ff', desc: '-X.X%' },
              { range: '0점', label: 'BB 하단까지 20% 초과', color: '#444', desc: '하단 표시 (근접도 정렬)' },
            ].map((item, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                <span style={{ minWidth: '90px', fontSize: '12px', fontWeight: 'bold', color: item.color }}>{item.range}</span>
                <span style={{ fontSize: '11px', color: '#888', flex: 1 }}>{item.label}</span>
                <span style={{ fontSize: '11px', background: item.color + '22', color: item.color, border: `1px solid ${item.color}66`, borderRadius: '4px', padding: '1px 6px' }}>{item.desc}</span>
              </div>
            ))}
          </div>

          {/* Case 1: 2차 조건 충족 */}
          <CaseCard
            badge="CASE 1"
            badgeColor="#ff4d4d"
            title="2차 조건 충족 — 최상위 노출"
            desc="현재가 < BB 하단 AND RSI < 30 → 51~150점"
            rows={[
              { label: '현재가', value: '17,600원', highlight: true },
              { label: 'BB 하단 (매수기준가)', value: '18,500원', highlight: true },
              { label: 'RSI(14)', value: '27.4', color: '#ff4d4d' },
              { label: '이격도 (gapPct)', value: '4.9% (BB 하단까지 남은 RSI 거리)', color: '#aaa' },
              { label: '점수', value: '112점', color: '#ff4d4d' },
            ]}
            signal={{ type: '2차 조건 충족', color: '#ffd966' }}
            note="BB 이탈 베이스 50점 + bbScore(최대50) + rsiScore(최대50) = 최소 51점. 항상 접근 중 종목보다 상위."
          />

          {/* Case 2: 1차 충족 RSI 대기 */}
          <CaseCard
            badge="CASE 2"
            badgeColor="#ffd966"
            title="1차 충족 (RSI 대기) — 상위 노출"
            desc="현재가 < BB 하단 AND RSI ≥ 30 → 51~100점"
            rows={[
              { label: '현재가', value: '18,200원', highlight: true },
              { label: 'BB 하단 (매수기준가)', value: '18,500원', highlight: true },
              { label: 'RSI(14)', value: '33.1', color: '#e0e0e0' },
              { label: '이격도 (gapPct)', value: '9.7% (RSI 30까지 남은 거리)', color: '#aaa' },
              { label: '점수', value: '68점', color: '#ffd966' },
            ]}
            signal={{ type: '1차 충족 (RSI 대기)', color: '#ffd966' }}
            note="BB 이탈 베이스 50점 + bbScore. rsiScore=0 (RSI 아직 30 이상). 최소 51점으로 접근 중 종목보다 항상 상위."
          />

          {/* Case 3: BB 접근 중 */}
          <CaseCard
            badge="CASE 3"
            badgeColor="#4fa3ff"
            title="BB 접근 중 (0~20%) — 하위 노출"
            desc="현재가 ≥ BB 하단 AND 이격 ≤ 20% → 최대 50점"
            rows={[
              { label: '현재가', value: '19,800원' },
              { label: 'BB 하단 (매수기준가)', value: '18,500원' },
              { label: 'RSI(14)', value: '44.2', color: '#e0e0e0' },
              { label: '이격도 (gapPct)', value: '6.6%', color: '#4fa3ff' },
              { label: '점수', value: '27점', color: '#ffd966' },
            ]}
            signal={{ type: '-6.6%', color: '#4fa3ff' }}
            note="(20 - 6.6) / 20 * 30 = 20.1점 (근접도) + (50-44.2)/50 * 20 = 6.9점 (RSI보너스) = 27점. BB 이탈 종목보다 항상 하위."
          />

          {/* Case 4: 하단 표시 */}
          <CaseCard
            badge="CASE 4"
            badgeColor="#444"
            title="BB 20% 초과 이격 — 하단 표시 (점수 없음)"
            desc="현재가 ≥ BB 하단 AND 이격 > 20% → 점수 0 → 목록 하단에 근접도 기준 정렬"
            rows={[
              { label: '현재가', value: '23,000원' },
              { label: 'BB 하단 (매수기준가)', value: '18,500원' },
              { label: '이격도 (gapPct)', value: '24.3%', color: '#666' },
              { label: '점수', value: '-', color: '#666' },
            ]}
            signal={null}
            note="이격이 너무 커서 근접도 점수 미부여. 목록 하단에 gapPct 오름차순으로 표시 (BB 하단에 가까운 순)."
          />
        </div>
      </section>

      {/* ───── 횡보장 ───── */}
      <section style={{ marginBottom: '28px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '14px' }}>
          <span style={{ background: '#2a3a2a', color: '#66bb6a', border: '1px solid #66bb6a', borderRadius: '6px', padding: '3px 10px', fontSize: '13px', fontWeight: 'bold' }}>횡보장 SIDEWAYS</span>
          <span style={{ fontSize: '12px', color: '#aaa' }}>코스피 종가 &lt; MA20</span>
        </div>

        {/* RSI+볼린저밴드 */}
        <div className="skipped-card" style={{ marginBottom: '14px' }}>
          <h3 style={{ margin: '0 0 4px' }}>RSI + 볼린저밴드</h3>
          <p style={{ margin: '0 0 14px', fontSize: '12px', color: '#aaa' }}>
            1차 매수: 현재가 &lt; BB 하단 (RSI 무관)<br/>
            2차 매수: stage=1 보유 중 + RSI &lt; 30 (물타기)<br/>
            익절: RSI &gt; 70 <strong>OR</strong> 현재가 ≥ BB 상단
          </p>

          {/* Case A */}
          <CaseCard
            badge="CASE A"
            badgeColor="#ff4d4d"
            title="1차 매수 — BB 하단 이탈"
            desc="현재가 < BB 하단 → RSI 관계없이 즉시 1차 매수 (stage=1)"
            rows={[
              { label: '현재가', value: '18,200원', highlight: true },
              { label: 'BB 하단 (매수기준가)', value: '18,500원', highlight: true },
              { label: 'RSI(14)', value: '33.1', color: '#e0e0e0' },
              { label: '매수 단계', value: 'stage=1 저장', color: '#ffd966' },
            ]}
            signal={{ type: 'BUY', color: '#ff4d4d' }}
            note="⚡ 1차 매수 직후 트레일링 스탑은 보류 상태 — RSI < 30 도달 시 2차 매수(물타기) 대기"
            exitRows={[
              { label: '익절 조건', value: 'RSI > 70 OR 현재가 ≥ BB 상단 (OR 조건)' },
              { label: '트레일링 스탑', value: '2차 매수(stage=2) 이후 활성화, 고점 대비 -7%' },
              { label: '타임 컷', value: '3거래일 후 수익 < 1.5% 또는 MA5 아래 → 강제 매도' },
            ]}
          />

          {/* Case B */}
          <CaseCard
            badge="CASE B"
            badgeColor="#ff9800"
            title="2차 매수 — 물타기 (stage=1 보유 중 RSI < 30)"
            desc="1차 매수 보유 중(stage=1) + RSI < 30 → 추가 매수로 stage=2 전환"
            rows={[
              { label: '현재가', value: '17,600원' },
              { label: 'BB 하단', value: '18,500원' },
              { label: 'RSI(14)', value: '27.4', highlight: true, color: '#ff4d4d' },
              { label: '현재 stage', value: 'stage=1 → stage=2 갱신', color: '#ff9800' },
            ]}
            signal={{ type: 'BUY', color: '#ff9800' }}
            note="⚡ 물타기 매수 실행 — maxPositions 제한 적용 안 됨 (기존 포지션 추가). 트레일링 스탑 이 시점부터 활성화"
            exitRows={[
              { label: '익절 조건', value: 'RSI > 70 OR 현재가 ≥ BB 상단 (OR 조건)' },
              { label: '트레일링 스탑', value: '고점 대비 -7% 하락 시 즉시 매도' },
              { label: '타임 컷', value: '3거래일 후 수익 < 1.5% 또는 MA5 아래 → 강제 매도' },
            ]}
          />

          {/* Case C */}
          <CaseCard
            badge="CASE C"
            badgeColor="#ffd966"
            title="1차 보유 중 — RSI 대기"
            desc="stage=1 보유 중이지만 RSI ≥ 30 → 2차 매수 조건 미충족, HOLD"
            rows={[
              { label: '현재가', value: '17,900원' },
              { label: 'BB 하단', value: '18,500원' },
              { label: 'RSI(14)', value: '34.8', color: '#e0e0e0' },
              { label: '현재 stage', value: 'stage=1 (2차 매수 대기 중)', color: '#ffd966' },
            ]}
            signal={null}
            note="RSI가 30 미만으로 떨어지면 다음 사이클에서 2차 매수 실행 / 동시에 매 사이클마다 익절 조건(RSI>70 OR BB상단) 자동 평가"
          />

          {/* Case D */}
          <CaseCard
            badge="CASE D"
            badgeColor="#4fa3ff"
            title="미진입 — BB 하단 대기"
            desc="현재가 > BB 하단 → 진입 조건 미충족, gapPct 표시"
            rows={[
              { label: '현재가', value: '19,800원' },
              { label: 'BB 하단 (매수기준가)', value: '18,500원' },
              { label: 'RSI(14)', value: '48.2', color: '#e0e0e0' },
              { label: '기준가 상태', value: '-6.7%', color: '#4fa3ff' },
            ]}
            signal={null}
            note="BB 하단까지 -6.7% 더 하락해야 1차 매수 조건 충족"
          />
        </div>
      </section>

      {/* ───── 상승장 ───── */}
      <section>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '14px' }}>
          <span style={{ background: '#2a2a3a', color: '#4fa3ff', border: '1px solid #4fa3ff', borderRadius: '6px', padding: '3px 10px', fontSize: '13px', fontWeight: 'bold' }}>상승장 BULLISH</span>
          <span style={{ fontSize: '12px', color: '#aaa' }}>코스피 종가 ≥ MA20</span>
        </div>

        {/* 변동성 돌파 */}
        <div className="skipped-card" style={{ marginBottom: '14px' }}>
          <h3 style={{ margin: '0 0 4px' }}>변동성 돌파</h3>
          <p style={{ margin: '0 0 14px', fontSize: '12px', color: '#aaa' }}>
            목표가 = 전일 종가 + (전일 고가 - 전일 저가) × 0.5<br/>
            매수 기준: 현재가 ≥ 목표가 (targetPrice, threshold)
          </p>

          {/* Case A */}
          <CaseCard
            badge="CASE A"
            badgeColor="#ff4d4d"
            title="목표가 돌파 — 매수 신호"
            desc="현재가 ≥ 목표가 → gapPct ≤ 0"
            rows={[
              { label: '전일 종가', value: '72,000원' },
              { label: '전일 변동폭', value: '3,200원 (고가-저가)' },
              { label: '목표가 (매수기준가)', value: '73,600원', highlight: true },
              { label: '현재가', value: '73,900원', highlight: true },
              { label: '기준가 상태', value: '목표가 돌파', color: '#ffd966' },
              { label: 'gapPct', value: '-0.4% (초과 달성)', color: '#ffd966' },
            ]}
            signal={{ type: 'BUY', color: '#ff4d4d' }}
            note="⚡ 장중 최초 돌파 시 BUY 주문 → 15:20 강제 청산"
            exitRows={[
              { label: '당일 청산', value: '15:20 ForceExitScheduler 강제 매도' },
              { label: '트레일링 스탑', value: '고점 대비 -7% 하락 시 즉시 매도' },
            ]}
          />

          {/* Case B */}
          <CaseCard
            badge="CASE B"
            badgeColor="#4fa3ff"
            title="목표가 미달 — 대기"
            desc="현재가 < 목표가 → gapPct > 0"
            rows={[
              { label: '목표가 (매수기준가)', value: '73,600원' },
              { label: '현재가', value: '71,400원' },
              { label: '기준가 상태', value: '-3.0%', color: '#4fa3ff' },
            ]}
            signal={null}
            note="목표가까지 +3.0% 더 상승해야 돌파 조건 충족"
          />
        </div>

        {/* 골든크로스 */}
        <div className="skipped-card">
          <h3 style={{ margin: '0 0 4px' }}>골든크로스</h3>
          <p style={{ margin: '0 0 14px', fontSize: '12px', color: '#aaa' }}>
            threshold = MA20 (매수), EMA20 (매도)<br/>
            매수: SMA5 ≥ SMA20 → 골든크로스 (SMA 기준)<br/>
            매도: EMA5 &lt; EMA20 → 데드크로스 (EMA 기준, 빠른 청산)
          </p>

          {/* Case A */}
          <CaseCard
            badge="CASE A"
            badgeColor="#ff4d4d"
            title="골든크로스 발생 — 매수 신호"
            desc="MA5 ≥ MA20 → gapPct ≤ 0 (크로스 완료)"
            rows={[
              { label: 'MA5', value: '48,320원', highlight: true, color: '#ffd966' },
              { label: 'MA20 (매수기준가)', value: '47,800원', highlight: true },
              { label: '현재가', value: '48,500원' },
              { label: '기준가 상태', value: '골든크로스 발생', color: '#ffd966' },
              { label: 'gapPct', value: '-1.1% (MA5 > MA20)', color: '#ffd966' },
            ]}
            signal={{ type: 'BUY', color: '#ff4d4d' }}
            note="⚡ MA5가 MA20 상향 돌파한 직후 사이클에서 BUY"
            exitRows={[
              { label: '데드크로스 매도', value: 'EMA5 < EMA20 전환 시 SELL (EMA 기준)' },
              { label: '트레일링 스탑', value: '고점 대비 -7% 하락 시 즉시 매도' },
              { label: '타임 컷', value: '매수 후 3거래일 경과 시 강제 매도' },
            ]}
          />

          {/* Case B */}
          <CaseCard
            badge="CASE B"
            badgeColor="#4fa3ff"
            title="크로스 미발생 — 대기"
            desc="MA5 < MA20 → gapPct > 0"
            rows={[
              { label: 'MA5', value: '46,200원' },
              { label: 'MA20 (매수기준가)', value: '47,800원' },
              { label: '현재가', value: '46,500원' },
              { label: '기준가 상태', value: '-3.3%', color: '#4fa3ff' },
            ]}
            signal={null}
            note="MA5가 MA20 아래 → 크로스 미발생 (HOLD)"
          />

          {/* Case C — 데드크로스 매도 */}
          <CaseCard
            badge="CASE C"
            badgeColor="#ff4d4d"
            title="데드크로스 발생 — 매도 신호 (EMA 기준)"
            desc="보유 중인 상태에서 EMA5 < EMA20 전환 → 매도 (매수보다 빠르게 반응)"
            rows={[
              { label: 'EMA5', value: '47,100원' },
              { label: 'EMA20', value: '47,800원', highlight: true },
              { label: '현재가', value: '47,000원' },
              { label: '보유 여부', value: '보유 중' },
            ]}
            signal={{ type: 'SELL', color: '#4fa3ff' }}
            note="EMA는 최근 가격에 가중치를 주어 SMA보다 빠르게 하락을 감지. 매수는 SMA(노이즈 필터), 매도는 EMA(빠른 청산) 사용"
          />
        </div>
      </section>

      {/* ───── 공통 리스크 관리 ───── */}
      <section style={{ marginTop: '28px' }}>
        <div className="skipped-card">
          <h3 style={{ margin: '0 0 12px' }}>공통 리스크 관리</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <RiskRow
              icon="🔻"
              title="트레일링 스탑"
              desc="매수 후 고점 대비 -7% 하락 시 즉시 SELL. 모든 전략 공통 적용."
            />
            <RiskRow
              icon="⏱"
              title="타임 컷"
              desc="매수 후 3거래일 경과 시 강제 SELL. 변동성돌파 전략 제외."
            />
            <RiskRow
              icon="📉"
              title="지수 하락 매수 차단"
              desc="오늘 코스피 시초가가 전일 종가 대비 설정 %이상 급락 시 당일 신규 매수 전면 차단."
            />
            <RiskRow
              icon="🚨"
              title="시장경보 종목 스킵"
              desc="KIS API에서 투자 주의·경고·위험 종목 반환 시 해당 사이클 매수 스킵."
            />
            <RiskRow
              icon="🔢"
              title="최대 보유 종목 제한"
              desc="보유 수량 + 이번 사이클 매수 수 ≥ maxPositions(기본 3) 시 신규 매수 스킵. 단, RSI+볼린저밴드 2차 매수(물타기, stage=1→2)는 기존 포지션 추가이므로 제한 적용 안 됨."
            />
          </div>
        </div>
      </section>
    </div>
  )
}

/* ─── 내부 컴포넌트 ─── */

function CaseCard({ badge, badgeColor, title, desc, rows, signal, note, exitRows }) {
  return (
    <div style={{
      background: '#1a1a2e',
      border: `1px solid ${badgeColor}44`,
      borderRadius: '8px',
      padding: '12px',
      marginBottom: '10px',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
        <span style={{ background: badgeColor + '22', color: badgeColor, border: `1px solid ${badgeColor}`, borderRadius: '4px', padding: '1px 7px', fontSize: '11px', fontWeight: 'bold' }}>{badge}</span>
        <span style={{ fontWeight: 'bold', fontSize: '14px' }}>{title}</span>
        {signal && (
          <span style={{ marginLeft: 'auto', background: signal.color + '22', color: signal.color, border: `1px solid ${signal.color}`, borderRadius: '4px', padding: '2px 8px', fontSize: '12px', fontWeight: 'bold' }}>
            {signal.type}
          </span>
        )}
      </div>
      <p style={{ margin: '0 0 10px', fontSize: '12px', color: '#aaa' }}>{desc}</p>

      <div style={{ background: '#111', borderRadius: '6px', padding: '8px 10px', marginBottom: '8px' }}>
        {rows.map((r, i) => (
          <div key={i} style={{ display: 'flex', justifyContent: 'space-between', padding: '3px 0', borderBottom: i < rows.length - 1 ? '1px solid #222' : 'none', fontSize: '13px' }}>
            <span style={{ color: '#888' }}>{r.label}</span>
            <span style={{ color: r.color || (r.highlight ? '#fff' : '#ccc'), fontWeight: r.highlight ? 'bold' : 'normal' }}>{r.value}</span>
          </div>
        ))}
      </div>

      {note && (
        <p style={{ margin: '0 0 8px', fontSize: '11px', color: '#aaa', background: '#0d1117', borderRadius: '4px', padding: '6px 8px' }}>{note}</p>
      )}

      {exitRows && exitRows.length > 0 && (
        <div>
          <p style={{ margin: '6px 0 4px', fontSize: '11px', color: '#666' }}>— 청산 조건 —</p>
          {exitRows.map((r, i) => (
            <div key={i} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', padding: '2px 0', color: '#aaa' }}>
              <span style={{ color: '#666' }}>{r.label}</span>
              <span>{r.value}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function RiskRow({ icon, title, desc }) {
  return (
    <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-start' }}>
      <span style={{ fontSize: '16px', lineHeight: 1.4 }}>{icon}</span>
      <div>
        <div style={{ fontSize: '13px', fontWeight: 'bold', color: '#e0e0e0', marginBottom: '2px' }}>{title}</div>
        <div style={{ fontSize: '12px', color: '#888' }}>{desc}</div>
      </div>
    </div>
  )
}
