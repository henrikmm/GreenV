# GreenV Measurement Worker

Turns a captured segment into a Verge Studio grass assessment, automatically.

It consumes a finished segment, reconstructs it in three dimensions on the depth service,
segments the vegetation, measures its height in half-metre road-local cells, and publishes the
packet beside the frames it came from.

**The full contract, the limitations and the calling conventions are in
[`docs/AUTOMATIC-HEIGHT.md`](../../docs/AUTOMATIC-HEIGHT.md).** Read that before using a number
this service produces. The short version: every packet says `operationalStatus: "not-ready"`, and
that is accurate.

## It calls Verge Studio as a process, never as a library

```
spawn measurement/scripts/assess-grass.mjs --stdin --out <dir>   →   JSON back
```

Nothing here imports from inside `measurement/` and nothing there imports out. That boundary is
what lets the subtree keep being developed as its own repository and pushed back with
`git subtree push` — see the rule in the root [`AGENTS.md`](../../AGENTS.md). It also means the
image has to carry `measurement/`, which is why the `Dockerfile` builds from the repository root
rather than from this directory.

## Commands

```
npm ci && npm test        23 tests, including the whole chain on real recorded geometry
npm start                 run it
```

The end-to-end test replays a reconstruction Verge Studio computed on an L4 in August, so it
drives the real segmentation and the real geometry without waking a GPU. It skips itself when
that saved run is not on the machine — a fresh clone has none.

## Code map

| Path | Responsibility |
|---|---|
| `src/pipeline.mjs` | The use case: manifest → frames → depth → run → measure → publish |
| `src/infer.mjs` | The depth service client, including the range-fetch a 108 MB npz needs |
| `src/run-directory.mjs` | Laying out a run in the shape Verge Studio's inspector resolves |
| `src/frame-context.mjs` | Joining sampled frames to their telemetry; where `km` is deliberately not invented |
| `src/measure.mjs` | Spawning Verge Studio and reading its JSON |
| `src/queue/rabbit.mjs` | The automatic trigger |
| `src/http.mjs` | The manual trigger, for backfills and re-measures |
| `src/storage/` | Object storage: filesystem, or any S3-compatible endpoint |

## Two things that will bite

**There is no GPU in the compose stack.** Without `GREENV_INFER_BASE_URL` pointing at a real
service, the worker talks to Verge Studio's fixture-backed mock, which answers every request with
the same reconstruction of an unrelated scene. It refuses to publish that unless
`GREENV_MEASUREMENT_ALLOW_MOCK=true`, and marks any packet that gets through with `mock: true`.

**Waking the real service costs money for its whole lifetime**, not for the seconds it computes,
and needs the user's agreement every time. See the root `AGENTS.md`.
