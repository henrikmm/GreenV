# Motiva mobile capture

This Flutter client records a foreground route as consecutive ten-second MP4 segments. Each
segment carries GNSS location, altitude, accuracy, speed, course, cumulative distance, gravity,
linear acceleration, angular velocity and a segment-relative orientation. The client writes the
video, telemetry and SHA-256 digests to its application documents before attempting the network.

The upload queue is restart-safe. A capture remains local until the Video API has accepted its
bytes, and is dropped as soon as it has - what the worker later makes of a segment is never waited
for, because the phone cannot act on the answer and the wait keeps a finished capture on screen. Moving the app to the
background closes the current segment and stops recording; capture never continues invisibly.

The presentation is isolated from Verge Studio's React application. It follows the supplied
Motiva flow with login and password recovery, a GreenV operations dashboard, recording/upload,
network summary and full-route map screens. The upload screen starts the native camera path; the
dashboard and map data shown today are local presentation fixtures until their API contracts are
defined.

## Current scope

Implemented:

- Android and iOS camera capture in consecutive ten-second MP4 files.
- GNSS position, altitude, accuracy, speed, course and cumulative route distance.
- Gravity, linear acceleration, angular velocity and segment-relative orientation.
- A restart-safe on-device queue with SHA-256 checksums and serialized retry.
- The versioned `/v2/capture-sessions` API flow, up to and including segment delivery.
- Bearer-token authentication against a deployed API, compiled in with `GREENV_API_TOKEN`.
- A browser capture build that records from a workstation webcam and uploads to a deployed API.
- Motiva splash/authentication presentation, operations home, upload/capture and map views.

Not implemented yet:

- Real authentication or password recovery; those screens currently navigate local presentation
  state only.
- Dashboard, recent-upload and map APIs; the values on those screens are presentation fixtures.
- Background recording. Leaving the foreground intentionally closes the current segment and stops.
- Hardware timestamps for each camera frame or absolute device attitude.

## Code map

| Path | Responsibility |
|---|---|
| `lib/src/ui/capture_app.dart` | Motiva screens, navigation and visible capture states |
| `lib/src/capture/capture_coordinator.dart` | Session lifecycle and ten-second rotation |
| `lib/src/capture/camera_segment_recorder.dart` | Native camera initialization/start/stop |
| `lib/src/capture/phone_telemetry_collector.dart` | GNSS and inertial sampling |
| `lib/src/storage/persistent_capture_queue.dart` | Durable local files and queue index |
| `lib/src/upload/queue_uploader.dart` | Serialized, idempotent upload; drops what the API accepted |
| `lib/src/api/http_capture_backend.dart` | `/v2/capture-sessions` HTTP adapter, Bearer token included |
| `lib/src/bootstrap/capture_configuration.dart` | The three `--dart-define` settings, in one place |
| `lib/src/storage/browser_capture_queue.dart` | In-memory queue for the browser capture build |
| `lib/src/capture/browser_camera_recorder.dart` | `getUserMedia`/`MediaRecorder` recorder for the browser build |
| `lib/src/bootstrap/bootstrap_io.dart` | Real phone dependencies and runtime configuration |
| `lib/src/bootstrap/bootstrap_web.dart` | Browser capture build, and the UI-only design preview |

## Requirements

- Flutter 3.47.1 or a compatible stable SDK with Dart 3.13.1 or newer.
- Android Studio plus an Android SDK for Android builds.
- Xcode on macOS for iOS builds.
- A camera-capable phone for real video and sensor verification.
- The repository Compose stack for end-to-end uploads.

Confirm the toolchain before the first run:

```bash
flutter doctor -v
flutter --version
```

## Run

Start the local stack from the repository root:

```bash
docker compose up --build
```

Then, from this directory:

```bash
flutter pub get
flutter run
```

The first press on **Iniciar coleta** asks for camera and location permission. Video still
records if GNSS is unavailable, but affected frame rows will carry unavailable location evidence.
Stop the Flutter process with `q` or `Ctrl+C`; stop the backend with `docker compose down` from the
repository root.

### Stop and reset

Press the in-app stop action before ending a test so the segment in progress is closed, persisted
and scheduled for upload. Backgrounding the app performs the same safe close. Terminating a debug
process is not a substitute for that user-visible stop action.

To discard the Android app's complete local queue, device ID, permissions and presentation state:

```bash
adb shell pm clear br.com.greenv.greenv_capture
```

For an iOS simulator, uninstalling resets the same application container:

