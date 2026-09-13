import 'dart:io';

import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/api/http_capture_backend.dart';
import 'package:greenv_capture/src/api/http_operations_gateway.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/bootstrap/capture_configuration.dart';
import 'package:greenv_capture/src/capture/camera_segment_recorder.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/capture/capture_runtime.dart';
import 'package:greenv_capture/src/capture/phone_telemetry_collector.dart';
import 'package:greenv_capture/src/identifier/uuid_v7_identifier_adapter.dart';
import 'package:greenv_capture/src/storage/file_session_store.dart';
import 'package:greenv_capture/src/storage/persistent_capture_queue.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';
import 'package:path_provider/path_provider.dart';

Future<AppDependencies> createAppDependencies() async {
  final documents = await getApplicationDocumentsDirectory();
  final captureRoot = Directory(
    '${documents.path}${Platform.pathSeparator}capture-queue',
  );
  final queue = PersistentCaptureQueue(captureRoot);
  await queue.initialize();
  final deviceFile = File(
    '${captureRoot.path}${Platform.pathSeparator}device-id',
  );
  final identifierGenerator = UuidV7IdentifierAdapter();
  final deviceId = await _deviceId(deviceFile, identifierGenerator);
  final monotonicClock = MonotonicClock();

  // The refresh token lives beside the capture queue, in the app's private directory, so signing
  // in survives closing the app.
  final authenticator = SessionAuthenticator(
    tokenUri: CaptureConfiguration.tokenUri,
    store: FileSessionStore(
      File('${captureRoot.path}${Platform.pathSeparator}session'),
    ),
  );
  await authenticator.restore();

  final recorder = CameraSegmentRecorder();
  final telemetry = PhoneTelemetryCollector(monotonicClock.nowNanos);
  final uploader = QueueUploader(
    queue: queue,
    backend: HttpCaptureBackend(
      baseUri: CaptureConfiguration.apiUri,
      content: queue,
      bearerToken: CaptureConfiguration.apiToken,
      // Every upload carries the token of whoever signed in, so a capture is attributable to a
      // person rather than to a credential shared by the whole pilot.
      auth: authenticator,
    ),
  );
  final coordinator = CaptureCoordinator(
    deviceId: deviceId,
    recorder: recorder,
    telemetry: telemetry,
    queue: queue,
    uploader: uploader,
    foregroundLease: WakelockForegroundLease(),
    scheduler: TimerSegmentScheduler(),
    identifierGenerator: identifierGenerator,
    monotonicNanos: monotonicClock.nowNanos,
  );
  uploader.syncSoon();
  return AppDependencies(
    capture: coordinator,
    authenticator: authenticator,
    // The same token the uploads carry, so what the phone reads back is what this person may
    // see. Capture never waits on it: a phone with no signal still records and queues.
    operations: HttpOperationsGateway(
      baseUri: CaptureConfiguration.apiUri,
      auth: authenticator,
    ),
  );
}

Future<String> _deviceId(
  File file,
  IdentifierGenerator identifierGenerator,
) async {
  if (await file.exists()) return (await file.readAsString()).trim();
  await file.parent.create(recursive: true);
  final id = 'greenv-${identifierGenerator.next()}';
  await file.writeAsString(id, flush: true);
  return id;
}
