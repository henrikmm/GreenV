# Application Development — Sprint 4

Group 27 · GreenV · FIAP 2CCPW · Professor Allan Roberto Molto

Submission branch: [`feat/sprint-4-application-development`](https://github.com/henrikmm/GreenV/tree/feat/sprint-4-application-development).

The React dashboard reads the existing GreenV API and turns measured vegetation heights into
explicit conditions, priorities and recommended maintenance actions. The changes run locally;
neither the capture pipeline nor the deployed dashboard needs to be redeployed.

## Run this branch

Use Node.js 22 or newer and a Chromium browser (Chrome or Edge):

```sh
git clone --branch feat/sprint-4-application-development --single-branch https://github.com/henrikmm/GreenV.git
cd GreenV
npm ci
npm run dev --workspace @greenv/web-prod
```

Open **http://localhost:5174/trechos** and sign in using an existing GreenV account.
Credentials are supplied separately; no account password or API token belongs in this repository.
The default upstream is `https://greenvapi.matomomitsu.com`.

The development server binds only to loopback. Its proxy uses the upstream hostname for Cloudflare
routing and narrows domain cookies to the local host, retaining Secure, HttpOnly and SameSite
attributes. Chrome accepts the Secure cookies on localhost; Safari over plain HTTP did not retain
the authenticated session during verification. Do not use `file://` or the mock dashboard for this
demonstration. The local UI uses the live API, so confirming an order would save it to the shared
backend; the demonstration stops at the order preview.

If Node reports an unknown certificate issuer on a machine with a managed system CA, use a Node
version supporting `--use-system-ca` and run:

```sh
NODE_OPTIONS=--use-system-ca npm run dev --workspace @greenv/web-prod
```

TLS verification stays enabled. No GPU inference, new capture or pipeline execution is required.

## Classification rules

The API supplies `measurementExtent95P95M` in metres. The dashboard displays centimetres and
applies the rules to the original, unrounded height. A *trecho* is a measured road stretch,
usually a window of about 25 metres.

| Height | Condition | Priority | Recommended action |
|---|---|---|---|
| Below 10 cm | Monitorado | Baixa | Manter monitoramento periódico. |
| 10 to 30 cm, inclusive | Atenção | Média | Programar vistoria e avaliar manutenção. |
| Above 30 cm | Intervenção prioritária | Alta | Priorizar vistoria para planejar a roçada. |
| Invalid/missing height, no valid API level, or fewer than 20 measured cells | Revisão necessária | A revisar | Revisar a evidência ou realizar nova coleta. |

The group's three bands are permitted by the assignment, which allows groups to define coherent,
documented rules; its four example labels are not mandatory. These are prototype decision rules,
not Motiva-approved operational thresholds or proof of automatic height accuracy.

The unknown category preserves the existing API evidence gate and never turns missing evidence
into a low-priority reading. An order preview suggests the highest priority of its selected
classified stretches. Any unclassified target requires an explicit human priority choice.

## Where each requirement is demonstrated

| Sprint 4 requirement | Implementation / demonstration |
|---|---|
| Object arrays and functions | `VEGETATION_RULES` and `classifyVegetation()` in [vegetationDecision.js](../apps/web-prod/src/domain/vegetationDecision.js) |
| Automatic height analysis and conditionals | Evidence checks plus numeric threshold comparisons in `classifyVegetation()` |
| Processing multiple points | `processVegetation()` maps every returned point to a decision without mutating the API records |
| Dynamic dashboard | `useMemo()` and JSX `map()` in [SegmentsPage.jsx](../apps/web-prod/src/pages/SegmentsPage.jsx); API-backed filters reload the rows |
| Locations, heights, conditions and actions | `/trechos`: Onde, Altura p95, Condição, Prioridade, Ação recomendada |
| Different visual conditions | Green, amber, red and grey condition badges and priorities |
| Highlighting priority locations | Existing global API sort “Mais altos primeiro” and the `h > 30 cm` filter; sorting is not limited to one client page |
| Automatic recommended actions | The rule's action is visible for every row |
| Documented rules | This guide and the expandable on-screen “Regras de classificação e prioridade” |
| One-minute pitch explaining solution and code | Record at most 60 seconds, showing the dashboard and the actual JavaScript source; publish the final narrated video to YouTube and provide its link |

The backend remains Java. React and Node.js are optional in the assignment; the new decision
functions are plain JavaScript and run in the React frontend. The API's original measurements and
classification fields remain intact.

## Verification

```sh
npm test --workspace @greenv/web-prod
npm run build --workspace @greenv/web-prod
npm run build --workspace @greenv/web-mock
```

The focused tests cover 10/30 cm boundaries, invalid readings, insufficient evidence, immutable
multi-point processing and suggested priorities for combined orders. Browser checks should cover
each band, the review category, filtering, opening a stretch and the order preview without
submitting it. Use the live API only for existing data; publishing a frontend branch needs no
backend redeployment.

The portal's rubric still requires a YouTube link, even when the code deliverable is this branch.
The narrated upload is completed by the group after recording its voice track. Screen recordings
remain external artifacts because this repository does not track media larger than 5 MB.
