// Equipes de campo — fonte única usada pelo modal de OS, sidebar e dashboard.
export const TEAMS = [
  {
    id: 'eq1',
    name: 'Equipe 1 — Zona Norte',
    short: 'Equipe 1',
    region: 'KM 0 – 10',
    color: '#5e22f3',
    initials: 'E1',
    status: 'em_campo',
  },
  {
    id: 'eq2',
    name: 'Equipe 2 — Zona Sul',
    short: 'Equipe 2',
    region: 'KM 10 – 20',
    color: '#0ea5a0',
    initials: 'E2',
    status: 'disponivel',
  },
  {
    id: 'eq3',
    name: 'Equipe 3 — Manutenção Especial',
    short: 'Equipe 3',
    region: 'Pontos críticos',
    color: '#dc6d1a',
    initials: 'E3',
    status: 'em_campo',
  },
  {
    id: 'terceirizada',
    name: 'Terceirizada',
    short: 'Terceirizada',
    region: 'Sob demanda',
    color: '#6b6b80',
    initials: 'TC',
    status: 'disponivel',
  },
]

export const TEAM_STATUS = {
  em_campo: { label: 'Em campo', color: '#16a34a' },
  disponivel: { label: 'Disponível', color: '#3b82f6' },
  fora_servico: { label: 'Fora de serviço', color: '#9d9db0' },
}

export function getTeam(idOrName) {
  return TEAMS.find(t => t.id === idOrName || t.name === idOrName) || null
}
