import { LEVELS, EQUIPMENT_TYPES, formatArea } from '../utils/classification'

const s = {
  sidebar: {
    width: 300, height: '100vh', background: 'white',
    borderRight: '1px solid var(--border)', display: 'flex',
    flexDirection: 'column', overflow: 'hidden', flexShrink: 0,
  },
  header: {
    padding: '20px 20px 16px',
    borderBottom: '1px solid var(--border)',
    background: '#5e22f3',
  },
  logo: { display: 'flex', alignItems: 'center', gap: 10 },
  logoIcon: {
    width: 34, height: 34, borderRadius: 10,
    background: 'rgb(11, 4, 82)', display: 'flex',
    alignItems: 'center', justifyContent: 'center', fontSize: 18,
  },
  title: { fontSize: 18, fontWeight: 700, color: 'white', letterSpacing: '-0.02em' },
  subtitle: {
    fontSize: 10, color: 'rgba(255,255,255,0.7)', marginTop: 1,
    letterSpacing: '0.06em', textTransform: 'uppercase',
  },
  body: { flex: 1, overflowY: 'auto', padding: '16px 20px' },
  section: { marginBottom: 22 },
  sectionTitle: {
    fontSize: 10, fontWeight: 700, color: 'var(--text-muted)',
    textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 10,
  },
  toggle: {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '10px 12px', background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-sm)', marginBottom: 6, cursor: 'pointer',
    border: '1px solid var(--border)', transition: 'border-color 0.15s',
  },
  toggleLabel: { fontSize: 13, fontWeight: 500 },
  sw: (on) => ({
    width: 36, height: 20, borderRadius: 10, position: 'relative',
    background: on ? '#5e22f3' : '#ccc', transition: 'background 0.2s', flexShrink: 0,
  }),
  swDot: (on) => ({
    width: 16, height: 16, borderRadius: '50%', background: 'white',
    position: 'absolute', top: 2, left: on ? 18 : 2, transition: 'left 0.2s',
  }),
  chip: (active, color) => ({
    display: 'inline-flex', alignItems: 'center', gap: 5,
    padding: '5px 12px', borderRadius: 20, fontSize: 12, fontWeight: 600,
    cursor: 'pointer', marginRight: 6, marginBottom: 6, transition: 'all 0.15s',
    border: `1.5px solid ${active ? color : 'var(--border)'}`,
    background: active ? `${color}15` : 'transparent',
    color: active ? color : 'var(--text-secondary)',
  }),
  dot: (c) => ({ width: 8, height: 8, borderRadius: '50%', background: c }),
  featureCard: {
    padding: 14, background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-md)', border: '1px solid var(--border)',
  },
  featureRow: {
    display: 'flex', justifyContent: 'space-between',
    fontSize: 12, color: 'var(--text-secondary)', marginBottom: 4,
  },
  btn: {
    width: '100%', padding: '10px 16px', background: '#5e22f3', color: 'white',
    border: 'none', borderRadius: 'var(--radius-sm)', fontSize: 13,
    fontWeight: 600, cursor: 'pointer', marginTop: 12, fontFamily: 'inherit',
  },
  statsGrid: { display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 },
  statBox: (c) => ({
    padding: '10px 8px', background: 'var(--bg-secondary)',
    borderRadius: 'var(--radius-sm)', textAlign: 'center',
    borderLeft: `3px solid ${c}`,
  }),
  statValue: { fontSize: 20, fontWeight: 700, fontFamily: 'var(--font-mono)' },
  statLabel: {
    fontSize: 9, color: 'var(--text-muted)', marginTop: 2,
    textTransform: 'uppercase', letterSpacing: '0.05em',
  },
}

function Toggle({ label, active, onToggle }) {
  return (
    <div style={s.toggle} onClick={onToggle}>
      <span style={s.toggleLabel}>{label}</span>
      <div style={s.sw(active)}><div style={s.swDot(active)} /></div>
    </div>
  )
}

