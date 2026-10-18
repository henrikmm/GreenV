# Live object measurement for the FIAP Next demo — technical reference

**Status, 17 Oct 2026.** A phone app records an object, sends the video over Wi-Fi to a gateway
on a Mac, and Verge Studio on that Mac extracts frames, runs DA3-Nested on a Cloud Run GPU and
shows the point cloud — automatically, with every stage timed and reported back to the phone.
Exercised end to end with a Galaxy S25 Edge against an RTX PRO 6000 and an L4; nothing is
deployed. The gateway runs only on the Mac; a cloud gateway does not exist yet.

This is the canonical record of what was built on branch `feat/mobile-next-demo-capture`, where
each part lives, the contracts between them, and what was measured. It describes; it does not
prescribe how a deployment should be done.

Event: FIAP Next, Saturday 24 Oct 2026. A visitor films an object, guesses its height, the
system reconstructs and measures it, a tape settles it.

## 1. Architecture

```
Android phone (Flutter, lib/main_demo.dart)
   │  HTTP, LAN — phone ↔ gateway contract v1 (§3)
   ▼
Phone intake  (measurement/app/vite-plugins/phone-intake.mjs, :8790, 0.0.0.0, opt-in)
   │  writes  $TMPDIR/verge-phone-inbox/<capture-id>/{meta.json, video.*, video.json, timeline.json}
   ▼
Verge Studio dev server, loopback only  (local-api.mjs → /api/phone/*)
   │  read by the browser app every second  (src/lib/phone-auto.ts)
   ▼
Browser: load capture as clip → ffmpeg extraction on the Mac → POST /infer (Cloud Run, via the
dev-server proxy that signs with the local gcloud identity) → artifacts from GCS → point cloud
   │  each stage written back:  POST /api/phone/captures/<id>/phase
   ▼
Phone polls GET /v1/captures/<id> and shows the phases
```

The GPU service is the existing Verge Studio DA3 service (`measurement/server/`), deployed by
`measurement/scripts/deploy.sh` as Cloud Run service `verge-da3` in project `verge-lab`,
`us-central1`. The phone never holds a cloud credential.

## 2. Where everything is

### Phone app (`apps/mobile`)

The demo is a second entry point. The field app (`lib/main.dart`) and its tests are unchanged.