```bash
xcrun simctl uninstall booted br.com.greenv.greenvCapture
```

Both operations are destructive to captures the API has not accepted yet. To reset the
backend separately, use `docker compose down -v` from the repository root; that deletes its local
database, queue and capture volumes.

### Android emulator

No extra option is required. The compiled default is `http://10.0.2.2:8080`, Android's alias for
the development host:

```bash
flutter run -d emulator
```

### Android phone over USB

USB port reversal keeps the Compose API bound to host loopback:

```bash
adb reverse tcp:8080 tcp:8080
flutter run --dart-define=GREENV_API_URL=http://127.0.0.1:8080
```

Remove the reversal after testing with `adb reverse --remove tcp:8080`.

### iOS simulator or phone

Run from macOS. The simulator can use host loopback; a phone needs a deliberately reachable HTTPS
or development-network endpoint:

```bash
flutter run --dart-define=GREENV_API_URL=http://127.0.0.1:8080
```

Against the deployed API, which is the only way to exercise the cloud pipeline from a phone:

```bash
flutter run -d <device-id> --dart-define=GREENV_API_URL=https://greenvapi.matomomitsu.com
```

Two things a free Apple ID adds, and neither is a build error:

- **The phone refuses to launch the app until the developer is trusted.** Settings → General →
  VPN & Device Management → the Apple ID → Trust. The check reaches `ppq.apple.com`, so it needs
  the phone online, and it fails on a phone that has just been unlocked from a locked state until
  it is restarted.
- **A Personal Team profile expires after seven days.** The app stops launching and must be run
  from Xcode again. That is the signing tier, not the app.

### Browser presentation preview

With `GREENV_WEB_CAPTURE=false`, web uses deterministic fake camera/sensor/backend adapters and a
fake session. It is for visual review, not capture - and it is the only build where `?screen=` opens
a screen without signing in:

```bash
flutter run -d chrome --dart-define=GREENV_WEB_CAPTURE=false
```

Preview states can be selected with `?screen=splash`, `login`, `forgotEmail`, `forgotCode`, `home`,
`upload`, `network` or `map`. `?offline=1`, `?phase=preparing` and `?phase=error` exercise capture
states. The repository review command builds web first, then runs from the repository root:

```bash
node scripts/capture-mobile-review.mjs
```

### Browser capture against a deployed API

The browser build uses the real webcam, the browser's own geolocation and the same
`HttpCaptureBackend` the phone uses - that is the default. It is how a workstation with no Android
device exercises a deployed pipeline:

```bash
GREENV_API_URL=https://greenvapi.example ./scripts/run-cloud-web.sh -d chrome
```

Sign in on the app's own login screen. `--dart-define=GREENV_WEB_CAPTURE=false` is what swaps the
camera, the backend and the session for fakes, when the point is to look at layout.

Four things differ from the phone, on purpose:

- **The queue is in memory.** A browser page has no application-documents directory, so a reload
  discards any segment the API has not accepted yet. Everything else is real: the SHA-256 digests
  are computed over the actual bytes, and a segment is dropped only once the upload came back
  accepted.
- **The container is WebM, not MP4.** A browser's `MediaRecorder` encodes VP9/WebM; the segment
  upload declares that, and the API accepts `video/mp4` and `video/webm` alike because the worker
  probes the container. The stored object keeps its `source.mp4` name.
- **There are no inertial samples.** A workstation exposes no accelerometer or gyroscope, so
  `motions` is empty and only GNSS and video carry evidence.
- **The recorder is not `package:camera`.** `camera_web.availableCameras()` opens a stream for
  every video input device to read its facing mode, and it does that without per-device error
  handling. One device that enumerates but refuses to open — a virtual camera whose application is
  not running, which is the common case on a workstation — throws away the entire list, including
  the real webcams already collected, and the app reports
  `CameraException(cameraNotReadable, ...)` with a working webcam attached.
  `BrowserCameraRecorder` therefore drives `getUserMedia` and `MediaRecorder` directly and tries
  each device in turn, keeping the first that actually opens. Pass `?camera=<label substring>` to
  try a particular one first, for example `?camera=C920`.

The API must list the exact page origin in `GREENV_ALLOWED_ORIGINS`, or the browser blocks the
upload preflight before it is sent. That is why the script pins `--web-port 5173`. CORS decides
which pages may read a response; it never replaces the Bearer token.

The capture build reads three query parameters, all of them for testing:

