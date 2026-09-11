// Metadados de exibição de OS — status e prioridade — compartilhados entre Ordens e Equipes.
export const STATUS_MAP = {
  pendente: { label: 'Pendente', color: '#ca8a04' },
  em_andamento: { label: 'Em Andamento', color: '#3b82f6' },
  concluida: { label: 'Concluída', color: '#16a34a' },
  cancelada: { label: 'Cancelada', color: '#9d9db0' },
}

export const PRIORITY_MAP = {
  baixa: { label: 'Baixa', color: '#16a34a' },
  media: { label: 'Média', color: '#ca8a04' },
  alta: { label: 'Alta', color: '#dc2626' },
  urgente: { label: 'Urgente', color: '#dc2626' },
}
