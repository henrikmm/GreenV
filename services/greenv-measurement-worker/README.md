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

**There is no GPU in the compose stack, and the fixture will not stand in for one.** The default
`GREENV_INFER_BASE_URL` points at Verge Studio's Vite fixture, but its privileged routes - every
`POST` is one - require an `Origin` header naming loopback on 5173 and a nonce the dev server
injects only into the HTML it serves. It answers browsers and refuses processes. Observed from the
compose stack on 8 Sep 2026:

```
POST http://<host>:5173/api/infer failed with 403:
{"detail":"local API requires a loopback origin on port 5173"}
```

Everything before that point does run: the segment is consumed, its sampled frames are downloaded,
and the failure is recorded as `depth_inference_failed`. Producing a packet needs a real depth
service. Were one reachable, its reconstruction would still be of an unrelated scene unless it is
the deployment's own, which is why a mock packet is refused unless
`GREENV_MEASUREMENT_ALLOW_MOCK=true` and marked `mock: true` when it gets through.

**Waking the real service costs money for its whole lifetime**, not for the seconds it computes,
and needs the user's agreement every time. See the root `AGENTS.md`.

## Two depth dialects

`GREENV_INFER_ADAPTER` chooses how the depth stage is reached. The default `http` is unchanged: the
FastAPI service in `measurement/server/`, frames uploaded as multipart, one request one answer.

`runpod` addresses a RunPod serverless endpoint, which is a job queue rather than a request:
`POST <endpoint>/run` returns an id and the answer is collected from `GET <endpoint>/status/<id>`.
Its JSON body has a payload ceiling far below a hundred JPEGs, so **frames travel by object key**
and the handler reads them from the bucket itself - which is why this adapter requires
`GREENV_OBJECT_STORAGE_ADAPTER=s3` and refuses to start on a filesystem the handler cannot see.

```
GREENV_INFER_ADAPTER=runpod
GREENV_RUNPOD_ENDPOINT=https://api.runpod.ai/v2/<endpoint-id>
GREENV_RUNPOD_API_KEY=<key>
GREENV_RUNPOD_POLL_MS=5000
```

The handler this expects is [`services/greenv-depth-runpod`](../greenv-depth-runpod/README.md).
Its contract is one job in, one manifest out:

```
input   { frames: [{ name, key }], storage: { bucket, endpoint, region },
          params: { fps, process_res, max_frames, source_duration_s } }
output  { run_id, frames: { count }, artifacts: [{ kind: "glb"|"npz", url, size_bytes }] }
```

`url` must be absolute - a signed bucket link - because a serverless handler has no origin of its
own to serve files from; the adapter rejects a relative one rather than resolving it against
RunPod's API host. Both sides of that envelope are pinned to one file,
`services/greenv-depth-runpod/contract/depth-job-v1.example.json`, which
`test/runpod-contract.test.mjs` and the handler's own tests each read, so the client and the
handler cannot drift apart without a test failing.

**Nothing here has been run against RunPod.** The adapter is exercised by
`test/infer-runpod.test.mjs` against a double that answers the way the API documents; no endpoint
has been deployed, no image has been built and no GPU has been woken.
