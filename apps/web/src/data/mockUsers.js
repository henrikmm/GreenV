// Usuários de demonstração — login mockado, sem backend de autenticação.
export const MOCK_USERS = [
  {
    email: 'operador@motiva.com.br',
    password: 'motiva2026',
    name: 'Rafael Nogueira',
    role: 'Operador de Campo',
    initials: 'RN',
  },
  {
    email: 'gestor@motiva.com.br',
    password: 'motiva2026',
    name: 'Camila Duarte',
    role: 'Gestora de Manutenção',
    initials: 'CD',
  },
]

export function findUser(email, password) {
  return MOCK_USERS.find(
    u => u.email.toLowerCase() === email.trim().toLowerCase() && u.password === password
  ) || null
}
