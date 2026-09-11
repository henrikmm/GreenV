import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// O proxy não é conveniência, é o que torna o login possível em desenvolvimento.
//
// A API abre a sessão em cookies `__Host-` com `SameSite=Lax`, e Lax exige que a página e a API
// compartilhem o mesmo domínio registrável. Servindo a API sob o mesmo endereço do Vite, o
// navegador trata tudo como primeira parte e o cookie viaja. A alternativa seria `SameSite=None`,
// que é um afrouxamento permanente em produção para resolver um problema só de desenvolvimento.
//
// `changeOrigin` fica falso de propósito: a API monta as URLs que devolve a partir do host que
// recebeu, e reescrevê-lo faria cada resposta apontar para um endereço que o navegador não usou.
const apiTarget = process.env.VITE_API_TARGET || 'https://greenvapi.matomomitsu.com'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    open: true,
    proxy: {
      '/v2': { target: apiTarget, changeOrigin: false, secure: true },
      '/actuator': { target: apiTarget, changeOrigin: false, secure: true },
      '/.well-known': { target: apiTarget, changeOrigin: false, secure: true },
    },
  },
})
