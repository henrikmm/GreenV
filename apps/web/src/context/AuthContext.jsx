import { createContext, useContext, useState, useCallback } from 'react'
import { findUser } from '../data/mockUsers'

const AuthContext = createContext(null)
const STORAGE_KEY = 'motiva_auth'

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      return saved ? JSON.parse(saved) : null
    } catch {
      return null
    }
  })

  const login = useCallback((email, password) => {
    const found = findUser(email, password)
    if (!found) return { ok: false, error: 'E-mail ou senha inválidos.' }
    const session = { name: found.name, email: found.email, role: found.role, initials: found.initials }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    setUser(session)
    return { ok: true, user: session }
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY)
    setUser(null)
  }, [])

  return <AuthContext.Provider value={{ user, login, logout }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth deve ser usado dentro de AuthProvider')
  return ctx
}
