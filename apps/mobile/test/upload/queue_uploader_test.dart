import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/storage/memory_capture_queue.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';

import '../support/capture_fakes.dart';

Future<MemoryCaptureQueue> _queueWithOneClosedSegment() async {
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
  return queue;
}

void main() {
  test(
    'retains offline data and lets it go as soon as the API takes it',
    () async {
      final queue = await _queueWithOneClosedSegment();
      final backend = FakeBackend()..online = false;
      final uploader = QueueUploader(queue: queue, backend: backend);

      await uploader.syncOnce();
      expect(queue.backlog.value, 1);
      expect(
        (await queue.sessions()).single.segments.single.state,
        SegmentUploadState.pending,
      );

      backend.online = true;
      await uploader.syncOnce();
      expect(
        queue.backlog.value,
        0,
        reason: 'the upload was accepted, so nothing is still queued',
      );
      expect(backend.uploads, 1);
      expect(backend.completions, 1);
    },
  );

  test('forgets a finished session instead of re-announcing it', () async {
    final queue = await _queueWithOneClosedSegment();
    final backend = FakeBackend();
    final uploader = QueueUploader(queue: queue, backend: backend);

    await uploader.syncOnce();
    final announced = backend.sessions;
    expect(announced, 1);

    await uploader.syncOnce();
    await uploader.syncOnce();

    expect(await queue.sessions(), isEmpty);
    expect(
      backend.sessions,
      announced,
      reason: 'a delivered session is not opened again on every tick',
    );
    expect(backend.uploads, 1);
    expect(backend.completions, 1);
  });

  test('drops a segment an older build left awaiting the worker', () async {
    final queue = await _queueWithOneClosedSegment();
    final queued = (await queue.sessions()).single.segments.single;
    await queue.updateSegment(
      queued.copyWith(state: SegmentUploadState.awaitingVerification),
    );
    final backend = FakeBackend();
    final uploader = QueueUploader(queue: queue, backend: backend);

    await uploader.syncOnce();

    expect(queue.backlog.value, 0);
    expect(
      backend.uploads,
      0,
      reason: 'those bytes are already in the API; sending them again is waste',
    );
  });

  test('keeps a failed segment queued and the session open', () async {
    final queue = await _queueWithOneClosedSegment();
    final backend = FailingUploadBackend();
    final uploader = QueueUploader(queue: queue, backend: backend);

    await uploader.syncOnce();

    expect(queue.backlog.value, 1);
    expect(
      (await queue.sessions()).single.segments.single.state,
      SegmentUploadState.failed,
    );
    expect(
      backend.completions,
      0,
      reason: 'a session is not completed while a segment is still undelivered',
    );
  });
}