| Parameter | Effect |
|---|---|
| `?camera=<label substring>` | Try that camera first, for example `?camera=C920` |
| `?autostart=1` | Start recording without a click, so a browser can be driven automatically |
| `?session=<uuid>` | Pin the session id, so the capture can be followed with `GET /v2/capture-sessions/<uuid>` |

### The API token

The deployed API rejects every route but `/actuator/health` and the sign-in routes without a
credential. Signing in supplies one, so no token needs compiling in:

```bash
flutter run --dart-define=GREENV_API_URL=https://greenvapi.example
```

`GREENV_API_TOKEN` still exists as a fallback for the pilot builds that carry one, and is used only
before anyone has signed in. It is compiled into the build, so a release artifact carrying it holds
a shared secret anyone who unpacks it can read - which is exactly why a person's own credential
replaced it.

`GREENV_API_URL` is a compile-time setting, not a runtime environment variable. A physical phone
on the development network must receive an address it can reach:

```bash
flutter run --dart-define=GREENV_API_URL=http://192.168.1.20:8080
```

The local Compose API binds to loopback by default. Bind or proxy it deliberately before testing
from a physical device; do not expose this unauthenticated pilot API to the public internet.

Android declares camera, fine/coarse location, network and wakelock permissions. iOS declares
camera and when-in-use location descriptions. The current workstation can compile Android and
web; iOS still requires Xcode on macOS.

## Capture and upload flow

```text
press record
  -> create local session UUID and persistent device UUID
  -> acquire wakelock and start camera + sensor subscriptions
  -> every 10 s: stop MP4, close telemetry, copy both into the queue, hash, start next segment
  -> upload video and telemetry with the same segment idempotency key
  -> ask API to queue extraction
  -> delete the local segment as soon as the API accepted it
press stop/background
  -> close and queue the segment in progress
  -> close the session and release wakelock
```

Retries are serialized: a second sync request joins the active sync instead of uploading the same
files in parallel. The session UUID is assigned before network access, so restarting offline keeps
the API identity stable. The client syncs on startup and every five seconds while the UI is alive.

## Local persisted data

The app uses its platform application-documents directory:

```text
capture-queue/
  device-id
  queue-v1.json
  <session UUID>/
    00000000/
      source.mp4
      telemetry.json
    00000001/
      source.mp4
      telemetry.json
```

`queue-v1.json.writing` is the temporary atomic-write file. The camera plugin's temporary MP4 is
deleted only after the durable queue copy and both SHA-256 digests exist. A queued directory is
deleted as soon as the API has accepted the segment's bytes. Uninstalling the app
or clearing its data removes this queue.

## Telemetry document

Each `telemetry.json` is schema version 1 and contains a session/segment identity, UTC segment
anchor, monotonic clock anchor, GNSS samples and motion samples. Motion sampling requests the
plugin's game interval. Location requests `bestForNavigation`; the operating system still decides
the delivered frequency and accuracy.

The API and worker READMEs describe the HTTP headers and per-frame association rules. Units are
explicit in field names: metres, metres per second, degrees, radians per second and monotonic
nanoseconds.

## Verify

```bash
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build apk --debug
flutter build web
```

The tests cover ten-second rotation, queue persistence across restart, offline retry, deletion on
delivery, normalized relative orientation, auth/navigation rendering, and the
idle/recording Motiva states.

The latest verified debug artifact is produced at
`build/app/outputs/flutter-apk/app-debug.apk`. iOS cannot be compiled or signed on Windows.

## Troubleshooting

| Symptom | Check |
|---|---|
| API connection fails in Android emulator | Compose is running and `http://10.0.2.2:8080/actuator/health` is reachable from the emulator |
| USB phone cannot reach API | Run `adb reverse tcp:8080 tcp:8080` and compile with the loopback `GREENV_API_URL` above |
| Camera action reports a permission error | Grant camera permission in platform settings, then retry; the action remains available |
| GPS stays unavailable | Enable the device location service and precise/when-in-use permission; video capture is independent |
| Queue count does not fall | The uploads are failing: read `lastError` on the queued segment, then check the API. A segment is dropped the moment an upload is accepted |
| Android native build has stale cache errors after moving the project | Run `flutter clean`, `flutter pub get`, then rebuild |
| Web shows a route icon instead of camera | You are running `--dart-define=GREENV_WEB_CAPTURE=false`, the design preview. Drop it for the real camera |
| Every API call fails with 401 | The build was compiled without `GREENV_API_TOKEN`, or the token is stale |
| Browser upload fails with a CORS error | The API's `GREENV_ALLOWED_ORIGINS` does not list the page origin, port included |
| Browser reports `cameraNotReadable` | Another camera on the machine cannot be opened; name the one you want with `?camera=<label>` |

