# Motiva mobile capture

This Flutter client records a foreground route as consecutive ten-second MP4 segments. Each
segment carries GNSS location, altitude, accuracy, speed, course, cumulative distance, gravity,
linear acceleration, angular velocity and a segment-relative orientation. The client writes the
video, telemetry and SHA-256 digests to its application documents before attempting the network.

The upload queue is restart-safe. A failed or accepted upload remains local until the Video API
reports that the worker has published and re-read the verified manifest. Moving the app to the
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
- The versioned `/v2/capture-sessions` API flow and worker-verification polling.
- Motiva splash/authentication presentation, operations home, upload/capture and map views.

Not implemented yet:

- Real user/device identity or password recovery; those screens currently navigate local
  presentation state only. Capture uploads use the shared MVP Bearer token described below.
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
| `lib/src/identifier/uuid_v7_identifier_adapter.dart` | RFC 9562 UUIDv7 generation behind the identifier port |
| `lib/src/storage/persistent_capture_queue.dart` | Durable local files and queue index |
| `lib/src/upload/queue_uploader.dart` | Serialized, idempotent upload and verification polling |
| `lib/src/api/http_capture_backend.dart` | `/v2/capture-sessions` HTTP adapter |
| `lib/src/bootstrap/bootstrap_io.dart` | Real phone dependencies and runtime configuration |
| `lib/src/bootstrap/bootstrap_web.dart` | Deterministic UI-only browser preview |

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
flutter run \
  --dart-define=GREENV_API_TOKEN=greenv-local-only-bearer-token-000000000000
```

The first press on **Iniciar gravação** asks for camera and location permission. Video still
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

Both operations are destructive to captures that have not reached worker `ready`. To reset the
backend separately, use `docker compose down -v` from the repository root; that deletes its local
database, queue and capture volumes.

### Android emulator

No API URL override is required. The compiled default is `http://10.0.2.2:8080`, Android's alias
for the development host; pass the Compose development token:

```bash
flutter run -d emulator \
  --dart-define=GREENV_API_TOKEN=greenv-local-only-bearer-token-000000000000
```

### Android phone over USB

USB port reversal keeps the Compose API bound to host loopback:

```bash
adb reverse tcp:8080 tcp:8080
flutter run \
  --dart-define=GREENV_API_URL=http://127.0.0.1:8080 \
  --dart-define=GREENV_API_TOKEN=greenv-local-only-bearer-token-000000000000
```

Remove the reversal after testing with `adb reverse --remove tcp:8080`.

### iOS simulator or phone

Run from macOS. The simulator can use host loopback; a phone needs a deliberately reachable HTTPS
or development-network endpoint:

```bash
flutter run \
  --dart-define=GREENV_API_URL=http://127.0.0.1:8080 \
  --dart-define=GREENV_API_TOKEN=greenv-local-only-bearer-token-000000000000
```

### Browser presentation preview

Web uses deterministic fake camera/sensor/backend adapters. It is for visual review, not capture:

```bash
flutter run -d chrome
```

Preview states can be selected with `?screen=splash`, `login`, `forgotEmail`, `forgotCode`, `home`,
`upload`, `network` or `map`. `?offline=1`, `?phase=preparing` and `?phase=error` exercise capture
states. The repository review command builds web first, then runs from the repository root:

```bash
node scripts/capture-mobile-review.mjs
```

`GREENV_API_URL` and `GREENV_API_TOKEN` are compile-time settings, not runtime environment
variables. A physical phone on the development network must receive an address it can reach:

```bash
flutter run \
  --dart-define=GREENV_API_URL=http://192.168.1.20:8080 \
  --dart-define=GREENV_API_TOKEN=greenv-local-only-bearer-token-000000000000
```

The local Compose API binds to loopback by default. Bind or proxy it deliberately before testing
from a physical device.

### Deployed MVP API

Terraform generates the deployed API token and stores it as an Azure Container Apps secret. Read
the sensitive output into the shell without printing it, then provide it to the debug build:

```bash
cd ../../infrastructure
export GREENV_API_TOKEN="$(terraform output -raw api_bearer_token)"
export GREENV_API_URL="$(terraform output -raw api_public_url)"
cd ../apps/mobile
flutter run \
  --dart-define=GREENV_API_URL="$GREENV_API_URL" \
  --dart-define=GREENV_API_TOKEN="$GREENV_API_TOKEN"
unset GREENV_API_TOKEN
```

