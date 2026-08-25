import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/storage/memory_capture_queue.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';

import '../support/capture_fakes.dart';

void main() {
  test(
    'rotates at ten seconds and continues recording the next segment',
    () async {
      final queue = MemoryCaptureQueue();
      final recorder = FakeRecorder();
      final telemetry = FakeTelemetry();
      final lease = FakeLease();
      final scheduler = FakeScheduler();
      final backend = FakeBackend()..online = false;
      final coordinator = CaptureCoordinator(
        deviceId: 'phone-1',
        recorder: recorder,
        telemetry: telemetry,
        queue: queue,
        uploader: QueueUploader(queue: queue, backend: backend),
        foregroundLease: lease,
        scheduler: scheduler,
        monotonicNanos: () => 123,
        utcNow: () => DateTime.utc(2026, 8, 23, 12),
        newSessionId: () => 'session-1',
      );
      addTearDown(coordinator.dispose);

      await coordinator.start();
      expect(coordinator.phase, CapturePhase.recording);
      expect(scheduler.duration, const Duration(seconds: 10));
      expect(recorder.starts, 1);

      await scheduler.fire();
      expect(recorder.stops, 1);
      expect(
        recorder.starts,
        2,
        reason: 'the next segment starts after the durable queue accepts the previous one',
      );
      expect(coordinator.segmentIndex, 1);
      expect((await queue.sessions()).single.segments.single.segmentIndex, 0);

      await coordinator.stop();
      final session = (await queue.sessions()).single;
      expect(recorder.stops, 2);
      expect(session.segments.map((segment) => segment.segmentIndex), [0, 1]);
      expect(session.lastSegmentIndex, 1);
      expect(coordinator.phase, CapturePhase.idle);
      expect(lease.acquired, 1);
      expect(lease.released, greaterThanOrEqualTo(1));
    },
  );

  test('backgrounding closes the current segment without restarting', () async {
    final queue = MemoryCaptureQueue();
    final recorder = FakeRecorder();
    final scheduler = FakeScheduler();
    final backend = FakeBackend()..online = false;
    final coordinator = CaptureCoordinator(
      deviceId: 'phone-1',
      recorder: recorder,
      telemetry: FakeTelemetry(),
      queue: queue,
      uploader: QueueUploader(queue: queue, backend: backend),
      foregroundLease: FakeLease(),
      scheduler: scheduler,
      monotonicNanos: () => 123,
      utcNow: () => DateTime.utc(2026, 8, 23, 12),
      newSessionId: () => 'background-session',
    );
    addTearDown(coordinator.dispose);

    await coordinator.start();
    await coordinator.stopForBackground();

    final session = (await queue.sessions()).single;
    expect(session.isClosed, isTrue);
    expect(session.segments, hasLength(1));
    expect(recorder.starts, 1);
    expect(recorder.stops, 1);
    expect(scheduler.duration, isNull);
    expect(coordinator.phase, CapturePhase.idle);
  });
}
