# GreenV — how to work in this repository

GreenV measures roadside vegetation from video shot out of a moving vehicle, and turns it into a
ranked list of stretches that need mowing. A phone captures a route; workers sample frames,
georeference them, segment the vegetation and measure its height; a map shows an operations team
where to send a crew first.

This file is the working agreement for every stack in the repository. It is the whole agreement
except where a directory carries its own — today only `measurement/` does.

## Where things are

| Directory | What it holds | Stack |
|---|---|---|
| `apps/web/` | The map dashboard: stretches, levels, service orders | React 18, Vite, Leaflet, plain JSX |
| `apps/mobile/` | The capture client: records a route with telemetry, uploads offline-first | Flutter, Dart |
| `services/greenv-video-api/` | Capture sessions, jobs, storage keys — the control plane | Spring Boot, Java 21, Gradle |
| `services/greenv-frame-extractor/` | Worker 1: samples frames, attaches GNSS, cuts road stretches | Spring Boot, Java 21, ffmpeg |
| `services/greenv-measurement-worker/` | Worker 2: reconstructs, segments and measures a segment. Calls `measurement/` as a process — see [docs/AUTOMATIC-HEIGHT.md](docs/AUTOMATIC-HEIGHT.md) | Node 22 |
| `services/greenv-depth-runpod/` | The depth stage as a RunPod serverless job, wrapping `measurement/server/`. **Never deployed** | Python 3.12, Docker |
| `services/capture-smoke/` | One end-to-end check of the compose stack | Shell, Docker |
| `infrastructure/` | Scale-to-zero MVP cloud resources and R2 state bootstrap | Terraform |
| `measurement/` | Verge Studio: metric height from video. **A git subtree — read the rule below** | TypeScript, Python, its own agreement |
| `compose.yaml` | The local stack: PostgreSQL, RabbitMQ, the API, worker 1 | Docker Compose |

## Commands

Run each from its own directory, not from the repository root.

```
apps/web            npm ci && npm run build
apps/mobile         flutter test && flutter analyze
services/*          ./gradlew check
measurement worker  npm ci && npm test
depth handler       python test_handler.py
infrastructure      terraform fmt -check -recursive && terraform init -backend=false && terraform validate && terraform test
measurement         npm ci --prefix app && ./scripts/verify.sh
whole local stack   docker compose up --build
end-to-end smoke    docker compose --profile test up --build capture-smoke
```

`measurement/scripts/verify.sh` wants a Python environment with FastAPI, or its server check
silently skips and the green tick means less than it looks:

```
VERGE_PY=.venv/bin/python ./scripts/verify.sh
```

## `measurement/` is a subtree, and stays round-trippable

`measurement/` is Verge Studio, imported with `git subtree add` from
`github.com/henrikmm/verge-studio`. That repository is the copy that matters and it is still
developed on its own. Two rules keep both usable:

- **Read `measurement/AGENTS.md` before changing anything under it.** It has its own conventions,
  its own verification loop and its own record. This file does not override it inside that directory.
- **Nothing outside `measurement/` may import from inside it, and nothing inside may import out.**
  When a worker needs a measurement it calls a process and reads JSON. That boundary is what lets
  the subtree keep moving independently.

To send work back upstream: `git subtree push --prefix=measurement <remote> <branch>`. To pull it
forward: `git subtree pull`. Both work today — verified 25 Aug 2026 by splitting the prefix and
getting back commit `25f9d13` with an identical tree.

## Language

**English everywhere in the repository**: code, comments, identifiers, commit messages,
documentation, schema field names. One vocabulary, so a grep finds every use of a thing.

The exception is **text a user reads on screen**, which is Brazilian Portuguese, and the Brazilian
road terms the domain is actually about. Those stay in Portuguese even in code, because translating
them invents a second vocabulary for the same thing:

`rodovia` · `sentido` · `km` · `roçada` · `marco km` · `trecho` · `ordem de serviço`

Define a domain term the first time it appears. Never explain programming.

## The four fields, on every artifact

Every frame, mask, measurement and result carries `(rodovia, sentido, km, capturado_em)`.

Without them nothing joins to anything — not the map, not repeat passes, not the semantic search on
the roadmap. Adding them now costs nothing. Adding them later costs reprocessing every video ever
uploaded.

## Claims

A claim about the system is the one thing a reader cannot check by looking at the code, so:

- **A claim carries its evidence** — the file, the run id, the date, the command that produced it.
  "Moves the floor by 31.9 cm" can be argued with; "significant drift" cannot. If a number is a
  guess, say so and give the range.
- **Record only what you observed.** Code that was written but never run is described as untested,
  not as working. That distinction has cost this project real money twice.
- **Outcome first, mechanism second.** A reader who stops after the first sentence should still have
  the thing that mattered.

This governs claims, not every sentence. Most work here is ordinary engineering and needs no
measurement behind it.

## Paid work needs permission, every time

The GPU service bills for the whole lifetime of the machine, not for the seconds it computes —
several minutes of startup and an idle period afterwards included.

- **Ask before you spend, and say what it will cost.** Deploying, running inference or anything else
  that wakes the service needs the user to agree first, in that conversation.
- **Delete the service when you are done.** It keeps one machine permanently alive otherwise.
- **Plan the whole batch before deploying.** Four experiments against one warm machine cost roughly
  one startup; four sessions cost four.

Checking costs nothing and wakes nothing.

## Git

- Branch off `main` as `feat/<area>-<thing>`. Pull request, one review, green CI.
- **Conventional Commits**: `<type>(<scope>): <summary>`, imperative, lower case, no full stop, 72
  characters or fewer. Types: `feat` `fix` `docs` `refactor` `perf` `test` `build` `ci` `chore`
  `revert`. Scopes follow the top-level directories: `web` `mobile` `api` `worker` `measurement`
  `services` `contracts` `docs` `ci` `repo`.
- The body explains **why**, not what, wrapped at 72. Required when a change alters a measured
  number or a file the CI reads.
- **Never commit dependencies, build output, model weights, secrets, or media over 5 MB.** The root
  `.gitignore` covers the known cases; a new stack adds its own lines before its first commit. CI
  enforces both. The one exemption is `measurement/fixtures/`, where Verge Studio tracks a 12 MB
  roadside reconstruction on purpose so its tests have real geometry to run against.
- Work on `main` is not protected — the free plan gives no branch protection, so nothing but review
  stops a bad push.

## Finishing a unit of work

| What changed | How you check it |
|---|---|
| `apps/web` | `npm run build`, then load the page and look at it |
| `apps/mobile` | `flutter test && flutter analyze` |
| `services/*` | `./gradlew check` |
| `services/greenv-measurement-worker` | `npm test` |
| `services/greenv-depth-runpod` | `python test_handler.py`, plus the worker's `npm test` — they share one contract example |
| `infrastructure` | `terraform fmt -check -recursive`, `terraform validate`, then mocked `terraform test` |
| `measurement` | `./scripts/verify.sh`, plus whatever `measurement/AGENTS.md` requires |
| The compose stack | `docker compose --profile test up --build capture-smoke` |

Tick a box when the step ran and you read its output, not when the code was written.

## What is not here

Tasks live on the team's Trello board, not in this repository. `measurement/docs/TASK.md` is Verge
Studio's own backlog and is **not** the GreenV sprint — do not work from it without asking.

Data acquisition, dataset labelling and the vegetation classifier were part of an earlier shape of
this project and are not on this trunk. They remain on the branches `feat/frontend-inicio`,
`HM-ml-classification` and `HM-ml-classificationV2`, and in commit `b0cea2d`.