`HttpCaptureBackend` adds the token to session creation, video/telemetry uploads, completion and
polling. It refuses to start with a token shorter than 32 characters. A value compiled with
`--dart-define` can still be extracted from an APK; this is acceptable only for the closed MVP
pilot. Production must exchange the login for a short-lived per-user/device token instead of
shipping a long-lived shared secret.

Android declares camera, fine/coarse location, network and wakelock permissions. iOS declares
camera and when-in-use location descriptions. The current workstation can compile Android and
web; iOS still requires Xcode on macOS.

## Capture and upload flow

```text
press record
  -> create local session UUIDv7 and persistent device UUIDv7
  -> acquire wakelock and start camera + sensor subscriptions
  -> every 10 s: stop MP4, close telemetry, copy both into the queue, hash, start next segment
  -> upload video and telemetry with the same segment idempotency key
  -> ask API to queue extraction
  -> poll segment state
  -> delete local segment only when worker state is ready
press stop/background
  -> close and queue the segment in progress
  -> close the session and release wakelock
```

Retries are serialized: a second sync request joins the active sync instead of uploading the same
files in parallel. The session UUIDv7 is assigned before network access, so restarting offline
keeps the API identity stable. Its embedded time describes capture creation using the phone clock,
not eventual server insertion. A device ID already persisted by an older app version is retained,
and the API accepts older queued UUIDv4 sessions during the rollout. The client syncs on startup
and every five seconds while the UI is alive.

## Local persisted data

The app uses its platform application-documents directory:

```text
capture-queue/
  device-id
  queue-v1.json
  <session UUIDv7>/
    00000000/
      source.mp4
      telemetry.json
    00000001/
      source.mp4
      telemetry.json
```

`queue-v1.json.writing` is the temporary atomic-write file. The camera plugin's temporary MP4 is
deleted only after the durable queue copy and both SHA-256 digests exist. A queued directory is
deleted only after the API reports the worker-generated manifest as `ready`. Uninstalling the app
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

The tests cover UUIDv7 version and same-millisecond ordering, ten-second rotation, queue
persistence across restart, offline retry, Bearer headers on JSON and binary requests, rejection
of missing token configuration, deletion only after worker `ready`, normalized relative
orientation, auth/navigation rendering, and the idle/recording Motiva states.

The latest verified debug artifact is produced at
`build/app/outputs/flutter-apk/app-debug.apk`. iOS cannot be compiled or signed on Windows.

Verification observed on 30 Aug 2026: `flutter analyze` reported no issues and `flutter test`
completed all 15 tests successfully. APK and web builds were not rerun for this authentication
change.

## Troubleshooting

| Symptom | Check |
|---|---|
| API connection fails in Android emulator | Compose is running and `http://10.0.2.2:8080/actuator/health` is reachable from the emulator |
| API returns `401` | Rebuild with the same `GREENV_API_TOKEN` configured in Compose or exposed by Terraform |
| USB phone cannot reach API | Run `adb reverse tcp:8080 tcp:8080` and compile with the loopback `GREENV_API_URL` above |
| Camera action reports a permission error | Grant camera permission in platform settings, then retry; the action remains available |
| GPS stays unavailable | Enable the device location service and precise/when-in-use permission; video capture is independent |
| Queue count does not fall | Inspect API segment state and worker logs; files intentionally remain through `queued`, `validating` and `failed` |
| Android native build has stale cache errors after moving the project | Run `flutter clean`, `flutter pub get`, then rebuild |
| Web shows a route icon instead of camera | Expected: web bootstrap is a design preview and never opens a real camera |

Do not manually delete queue files to resolve an upload error; doing so discards the only durable
copy. Inspect API/worker state first.

## Timing boundary

The camera plugin returns one finished file per segment, not hardware timestamps for individual
frames. The worker therefore probes every encoded presentation timestamp and maps it onto the
segment's monotonic clock anchor. GNSS older than two seconds and motion older than 100 ms are not
attached to a frame. The quaternion is gyroscope-integrated relative orientation from the start of
that segment, not absolute attitude, and it can drift.