Do not manually delete queue files to resolve an upload error; doing so discards the only durable
copy. Inspect API/worker state first.

## Timing boundary

The camera plugin returns one finished file per segment, not hardware timestamps for individual
frames. The worker therefore probes every encoded presentation timestamp and maps it onto the
segment's monotonic clock anchor. GNSS older than two seconds and motion older than 100 ms are not
attached to a frame. The quaternion is gyroscope-integrated relative orientation from the start of
that segment, not absolute attitude, and it can drift.

## Recording frame rate

The camera's frame rate bounds how tightly a stretch of road can be reconstructed. A stretch is
measured from the frames recorded while crossing it, so at 30 fps a twenty-metre stretch holds 112
views at 20 km/h and only 22 at 100 km/h — below the smallest count Verge Studio has graded.

| recording | 60 km/h | 100 km/h | 120 km/h | graded up to |
|---|---:|---:|---:|---|
| 30 fps | 38 views | 22 | 18 | 34 km/h |
| 60 fps | 76 | 43 | 36 | 68 km/h |
| 120 fps | 112 | 86 | 71 | 135 km/h |

**Nothing configures this.** The recorder walks a ladder at startup and keeps the first format the
device accepts:

| | resolution | rate |
|---|---|---|
| 1 | 720p | 240 |
| 2 | 1080p | 240 |
| 3 | 720p | 120 |
| 4 | 1080p | 120 |
| 5 | 720p | 60 |
| 6 | 720p | platform default |

Highest rate first, and at equal rate the **lower** resolution first: the worker resizes every frame
to a 1024 px long edge before publishing and 720p is already 1280 px across, so 1080p would discard
72% of what it captured while costing the 64 MB upload budget. 1080p is on the ladder only because
some devices offer a high-rate format there and not at 720p. 4K is not on it at all — it would
discard 93% and, on most phones, cost the frame rate too.

The two platforms fail differently, which is why this is a ladder rather than one request:

- **iOS** searches the formats at the requested resolution and clamps to the closest rate it finds,
  so rung 1 nearly always succeeds and yields the fastest format 720p has on that device.
- **Android** asks CameraX for an exact `[fps, fps]` range and the bind throws when no format offers
  it, so a 30 fps phone falls through to the last rung. The range reaches video capture, not only
  the preview (`android_camera_camerax.dart:424`).

**Neither platform reports the rate it achieved** — `CameraValue` does not carry it — so the app
cannot log what it got. The worker measures it per segment and records it in the manifest as
`nativeFps`. That is the number to trust, and the way to check a device is to record one segment and
read it.

Setting the iOS Camera app to 120 fps changes nothing here: that setting belongs to Apple's app, and
this one opens its own capture session.

This ladder has not been run on a device that offers 120 fps.

### In the browser

The browser build asks for `frameRate: {ideal: 240, min: 24}` at 720p — `ideal` rather than `exact`,
because an unsatisfiable `exact` makes `getUserMedia` reject the device outright, and a 30 fps webcam
is still worth opening.

It is also the one platform that **reports what it granted**, through
`track.getSettings().frameRate`, so the recorder reads it back and exposes it as `capturedFrameRate`.
AVFoundation and CameraX accept a requested rate and never say what they settled on.

The recording bitrate scales with the rate, at roughly 90 kbit per 720p frame. MediaRecorder's
default is a fixed budget for the whole stream, so at 120 fps it would give each frame a quarter of
the bits it gives at 30 — and compression artefacts are exactly what breaks the feature matching this
footage exists for.

To find out what a camera can actually do, paste this into the browser console on any page:

```js
const s = await navigator.mediaDevices.getUserMedia({
  video: { width: {ideal:1280}, height: {ideal:720}, frameRate: {ideal:240, min:24} } });
const t = s.getVideoTracks()[0];
console.log('granted', t.getSettings(), 'max', t.getCapabilities?.().frameRate);
t.stop();
```

Most USB webcams top out at 30 fps at 720p whatever is asked, and many that advertise 60 offer it
only at a lower resolution. If `granted.frameRate` comes back 30, the camera is the limit, not this
code — and the worker's `nativeFps` will agree.
