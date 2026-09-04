import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, SESSION_ENDED } from '../api/client'

const AuthContext = createContext(null)

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside an AuthProvider')
  return context
}

/**
 * Holds who is signed in.
 *
 * The session cookies are HttpOnly, so JavaScript cannot read them and cannot tell whether it is
 * signed in by looking. Asking the server on boot is the only way, and it is also the honest one:
 * the server is the single source of truth about a session, and a copy kept in localStorage would
 * only ever be a stale one.
 */
export function AuthProvider({ children }) {
  const [status, setStatus] = useState('checking')
  const [user, setUser] = useState(null)

  useEffect(() => {
    let cancelled = false

    api.me()
      .then((account) => {
        if (cancelled) return
        setUser(account)
        setStatus('authenticated')
      })
      .catch(() => {
        if (cancelled) return
        setUser(null)
        setStatus('anonymous')
      })

    return () => { cancelled = true }
  }, [])

  // A refresh that fails anywhere in the app ends the session here too, so one expired tab does not
  // sit showing a map it can no longer load.
  useEffect(() => {
    const onSessionEnded = () => {
      setUser(null)
      setStatus('anonymous')
    }
    window.addEventListener(SESSION_ENDED, onSessionEnded)
    return () => window.removeEventListener(SESSION_ENDED, onSessionEnded)
  }, [])

  const login = useCallback(async (email, password) => {
    const account = await api.login(email, password)
    setUser(account)
    setStatus('authenticated')
    return account
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.logout()
    } finally {
      // Even if the call fails the local session is over; the cookies are cleared server-side and
      // leaving the UI signed in would be a lie.
      setUser(null)
      setStatus('anonymous')
    }
  }, [])

  const value = useMemo(
    () => ({ status, user, login, logout }),
    [status, user, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
