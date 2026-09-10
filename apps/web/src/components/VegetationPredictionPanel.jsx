import { useEffect, useState } from 'react'
import { Sparkles, AlertTriangle, Clock } from 'lucide-react'
import Card from './ui/Card'
import Badge from './ui/Badge'
import AnimatedNumber from './ui/AnimatedNumber'
import { LEVELS } from '../utils/classification'
import { getSummary, getRanking, getForecast, VegetationPredictionApiError } from '../services/vegetationPredictionApi'

// GreenV Dashboard integration with the Vegetation Prediction API (Phase 12).
//
// Everything shown here comes from the backend AS GIVEN -- vegetation_level, status,
// operational_confidence and the ranking order itself are never recomputed on this side (see
// services/greenv-vegetation-prediction/reports/integration.md). This component only reads the
// module's snapshot-backed endpoints (summary / ranking / one forecast by id) -- it never calls
// the dynamic POST /predict, so simply viewing this dashboard never triggers model inference.

const CONFIDENCE_STYLE = {
  high: { color: 'var(--level-1)', bg: 'var(--level-1-bg)', label: 'Alta' },
  medium: { color: 'var(--text-secondary)', bg: 'var(--bg-tertiary)', label: 'Média' },
  low: { color: 'var(--level-2)', bg: 'var(--level-2-bg)', label: 'Baixa' },
}

const STATUS_LABEL = {
  critical: 'Crítico — já ≥ 30 cm',
  forecast: 'Em previsão',
  beyond_horizon: 'Sem previsão em 120 dias',
  insufficient_data: 'Dados insuficientes',
}

function levelStyle(level) {
  return LEVELS[level] ?? LEVELS[0]
}

function formatKmRange(kmStart, kmEnd) {
  if (kmStart == null || kmEnd == null) return '—'
  const f = (v) => v.toFixed(1).replace('.', ',')
  return `KM ${f(kmStart)}–${f(kmEnd)}`
}

// "27 dias, faixa 0-61.6" must never read as a precise date -- see reports/integration.md §9.
// Language stays estimative ("~N dias", "faixa estimada") on purpose -- never "atingirá em N dias".
function describeEstimate(fc) {
  if (fc.status === 'critical') return 'Já no limite crítico'
  if (fc.status === 'beyond_horizon') return 'Sem cruzamento previsto dentro de 120 dias'
  if (fc.status === 'insufficient_data') return 'Dados insuficientes para estimar'
  const days = fc.days_until_critical
  const lo = fc.interval?.lower_days
  const hi = fc.interval?.upper_days
  if (days == null || lo == null || hi == null) return 'Estimativa indisponível'
  const horizonNote = fc.interval?.upper_is_horizon_bound
    ? ' (limite superior no teto de 120 dias — pode ser maior)'
    : ''
  return `~${Math.round(days)} dias · faixa estimada ${Math.round(lo)}–${Math.round(hi)} dias${horizonNote}`
}

const s = {
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 2 },
  titleRow: { display: 'flex', alignItems: 'center', gap: 8 },
  syntheticBadge: {
    display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10.5, fontWeight: 700,
    color: 'var(--motiva)', background: 'var(--motiva-subtle)', padding: '3px 9px', borderRadius: 20,
  },
  hint: { fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 },
  sectionTitle: {
    fontSize: 11.5, fontWeight: 700, color: 'var(--text-secondary)',
    textTransform: 'uppercase', letterSpacing: 0.3, margin: '14px 0 4px',
  },
  sectionHint: { fontSize: 10.5, color: 'var(--text-muted)', marginBottom: 6 },
  kpiRow: { display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 8, marginBottom: 16 },
  kpiCell: { padding: '8px 10px', background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)', textAlign: 'center' },
  kpiValue: { fontSize: 18, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  kpiLabel: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2 },
  row: {
    display: 'grid', gridTemplateColumns: '1.4fr 0.6fr 1.3fr 1.6fr 0.9fr', gap: 8,
    alignItems: 'center', padding: '8px 6px', borderBottom: '1px solid var(--border)',
    cursor: 'pointer', fontSize: 12,
  },
  rowSelected: { background: 'var(--motiva-subtle)', borderRadius: 'var(--radius-sm)' },
  trechoCell: { fontWeight: 600 },
  trechoSub: { fontSize: 10.5, color: 'var(--text-muted)' },
  estimateCell: { color: 'var(--text-secondary)', fontSize: 11.5 },
  notReadyNote: {
    display: 'flex', alignItems: 'center', gap: 5, fontSize: 10.5, color: 'var(--level-2)', marginTop: 3,
  },
  detail: {
    marginTop: 14, padding: 14, background: 'var(--bg-secondary)', borderRadius: 'var(--radius-sm)',
  },
  detailGrid: { display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10, fontSize: 12.5 },
  detailLabel: { color: 'var(--text-muted)', fontSize: 10.5 },
  detailValue: { fontWeight: 600, marginTop: 1 },
  stateMsg: { fontSize: 12.5, color: 'var(--text-secondary)', padding: '10px 2px' },
  errorMsg: {
    display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, color: 'var(--level-3)',
    padding: '10px 2px',
  },
}