| File | What it does |
|---|---|
| `lib/main_demo.dart` | Entry point. Portrait lock, immersive mode, one screen |
| `lib/src/demo/demo_screen.dart` | The screen and its flow: open camera → record → announce → upload → poll phases. Full-screen preview with `BoxFit.cover` (the field app squeezes it into a 16:10 box at `lib/src/ui/capture_app.dart:989`, which distorted it). Guards: start/stop serialised; stops in the first 1.5 s ignored; zero-byte files refused before upload |
| `lib/src/demo/demo_camera.dart` | `CameraController` wrapper. Opens the first back camera, applies resolution, fps, stabilisation, torch, exposure offset; locks focus and exposure at record start; reports what the camera actually accepted (`applied`). Open/close serialised (Android's permission dialog pauses the app mid-open) |
| `lib/src/demo/demo_settings.dart` | Operator settings + JSON persistence in app documents. Defaults below |
| `lib/src/demo/gateway_client.dart` | Contract v1 client: `health`, `announce`, streamed `uploadVideo` with progress, `status` |
| `lib/src/demo/orbit_tracker.dart` | Gyroscope rate projected on low-passed gravity, integrated → degrees walked around the object; smoothed speed with a "too fast" flag at 0.8 rad/s |
| `lib/src/demo/demo_widgets.dart` | Record button with progress ring, orbit arc (90° mark), reticle, phase list (collapsible; collapsed shows the current phase and total), operator settings sheet (opened by a 2 s press on the title) |
| `lib/src/demo/verge_theme.dart` | Verge Studio palette from `measurement/app/src/theme.css` |
| `test/demo/demo_test.dart` | 13 tests: settings defaults/round-trip/clamping, orbit integration, client against `MockClient` |
| `tool/gateway_smoke.dart` | Runs the client against a live gateway without a phone |
| `android/app/src/{debug,profile}/AndroidManifest.xml` | `usesCleartextTraffic="true"` for debug and profile only; release refuses cleartext |

Operator settings and defaults:

| Setting | Default | Choices |
|---|---|---|
| Resolution | 1080p | 720p, 1080p, 4K (`ResolutionPreset.high/veryHigh/ultraHigh`) |
| Recording fps | 30 | 30, 60 |
| Lock focus + exposure at record start | on | |
| Video stabilisation | off | on → `level1` if the device offers it |
| Torch / exposure offset | off / 0 EV | offset clamped to the device range (S25 Edge: −2…+2) |
| Automatic stop | 15 s | 10, 15, 20, 30 |
| Sampling for the studio | 10 fps | 5, 6, 8, 10, 15 |
| Max frames for the studio | 112 | 64, 112, 160, 200, 256 |
| Gateway URL / pairing code | `--dart-define=VERGE_GATEWAY_URL` / `VERGE_GATEWAY_CODE` | editable in the sheet |

### Gateway and studio (`measurement/`, Verge Studio subtree)

| File | What it does |
|---|---|
| `app/vite-plugins/phone-intake.mjs` | The intake server (contract v1), the inbox (create, receive, phases, list, discard, prune at 3 days), `ffprobe` of every video, the studio routes (`studioPhoneRoute`), pairing code. Started by the Vite plugin only when `VERGE_PHONE_INTAKE` is set; one instance per process across Vite restarts |
| `app/vite-plugins/phone-intake.test.mjs` | 15 tests: refusals, full announce → upload → status, studio routes, housekeeping |
| `app/vite-plugins/local-api.mjs` | Mounts `/api/phone/*` (privileged: loopback origin + dev nonce, even for reads). `POST /api/cloud/deploy` accepts `{gpu}` from a two-item allowlist |
| `app/src/lib/phone-link.ts` | Browser client for `/api/phone/*`; `loadPhoneCapture` points Frame Source at the video and applies the phone's sampling clamped to the card; the *active* capture; forward-only reporting of run phases |
| `app/src/lib/phone-auto.ts` | The poll (1 s) and Auto (load → extract → run). Module-level so it survives any pane being hidden. `run` is never persisted |
| `app/src/panes/phone-captures.tsx` | Setup → Phone: gateway address and code, Auto switches, captures with recorded format, sampling, camera settings, Wi-Fi time, tape input, per-phase timeline |
| `app/src/panes/demo-panel.tsx` | Demo mode's replacement for Setup: five stages with measured times, live total, extracted frames as thumbnails |
| `app/src/panes/gpu-choice.tsx` | Run → GPU card selector; "Switch to …" redeploy when the deployed card differs |
| `app/src/lib/ui-mode.ts`, `app/src/app.tsx` | Third view mode, `demo` |
| `app/src/panes/depth-2d.tsx`, `pane-chrome.tsx`, `viewport-3d.tsx` | Demo trims (pane timings, object label, Segment, Above floor and Mask/Zoom unless in use); viewport draws a new cloud in over 2.5 s in Demo; the DA3/Ours cloud choice now shows in Standard (DA3 stays the default) |
| `app/src/lib/load-clip.ts` | `setFrameCap` writes the cap to Frame Source and DA3 Depth together; extraction reports `extracting`/`extracted` |
| `app/src/lib/run-inference.ts`, `run-phase.ts` | Report `inferred` (with GPU seconds) and `fetched`; `subscribeRunPhase` |
| `app/src/lib/cloud-control.ts`, `deploy-store.ts`, `app/vite-plugins/cloud.mjs` | `GPU_TYPES`, `DEFAULT_GPU` (RTX PRO 6000), `GPU_FRAME_CEILING`, `activeGpu`; status reads the deployed card from the service template |
| `scripts/deploy.sh` | `VERGE_GPU` = `nvidia-rtx-pro-6000` (default; 20 vCPU, 80 GiB) or `nvidia-l4` (8 vCPU, 32 GiB) |
| `server/Dockerfile` | PyTorch 2.8 / CUDA 12.8; DA3 installed `--no-deps`, no xformers (attention on PyTorch SDPA) |
| `scripts/regress-run.mjs` | Resends a saved run's frames to the deployed service, downloads the artifacts into a scratch runs root, replays the run's painted trials on old and new reconstructions; also used as a timer for frames without trials. `--no-gpu` rehearses with every delta 0 |
| `.claude/launch.json` (root and `measurement/`) | `verge-phone`: the studio with the intake on |

Frame ceilings the studio plans for: L4 144 (measured: 144 ran at 21.88 of 22.03 GiB; 160 ran out
of memory), RTX PRO 6000 256 (chosen, never run; see §6). The phone's request is clamped to the
deployed card, or to the selected card when nothing is deployed, and the clamp is shown.

## 3. Phone ↔ gateway contract, version 1

Plain HTTP. Every route except health needs `Authorization: Bearer <pairing code>`.

| Call | Body | Answer |
|---|---|---|
| `GET /v1/health` | — | `200 {service: "verge-phone-intake", contract: 1, authorised}` |
| `POST /v1/captures` | metadata JSON ≤ 64 KiB | `201 {id, uploadPath}` |
| `PUT /v1/captures/{id}/video` | raw bytes; `content-type` `video/mp4`, `video/quicktime` or `video/webm`; `content-length` required; ≤ 400 MB | `200 {id, bytes, sha256, transferMs}` · `409` already received · `411` · `413` · `415` |
| `GET /v1/captures/{id}` | — | `200 {id, state, phases: [{phase, at, tMs, detail?}], video, result}` |

Capture metadata (`schema: "verge-phone-capture/1"`): `capturedAt` (ISO-8601 UTC, record start),
`recordStoppedAt`, `durationMs`, `bytes`, `settings` (what was asked), `applied` (what the camera
accepted: lens, sensor orientation, preview size, stabilisation and the modes offered, exposure
range and value, torch, whether focus/exposure locked), `sampling` `{fps, maxFrames}`, `orbit`
`{degrees, peakDegreesPerSecond, tooFastEvents}`, `device`, `app`. The gateway adds `rodovia`,
`sentido`, `km` (null — a demo is on no road), `purpose: "demo"`, `capturado_em` (a copy of
`capturedAt`) and `receivedBy`, and later `truth` `{truthM, object, recordedAt}` when an operator
enters a tape.

Phases, with who writes them:

| Phase | Written by | `detail` |
|---|---|---|
| `created` | gateway, on announce | — |
| `uploading`, `received` | gateway | `bytesExpected`; `bytes`, `transferMs`, `megabytesPerSecond` |
| `loaded` | studio route, on load | — |
| `extracting`, `extracted` | studio (`load-clip.ts`) | `frames`, `ms` |
| `uploading-frames`, `waking`, `loading-model`, `inferring` | studio (run phase, forward only) | `bytes`; —; —; `gpu` |
| `inferred` | studio, when `/infer` returns | `gpu`, `frames`, `gpuSeconds`, `serverWallSeconds`, `mock` |
| `fetched` | studio, when the cloud is built | `ms` |
| `measured` | reserved | — |
| `failed` | studio | `error`, `failedIn` |

`tMs` is milliseconds since `created` on the gateway's clock. The phone's clock is never
subtracted from it. The phone stops polling at `fetched`, `measured` or `failed`.

Studio-side routes (loopback, nonce): `GET /api/phone/info`, `GET /api/phone/captures`,
`POST /api/phone/captures/{id}/load`, `POST …/phase {phase, detail?}`,
`POST …/truth {truthM, object?}` (metres, 0–20), `DELETE /api/phone/captures/{id}`.

## 4. Running it locally

1. Mac and phone on the same Wi-Fi (a guest network may isolate clients).
2. Studio with intake: launch configuration `verge-phone`, or from `measurement/`:
   `VERGE_PHONE_INTAKE=1 npm run dev --prefix app`. The log prints
   `[phone] intake listening: http://<lan-ip>:8790 · pairing code <code>`; the code persists in
   the inbox directory. macOS asks once to allow incoming connections for `node`.
3. Toolchain: `source ~/greenv-toolchain/env.sh` (Flutter 3.47.7, Android SDK 36, Temurin 21).
4. From `apps/mobile`, phone on USB with debugging enabled:
   `flutter run -t lib/main_demo.dart --dart-define=VERGE_GATEWAY_URL=http://<mac>:8790 --dart-define=VERGE_GATEWAY_CODE=<code>`
   or `flutter build apk --profile …` and `adb install -r build/app/outputs/flutter-apk/app-profile.apk`.
5. GPU: `VERGE_GPU=nvidia-rtx-pro-6000 ./scripts/deploy.sh` (or Deploy in the studio), then
   Setup → Cloud control → *Connect (signed locally)*, tick Phone → Auto (load, extract, run).
   `./scripts/teardown.sh` deletes the service.

Retention: phone keeps only its last video; inbox 3 days or Delete; extracted frames 24 h
(`$TMPDIR/verge-frames`); GCS `runs/transient/` 3 days by lifecycle; `~/verge-runs` only on Save.
Per capture: video 14–26 MB, frames ~5 MB, GCS 75–120 MB.

## 5. Results, 17 Oct 2026

### Image regression and GPU timing

The CUDA 12.8 image passed regression on both cards: the nine painted trials of
`20260811-161356-d387ec` moved at most 0.3 mm (L4) and 0.4 mm (RTX PRO 6000). Full table and
method: `measurement/docs/REGISTRY.md`, "The service image moved to CUDA 12.8".

| Card | Frames | State | `/infer` round trip | GPU | Model load |
|---|---:|---|---:|---:|---:|
| RTX PRO 6000 | 63 (phone) | warm | 9.7–9.8 s (one outlier 25.6 s) | 4.1–4.3 s | — |
| RTX PRO 6000 | 112 | warm | 16.2 s | 7.8 s | — |
| RTX PRO 6000 | 112 | first after deploy | 73.8 s | 11.2 s | 34.2 s |
| L4 | 63 (phone) | warm | 22.0 s | 14.6 s | — |
| L4 | 112 | warm | 41.6 s | 30.6 s | — |
| L4 | 112 | first after deploy | 127.3 s | 35.9 s | 41.7 s |

Artifacts per run: `verge-result.npz` 60 MB at 63 frames, 100–105 MB at 106–112; `scene.glb`
16.1 MB. Download over home broadband: 6–10 s for the npz, ~3 s for the glb. Redeploy L4 → PRO
6000 with the same image: 38 s. Cloud Build of the image: 14 min 21 s.

### End to end, phone to cloud, warm RTX PRO 6000, through the studio

Measured from the gateway's timelines (seconds since upload started):

| Capture | Video | Frames | Phone→Mac | Load | Extract | Frames→GPU | `/infer` (GPU) | Cloud built | Total |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `cap-20261017-203220-3knl` | 6.6 s, 13.8 MB, 121° | 58 | 0.4 | 0.9 | 0.9 | 5.8 ¹ | 12.5 (3.9) | 4.7 | **25.4** |
| `cap-20261017-203547-4kmz` | 6.2 s, 15.8 MB, 218° | 59 | 0.3 | 0.9 | 1.0 | 1.4 | 12.8 (3.9) | 8.3 | **25.0** |

¹ First request after connecting; the next one took 1.4 s. Likely the dev-server proxy minting
its identity token; not measured separately.

Of ~25 s, ~4 s is GPU. The server spends ~4 s more exporting and publishing (`wall_seconds` 7.7–
8.3), the browser waits ~4.5 s beyond the server's wall time (unexplained), and fetching the
artifacts and building the cloud takes 4.7–8.3 s.

### The phone captures

Galaxy S25 Edge, home Wi-Fi: requested 1080p/30 was granted exactly — stored 1920×1080 with
rotation −90°, 29.9 fps, H.264 High, SDR bt709, 17.8–18.7 Mbit/s. Transfer 0.3–0.7 s for
14–26 MB (34–53 MB/s). Extraction 0.9–1.6 s for 58–109 frames at 576×1024.

### Height

PC case, capture `cap-20261017-195250-asyv`, measured without a mask by
`inspect levels --expect` (the flat surface above the fitted floor): **0.488 m** on all four
reconstructions (L4 and PRO 6000, 63 and 106 frames, ±1 mm). The fitted floor is the desk the
case stands on, so this is desk to top. The operator's 0.45 m tape runs from the top to where
the feet begin, which excludes the feet; the two are different lengths, so this capture is not
graded against the 7% gate. No painted trial was saved for any phone capture.

### Cost of the session

≈ US$ 1.20: ~9 min L4, ~18 min RTX PRO 6000, plus the build. One image is kept in Artifact
Registry (`da3-service:src-72503b7885b12c8b`, 10.9 GB, ~US$ 1/month); the 2.5.1 image and the
LingBot `live-service` image were deleted.

## 6. Known limits

- **Never run:** an H100 (the CUDA 12.8 image supports Hopper, untested); more than 112 frames on
  either card; 256 frames on the RTX PRO 6000.
- **The studio's GPU selector button** has not been clicked against a live service; `deploy.sh`
  with `VERGE_GPU` was used for both cards, and the studio did read the deployed card back.
- **Demo mode** was exercised against the local mock only; its stages and thumbnails were seen
  with real timelines from earlier captures, not during a live GPU run.
- **The download is the largest share after the GPU.** A reduced preview artifact was designed
  and deliberately not built.
- **Security**: the intake is plain HTTP with a six-digit code, meant for a trusted LAN. Release
  builds of the app refuse cleartext.
- **`services/greenv-depth-runpod`** builds `FROM` the image `measurement/server/Dockerfile`
  produces, so it now inherits PyTorch 2.8 / CUDA 12.8; its frame-ceiling table still assumes an
  L4 (`GREENV_DEPTH_MAX_FRAMES` overrides it).
- DA3-Nested is licensed for non-commercial research use.

## 7. Decisions behind it

- The number comes from the recorded DA3-Nested pipeline, not the LingBot live branch
  (`feat/measurement-live-reconstruction`, kept as an experiment), which missed the 7% metric gate
  on every target (−9.9% to −26.4%, `measurement/MEASUREMENTS.md`).
- One capture is one clip is one reconstruction: metric scale does not transfer between clips.
- DA3's exported cloud is the default view; the cloud rebuilt from depth ("Ours") is an option.
- The RTX PRO 6000 is the default card: 2.7× faster on the GPU than the L4 on the same frames.
