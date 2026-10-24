import { createContext, useContext, useState, useCallback, useEffect } from 'react'

/**
 * Quem está logado, sem saber como isso foi decidido.
 *
 * O core não sabe autenticar. A demo compara uma senha contra uma lista em memória; a versão real
 * troca cookies com a API e nunca vê a senha depois do envio. O que os dois têm em comum é a
 * sessão que sai disso e as telas que a leem, então é isso que mora aqui.
 *
 * O provedor recebe as três operações como funções. Um `signIn` que devolve `{ ok, user, error }`,
 * um `signOut`, e um `restore` opcional que descobre uma sessão já aberta — na demo lendo o
 * armazenamento local, em produção perguntando à API, porque o cookie é invisível ao JavaScript.
 */
const AuthContext = createContext(null)

export function AuthProvider({ children, signIn, signOut, restore }) {
  const [user, setUser] = useState(null)
  // Nulo enquanto ninguém perguntou ainda. Sem isto a tela decide que não há sessão antes de a
  // resposta chegar e devolve o usuário ao login a cada recarga.
  const [restoring, setRestoring] = useState(Boolean(restore))

  useEffect(() => {
    if (!restore) return undefined
    let live = true
    Promise.resolve(restore())
      .then((session) => { if (live) setUser(session ?? null) })
      .catch(() => { if (live) setUser(null) })
      .finally(() => { if (live) setRestoring(false) })
    return () => { live = false }
  }, [restore])

  const login = useCallback(async (email, password) => {
    const result = await signIn(email, password)
    if (result?.ok) setUser(result.user)
    return result
  }, [signIn])

  /**
   * Entra por outro caminho que não e-mail e senha — em produção, o botão de demonstração. Recebe
   * uma função que devolve o mesmo `{ ok, user, error }` de `signIn`, e o core só guarda a sessão.
   */
  const loginWith = useCallback(async (attempt) => {
    const result = await attempt()
    if (result?.ok) setUser(result.user)
    return result
  }, [])

  const logout = useCallback(async () => {
    await signOut?.()
    setUser(null)
  }, [signOut])

  return (
    <AuthContext.Provider value={{ user, login, loginWith, logout, restoring }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth deve ser usado dentro de AuthProvider')
  return ctx
}
