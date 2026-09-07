import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/storage/memory_capture_queue.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';

import '../support/capture_fakes.dart';

void main() {
  test(
    'retains offline data and removes it only when the worker reports ready',
    () async {
      final queue = MemoryCaptureQueue();
      final startedAt = DateTime.utc(2026, 8, 23, 12);
      await queue.beginSession(
        QueuedSession(
          sessionId: 'session-1',
          deviceId: 'phone-1',
          startedAtUtc: startedAt,
        ),
      );
      await queue.enqueueCaptured(
        sessionId: 'session-1',
        segmentIndex: 0,
        capturedAtUtc: startedAt,
        durationMillis: 10000,
        sourceVideoPath: 'memory://segment-0.mp4',
        videoContentType: 'video/mp4',
        telemetry: const SegmentTelemetryDocument({'schemaVersion': 1}),
      );
      await queue.closeSession(
        'session-1',
        endedAtUtc: startedAt.add(const Duration(seconds: 10)),
        lastSegmentIndex: 0,
      );
      final backend = FakeBackend()..online = false;
      final uploader = QueueUploader(queue: queue, backend: backend);

      await uploader.syncOnce();
      expect(queue.backlog.value, 1);
      expect(
        (await queue.sessions()).single.segments.single.state,
        SegmentUploadState.pending,
      );

      backend
        ..online = true
        ..workerState = 'queued';
      await uploader.syncOnce();
      expect(queue.backlog.value, 1);
      expect(
        (await queue.sessions()).single.segments.single.state,
        SegmentUploadState.awaitingVerification,
      );
      expect(backend.uploads, 1);

      backend.workerState = 'ready';
      await uploader.syncOnce();
      expect(queue.backlog.value, 0);
      expect(
        backend.uploads,
        1,
        reason: 'verification polling does not upload the same bytes again',
      );
    },
  );
}