export default function Sidebar({
  activeLayers, onToggleLayer, filterLevel, onFilterLevel,
  selectedFeature, onCreateOrder, geojson,
}) {
  // Stats from polygon geojson
  const stats = geojson ? (() => {
    let l1 = 0, l2 = 0, l3 = 0
    geojson.features.forEach(f => {
      const lv = f.properties.vegetation_level || 1
      if (lv === 1) l1++; if (lv === 2) l2++; if (lv === 3) l3++
    })
    return { l1, l2, l3 }
  })() : null

  const eq = selectedFeature
    ? (EQUIPMENT_TYPES[selectedFeature.properties.name] || { short: selectedFeature.properties.name })
    : null
  const selLevel = selectedFeature ? (selectedFeature.properties.vegetation_level || 1) : null
  const selLvl = selLevel ? LEVELS[selLevel] : null

  return (
    <div style={s.sidebar}>
      <div style={s.header}>
        <div style={s.logo}>
          <div style={s.logoIcon}>🌿</div>
          <div>
            <div style={s.title}>MOTIVA</div>
            <div style={s.subtitle}>Gestão de Vegetação Rodoviária</div>
          </div>
        </div>
      </div>

      <div style={s.body}>
        {stats && (
          <div style={s.section}>
            <div style={s.sectionTitle}>Resumo — Rodoanel Oeste (SP-021)</div>
            <div style={s.statsGrid}>
              <div style={s.statBox(LEVELS[1].color)}>
                <div style={{ ...s.statValue, color: LEVELS[1].color }}>{stats.l1}</div>
                <div style={s.statLabel}>Nível 1</div>
              </div>
              <div style={s.statBox(LEVELS[2].color)}>
                <div style={{ ...s.statValue, color: LEVELS[2].color }}>{stats.l2}</div>
                <div style={s.statLabel}>Nível 2</div>
              </div>
              <div style={s.statBox(LEVELS[3].color)}>
                <div style={{ ...s.statValue, color: LEVELS[3].color }}>{stats.l3}</div>
                <div style={s.statLabel}>Nível 3</div>
              </div>
            </div>
          </div>
        )}

        <div style={s.section}>
          <div style={s.sectionTitle}>Camadas</div>
          <Toggle label="Polígonos de Vegetação" active={activeLayers.polygons}
            onToggle={() => onToggleLayer('polygons')} />
          <Toggle label="Marcos Quilométricos" active={activeLayers.marcoKm}
            onToggle={() => onToggleLayer('marcoKm')} />
        </div>

        <div style={s.section}>
          <div style={s.sectionTitle}>Filtrar por Nível</div>
          <div>
            <span style={s.chip(filterLevel === null, '#5e22f3')}
              onClick={() => onFilterLevel(null)}>Todos</span>
            {Object.entries(LEVELS).map(([k, v]) => (
              <span key={k} style={s.chip(filterLevel === Number(k), v.color)}
                onClick={() => onFilterLevel(filterLevel === Number(k) ? null : Number(k))}>
                <span style={s.dot(v.color)} /> {v.desc}
              </span>
            ))}
          </div>
        </div>

        {selectedFeature && (
          <div style={s.section}>
            <div style={s.sectionTitle}>Área Selecionada</div>
            <div style={s.featureCard}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                <span style={{
                  display: 'inline-block', padding: '3px 10px', borderRadius: 20,
                  fontSize: 11, fontWeight: 600, background: selLvl.bg, color: selLvl.color,
                }}>{selLvl.label} — {selLvl.desc}</span>
              </div>
              <div style={s.featureRow}>
                <span>Equipamento</span>
                <span style={{ color: 'var(--text-primary)', fontWeight: 500 }}>{eq.short}</span>
              </div>
              <div style={s.featureRow}>
                <span>Área</span>
                <span style={{ color: 'var(--text-primary)' }}>{formatArea(selectedFeature.properties.area_m2)}</span>
              </div>
              <div style={s.featureRow}>
                <span>Lat</span>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                  {selectedFeature.properties.centroid_lat.toFixed(6)}
                </span>
              </div>
              <div style={s.featureRow}>
                <span>Lon</span>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                  {selectedFeature.properties.centroid_lon.toFixed(6)}
                </span>
              </div>
              <button style={s.btn} onClick={onCreateOrder}
                onMouseOver={e => e.target.style.background = '#4c1ad0'}
                onMouseOut={e => e.target.style.background = '#5e22f3'}>
                Gerar Ordem de Serviço
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
