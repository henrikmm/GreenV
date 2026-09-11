# GreenV — painel de produção

O dashboard que lê a API de verdade. Abre numa lista de sessões de captura, desenha o caminho de
cada uma onde quer que ela tenha sido feita, e deixa clicar num ponto da trilha para ver a foto
tirada ali.

O irmão dele é [`apps/web-mock`](../web-mock), a demo que roda sobre dados inventados. Os dois
compartilham [`apps/web-core`](../web-core), que tem os componentes e as regras de domínio e não
sabe de onde vem dado nenhum.

## Rodar em desenvolvimento

```
npm install            # na raiz do repositório, uma vez: são workspaces
npm run dev --workspace @greenv/web-prod
```

Abre em `http://localhost:5174` e fala com `https://greenvapi.matomomitsu.com` **através do proxy
do Vite**, não direto.

O proxy não é conveniência. A API abre a sessão em cookies `__Host-` com `SameSite=Lax`, e Lax só
deixa o cookie viajar quando a página e a API dividem o mesmo domínio registrável. Servindo a API
sob o mesmo endereço do Vite, o navegador trata tudo como primeira parte. A alternativa seria
`SameSite=None`, que é um afrouxamento permanente em produção para resolver um problema que só
existe em desenvolvimento.

Para apontar para outra API:

```
VITE_API_TARGET=http://127.0.0.1:8080 npm run dev --workspace @greenv/web-prod
```

## As três coisas que a API exige de um navegador

Todas falham como um 401 seco, sem dizer qual delas foi. Estão resolvidas em `src/api/client.js` e
são a razão de esse arquivo existir:

1. **`credentials: 'include'` em toda requisição.** Os cookies são invisíveis ao JavaScript.
2. **`X-CSRF-Token` em toda escrita**, com o valor do cookie `greenv_csrf`, o único legível. Sem
   ele o filtro simplesmente não autentica.
3. **Uma renovação compartilhada.** O token de acesso dura quinze minutos. Quatro telas carregando
   juntas fariam quatro renovações concorrentes, e como cada uma rotaciona o token de renovação,
   três invalidariam a sessão que acabaram de renovar.

## Publicar

O site é estático e vai para o **Cloudflare Pages**. O provider do Terraform nesta versão não tem
recurso de Pages, então o Terraform cuida do que ele consegue — a regra de firewall da zona e a
origem no CORS da API — e a publicação é o comando abaixo.

### Uma vez: o token

Um token da Cloudflare com **Account → Cloudflare Pages → Edit**. O token do Terraform não serve:
ele tem DNS, R2 e WAF, e não tem Pages.

```bash
export CLOUDFLARE_API_TOKEN='<token com Pages:Edit>'
export CLOUDFLARE_ACCOUNT_ID='5aab41dff9973754fb1350ff64207008'
```

### A cada publicação

```bash
npm run build --workspace @greenv/web-prod
npx wrangler pages deploy apps/web-prod/dist --project-name greenv-dashboard
```

O primeiro `deploy` cria o projeto. Depois disso, anexe o domínio uma vez, no painel do Pages em
**Custom domains**, ou por API. **O registro DNS é criado pelo Pages**, não pelo Terraform — por
isso `dashboard_hostname` no Terraform não cria registro nenhum, só libera o host no firewall e
no CORS.

### O que quebraria sem a regra de firewall

A zona `matomomitsu.com` tem um ruleset cuja última regra bloqueia todo caminho, com exceções
para `/api` e `/demo`. Enquanto um host está em DNS-only o tráfego não passa pela Cloudflare e a
regra não se aplica; no instante em que ele é proxiado, aplica. Foi assim que a API respondeu 403
em tudo em 11 de setembro de 2026.

O Terraform adota esse ruleset e insere uma liberação por host antes do bloqueio. Para o painel a
regra é por host e não por caminho, porque um SPA serve `/sessoes/<uuid>` do mesmo `index.html`
que a raiz e os nomes dos arquivos carregam um hash que muda a cada build.

### `_redirects`

`public/_redirects` manda todo caminho para `index.html` com status 200. Sem isso, qualquer link
aberto direto ou recarregado devolve 404 e só a raiz funciona.

## O que o painel mostra, e o que ele não afirma

Toda tela que mostra altura mostra também que nenhuma leitura automática foi conferida contra fita
métrica, e que os limiares de 10 e 30 cm ainda não foram aprovados pela Motiva. Não é enfeite: é a
diferença entre evidência para olhar e instrução para mandar uma equipe.

Três coisas que o formato dos dados impõe e que a tela respeita:

- **O nível é por segmento, não por célula.** Uma célula medida vive em metros relativos à estrada,
  com origem numa pose de câmera arbitrária, e a transformação para latitude e longitude não
  sobrevive à execução: o worker apaga o `.npz` e o `.glb`. O que é georreferenciado é a trilha da
  câmera.
- **A faixa é desenhada dos dois lados da trilha.** O pacote dobra os lados
  (`corridor.side: "unsigned-both-sides-folded"`) e nunca registrou de qual deles a célula veio.
- **A altura é `extent95`**, medida a partir do solo local de cada célula, que é o único estimador
  já comparado com fita. O `h95` do pacote é medido a partir do plano ajustado e lê mais alto.

## Estado

Construído e verificado com `npm run build`. Não há teste nem lint neste app, como também não há
no `web-mock`. As telas de ordens, equipes e tendências ainda não existem aqui: elas dependem de
tabelas e rotas que a API não tem.
