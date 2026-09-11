import { findUser } from '../data/mockUsers'

/**
 * A autenticação da demo: uma lista em memória e o armazenamento local.
 *
 * A senha é comparada em texto puro contra dois usuários fixos. Isso só é aceitável porque nada
 * aqui é real — não há API, não há dado de captura, e a tela de login mostra as duas credenciais
 * em botões. A versão de produção troca este módulo por um que fala com a API.
 */
const STORAGE_KEY = 'motiva_auth'

export function signIn(email, password) {
  const found = findUser(email, password)
  if (!found) return { ok: false, error: 'E-mail ou senha inválidos.' }
  const user = { name: found.name, email: found.email, role: found.role, initials: found.initials }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(user))
  return { ok: true, user }
}

export function signOut() {
  localStorage.removeItem(STORAGE_KEY)
}

export function restore() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved ? JSON.parse(saved) : null
  } catch {
    return null
  }
}
