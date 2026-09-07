import { motion, AnimatePresence } from 'framer-motion'
import { X, Sparkles, MapPin, Trash2, Wand2, ClipboardCheck, ArrowRight } from 'lucide-react'
import { LEVELS, formatArea, getFeatureId } from '../utils/classification'

const s = {
  panel: {
    position: 'absolute', top: 0, right: 0, bottom: 0, width: 340,
    background: 'white', borderLeft: '1px solid var(--border)',
    boxShadow: 'var(--shadow-float)', zIndex: 900,
    display: 'flex', flexDirection: 'column',
  },
  header: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '16px 18px', borderBottom: '1px solid var(--border)',
  },
  title: { fontSize: 14.5, fontWeight: 700 },
  subtitle: { fontSize: 11.5, color: 'var(--text-muted)', marginTop: 2 },
  closeBtn: {
    background: 'var(--bg-secondary)', border: '1px solid var(--border)', borderRadius: 8,
    width: 28, height: 28, display: 'flex', alignItems: 'center', justifyContent: 'center',
    cursor: 'pointer', color: 'var(--text-secondary)',
  },
  body: { flex: 1, overflowY: 'auto', padding: '14px 18px' },
  sectionTitle: {
    display: 'flex', alignItems: 'center', gap: 6, fontSize: 10.5, fontWeight: 700,
    color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em',
    marginBottom: 10, marginTop: 18,
  },
  suggestionCard: {
    padding: 12, borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)',
    background: 'var(--motiva-subtle)', marginBottom: 8,
  },
  suggestionMeta: { fontSize: 12, fontWeight: 600, marginBottom: 3 },
  suggestionReason: { fontSize: 11, color: 'var(--text-secondary)', marginBottom: 8 },
  suggestionActions: { display: 'flex', gap: 6 },
  smallBtn: {
    flex: 1, padding: '6px 8px', borderRadius: 6, fontSize: 11.5, fontWeight: 600,
    border: '1px solid var(--motiva)', background: 'white', color: 'var(--motiva)',
    cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center',
    justifyContent: 'center', gap: 4,
  },
  smallBtnFilled: {
    flex: 1, padding: '6px 8px', borderRadius: 6, fontSize: 11.5, fontWeight: 600,
    border: 'none', background: 'var(--motiva)', color: 'white',
    cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center',
    justifyContent: 'center', gap: 4,
  },
  empty: { fontSize: 12, color: 'var(--text-muted)', padding: '10px 2px', lineHeight: 1.6 },
  selRow: {
    display: 'flex', alignItems: 'center', gap: 8, padding: '9px 10px',
    background: 'var(--bg-secondary)', borderRadius: 8, marginBottom: 6,
  },
  selBadge: {
    width: 20, height: 20, borderRadius: '50%', background: 'var(--motiva)', color: 'white',
    fontSize: 10.5, fontWeight: 700, display: 'flex', alignItems: 'center',
    justifyContent: 'center', flexShrink: 0, fontFamily: 'var(--font-mono)',
  },
  selInfo: { flex: 1, minWidth: 0 },
  selName: { fontSize: 12, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' },
  selMeta: { fontSize: 10.5, color: 'var(--text-muted)' },
  removeBtn: { background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', display: 'flex' },
  footer: { borderTop: '1px solid var(--border)', padding: 16 },
  summaryRow: { display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 },
  summaryLabel: { color: 'var(--text-secondary)' },
  summaryValue: { fontWeight: 700, fontFamily: 'var(--font-mono)' },
  calcBtn: {
    width: '100%', padding: '10px 14px', borderRadius: 'var(--radius-sm)', fontSize: 12.5,
    fontWeight: 700, border: '1.5px solid var(--motiva)', background: 'white', color: 'var(--motiva)',
    cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center',
    justifyContent: 'center', gap: 7, marginTop: 12,
  },
  createBtn: {
    width: '100%', padding: '11px 14px', borderRadius: 'var(--radius-sm)', fontSize: 13,
    fontWeight: 700, border: 'none', background: 'var(--motiva)', color: 'white',
    cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center',
    justifyContent: 'center', gap: 7, marginTop: 8,
  },
  calcHint: {
    fontSize: 10.5, color: 'var(--text-muted)', lineHeight: 1.5, marginTop: 6, textAlign: 'center',
  },
}

export default function RoutePlannerPanel({
  suggestions, selection, routeResult,
  onAddSuggestion, onRemove, onCalculate, onCreateCombined, onClose,
}) {
  const totalArea = selection.reduce((sum, f) => sum + (f.properties.area_m2 || 0), 0)

  return (
    <motion.div
      style={s.panel}
      initial={{ x: 340 }}
      animate={{ x: 0 }}
      exit={{ x: 340 }}
      transition={{ type: 'spring', stiffness: 380, damping: 38 }}
    >
      <div style={s.header}>
        <div>
          <div style={s.title}>Planejar Rota</div>
          <div style={s.subtitle}>Combine trechos próximos numa só saída de equipe</div>
        </div>
        <button style={s.closeBtn} onClick={onClose}><X size={14} /></button>
      </div>

      <div style={s.body}>
        <div style={s.sectionTitle}><Sparkles size={12} /> Sugestões automáticas</div>
        {suggestions.length === 0 && (
          <div style={s.empty}>Nenhuma sugestão no momento — todos os trechos próximos de
            nível 2+ já têm OS aberta, ou estão isolados.</div>
        )}
        {suggestions.map(sug => (
          <div key={sug.id} style={s.suggestionCard}>
            <div style={s.suggestionMeta}>
              {sug.features.length} trechos · {LEVELS[sug.maxLevel].label} · {formatArea(sug.totalArea)}
            </div>
            <div style={s.suggestionReason}>{sug.reason}</div>
            <div style={s.suggestionActions}>
              <button style={s.smallBtn} onClick={() => onAddSuggestion(sug.features)}>
                <MapPin size={12} /> Adicionar à rota
              </button>
              <button style={s.smallBtnFilled} onClick={() => onCreateCombined(sug.features)}>
                Criar OS <ArrowRight size={12} />
              </button>
            </div>
          </div>
        ))}

        <div style={s.sectionTitle}><MapPin size={12} /> Seleção manual ({selection.length})</div>
        {selection.length === 0 && (
          <div style={s.empty}>Clique em trechos no mapa para adicioná-los à rota, ou aceite
            uma sugestão acima.</div>
        )}
        <AnimatePresence initial={false}>
          {(routeResult ? routeResult.ordered : selection).map((f, i) => {
            const level = f.properties.vegetation_level || 1
            return (
              <motion.div
                key={getFeatureId(f)} style={s.selRow}
                initial={{ opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 12 }} transition={{ duration: 0.15 }}
              >
                <div style={s.selBadge}>{i + 1}</div>
                <div style={s.selInfo}>
                  <div style={s.selName}>{f.properties.name}</div>
                  <div style={s.selMeta}>{LEVELS[level].label} · {formatArea(f.properties.area_m2)}</div>
                </div>
                <button style={s.removeBtn} onClick={() => onRemove(f)}><Trash2 size={13} /></button>
              </motion.div>
            )
          })}
        </AnimatePresence>
      </div>

      <div style={s.footer}>
        <div style={s.summaryRow}>
          <span style={s.summaryLabel}>Trechos selecionados</span>
          <span style={s.summaryValue}>{selection.length}</span>
        </div>
        <div style={s.summaryRow}>
          <span style={s.summaryLabel}>Área total</span>
          <span style={s.summaryValue}>{formatArea(totalArea)}</span>
        </div>
        {routeResult && (
          <div style={s.summaryRow}>
            <span style={s.summaryLabel}>Distância estimada</span>
            <span style={s.summaryValue}>{routeResult.totalDistanceKm.toFixed(1).replace('.', ',')} km</span>
          </div>
        )}

        <button style={s.calcBtn} disabled={selection.length < 2} onClick={onCalculate}>
          <Wand2 size={14} /> Calcular ordem ideal
        </button>
        <div style={s.calcHint}>Ordena pela quilometragem da via, num só sentido — evita sugerir travessia para a pista contrária.</div>
        <button
          style={s.createBtn} disabled={selection.length === 0}
          onClick={() => onCreateCombined(routeResult ? routeResult.ordered : selection)}
        >
          <ClipboardCheck size={14} /> Criar OS combinada
        </button>
      </div>
    </motion.div>
  )
}