export default function VegetationPredictionPanel({ criticalShown = 5, forecastShown = 5 }) {
  const [summary, setSummary] = useState(null)
  const [ranking, setRanking] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const [selectedId, setSelectedId] = useState(null)
  const [selectedForecast, setSelectedForecast] = useState(null)
  const [selectedError, setSelectedError] = useState(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    // No `limit` here: the demo needs to reach into the ranking far enough to find `forecast`
    // -status rows too (today's snapshot has 61 `critical` ones first) -- fetching the full,
    // already-ordered ranking and filtering client-side (order-preserving `.filter`, never a
    // re-sort) is what lets both sections below exist without touching the ranking rule itself.
    Promise.all([getSummary(), getRanking()])
      .then(([summaryData, rankingData]) => {
        if (cancelled) return
        setSummary(summaryData)
        setRanking(rankingData)
      })
      .catch((err) => {
        if (cancelled) return
        setError(err instanceof VegetationPredictionApiError ? err.message : 'erro desconhecido')
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  // Both slices preserve the backend's own order -- `.filter()` never reorders, and neither list
  // is sorted again here. This is presentation only: the frozen rule (critical -> forecast by
  // soonest -> beyond_horizon -> insufficient_data) is untouched; these are two WINDOWS into the
  // same single ranking, not a new one.
  const criticalItems = ranking?.filter((fc) => fc.status === 'critical').slice(0, criticalShown) ?? []
  const forecastItems = ranking?.filter((fc) => fc.status === 'forecast').slice(0, forecastShown) ?? []

  function selectTrecho(trechoId) {
    setSelectedId(trechoId)
    setSelectedForecast(null)
    setSelectedError(null)
    getForecast(trechoId)
      .then(setSelectedForecast)
      .catch((err) => setSelectedError(err instanceof VegetationPredictionApiError ? err.message : 'erro desconhecido'))
  }

  function renderRow(fc) {
    const lvl = levelStyle(fc.vegetation_level)
    const conf = CONFIDENCE_STYLE[fc.operational_confidence] ?? CONFIDENCE_STYLE.medium
    return (
      <div
        key={fc.trecho_id}
        style={{ ...s.row, ...(selectedId === fc.trecho_id ? s.rowSelected : {}) }}
        onClick={() => selectTrecho(fc.trecho_id)}
      >
        <div>
          <div style={s.trechoCell}>{formatKmRange(fc.km_start, fc.km_end)}</div>
          <div style={s.trechoSub}>{fc.sentido ?? '—'}</div>
        </div>
        <Badge color={lvl.color} bg={lvl.bg}>{lvl.label}</Badge>
        <div>{fc.current_height_cm != null ? `${fc.current_height_cm.toFixed(1)} cm` : '—'}</div>
        <div style={s.estimateCell}>{describeEstimate(fc)}</div>
        <div>
          <Badge color={conf.color} bg={conf.bg}>{conf.label}</Badge>
          {fc.operational_status === 'not-ready' && (
            <div style={s.notReadyNote}><AlertTriangle size={10} /> not-ready</div>
          )}
        </div>
      </div>
    )
  }

  return (
    <Card delay={0.35}>
      <div style={s.header}>
        <div style={s.titleRow}>
          <Sparkles size={14} color="var(--motiva)" />
          <span style={{ fontSize: 13, fontWeight: 700 }}>Previsão de vegetação</span>
        </div>
        <span style={s.syntheticBadge}>Previsões demonstrativas — dados sintéticos</span>
      </div>
      <div style={s.hint}>
        Estimativa de dias até o limite crítico (30 cm) e prioridade operacional, geradas pelo
        módulo de previsão (protótipo, não validado em campo).
      </div>

      {loading && <div style={s.stateMsg}>Carregando previsão…</div>}

      {!loading && error && (
        <div style={s.errorMsg}>
          <AlertTriangle size={14} />
          Previsão temporariamente indisponível.
        </div>
      )}

      {!loading && !error && summary && (
        <div style={s.kpiRow}>
          {[
            ['Trechos', summary.total_trechos],
            ['Críticos', summary.critical],
            ['Em previsão', summary.forecast],
            ['Fora do horizonte', summary.beyond_horizon],
            ['Dados insuf.', summary.insufficient_data],
          ].map(([label, value]) => (
            <div key={label} style={s.kpiCell}>
              <div style={s.kpiValue}><AnimatedNumber value={value ?? 0} /></div>
              <div style={s.kpiLabel}>{label}</div>
            </div>
          ))}
        </div>
      )}

      {!loading && !error && ranking && ranking.length > 0 && (
        <>
          <div style={s.sectionTitle}>Prioridade atual</div>
          <div style={s.sectionHint}>Trechos já no limite crítico (≥ 30 cm) — máxima prioridade no ranking.</div>
          {criticalItems.length > 0
            ? criticalItems.map(renderRow)
            : <div style={s.stateMsg}>Nenhum trecho crítico no momento.</div>}

          <div style={s.sectionTitle}>Previsões — próximos a atingir 30 cm</div>
          <div style={s.sectionHint}>
            Ainda abaixo do limite, ordenados pela própria estimativa do backend (menor prazo primeiro) —
            o valor preditivo do módulo: antecipar antes de o trecho virar crítico.
          </div>
          {forecastItems.length > 0
            ? forecastItems.map(renderRow)
            : <div style={s.stateMsg}>Nenhum trecho em previsão no momento.</div>}
        </>
      )}

      {!loading && !error && ranking && ranking.length === 0 && (
        <div style={s.stateMsg}>Nenhum trecho disponível no snapshot atual.</div>
      )}

      {selectedId && (
        <div style={s.detail}>
          {!selectedForecast && !selectedError && (
            <div style={s.stateMsg}>Carregando detalhe de {selectedId}…</div>
          )}
          {selectedError && (
            <div style={s.errorMsg}><AlertTriangle size={14} /> Previsão temporariamente indisponível.</div>
          )}
          {selectedForecast && (
            <>
              <div style={{ fontSize: 12.5, fontWeight: 700, marginBottom: 8 }}>
                {selectedForecast.trecho_id} — {formatKmRange(selectedForecast.km_start, selectedForecast.km_end)} ({selectedForecast.sentido})
              </div>
              <div style={s.detailGrid}>
                <div>
                  <div style={s.detailLabel}>Altura atual</div>
                  <div style={s.detailValue}>
                    {selectedForecast.current_height_cm != null ? `${selectedForecast.current_height_cm.toFixed(1)} cm` : '—'}
                  </div>
                </div>
                <div>
                  <div style={s.detailLabel}>Nível</div>
                  <div style={s.detailValue}>{levelStyle(selectedForecast.vegetation_level).label}</div>
                </div>
                <div>
                  <div style={s.detailLabel}>Limite crítico</div>
                  <div style={s.detailValue}>{selectedForecast.critical_height_cm} cm</div>
                </div>
                <div>
                  <div style={s.detailLabel}>Status</div>
                  <div style={s.detailValue}>{STATUS_LABEL[selectedForecast.status] ?? selectedForecast.status}</div>
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <div style={s.detailLabel}><Clock size={10} style={{ verticalAlign: -1 }} /> Estimativa</div>
                  <div style={s.detailValue}>{describeEstimate(selectedForecast)}</div>
                </div>
                <div>
                  <div style={s.detailLabel}>Confiança operacional</div>
                  <div style={s.detailValue}>
                    {(CONFIDENCE_STYLE[selectedForecast.operational_confidence] ?? CONFIDENCE_STYLE.medium).label}
                  </div>
                  {selectedForecast.operational_status === 'not-ready' && (
                    <div style={s.notReadyNote}>
                      <AlertTriangle size={10} /> origem not-ready — classificação/confiança não são confiáveis
                    </div>
                  )}
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </Card>
  )
}
