import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// O proxy não é conveniência, é o que torna o login possível em desenvolvimento.
//
// A API abre a sessão em cookies `__Host-` com `SameSite=Lax`, e Lax exige que a página e a API
// compartilhem o mesmo domínio registrável. Servindo a API sob o mesmo endereço do Vite, o
// navegador trata tudo como primeira parte e o cookie viaja. A alternativa seria `SameSite=None`,
// que é um afrouxamento permanente em produção para resolver um problema só de desenvolvimento.
//
// Match the upstream virtual host so Cloudflare routes the local proxy to the API.
const apiTarget = process.env.VITE_API_TARGET || 'https://greenvapi.matomomitsu.com'
const apiProxy = { target: apiTarget, changeOrigin: true, secure: true, cookieDomainRewrite: '' }

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: {
      '/v2': apiProxy,
      '/actuator': apiProxy,
      '/.well-known': apiProxy,
    },
  },
})
