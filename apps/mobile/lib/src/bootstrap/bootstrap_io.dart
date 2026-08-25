import 'dart:io';

import 'package:greenv_capture/src/api/http_capture_backend.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/camera_segment_recorder.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_runtime.dart';
import 'package:greenv_capture/src/capture/phone_telemetry_collector.dart';
import 'package:greenv_capture/src/storage/persistent_capture_queue.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

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
  final deviceId = await _deviceId(deviceFile);
  const configuredApi = String.fromEnvironment(
    'GREENV_API_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );
  final monotonicClock = MonotonicClock();
  final recorder = CameraSegmentRecorder();
  final telemetry = PhoneTelemetryCollector(monotonicClock.nowNanos);
  final uploader = QueueUploader(
    queue: queue,
    backend: HttpCaptureBackend(Uri.parse(configuredApi)),
  );
  final coordinator = CaptureCoordinator(
    deviceId: deviceId,
    recorder: recorder,
    telemetry: telemetry,
    queue: queue,
    uploader: uploader,
    foregroundLease: WakelockForegroundLease(),
    scheduler: TimerSegmentScheduler(),
    monotonicNanos: monotonicClock.nowNanos,
  );
  uploader.syncSoon();
  return AppDependencies(capture: coordinator);
}

Future<String> _deviceId(File file) async {
  if (await file.exists()) return (await file.readAsString()).trim();
  await file.parent.create(recursive: true);
  final id = 'greenv-${const Uuid().v4()}';
  await file.writeAsString(id, flush: true);
  return id;
}
