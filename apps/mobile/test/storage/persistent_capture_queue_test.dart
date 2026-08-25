import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/storage/persistent_capture_queue.dart';

void main() {
  test(
    'persists a captured segment and deletes it only after verification',
    () async {
      final root = await Directory.systemTemp.createTemp('greenv-queue-test-');
      addTearDown(() => root.delete(recursive: true));
      final cameraFile = File(
        '${root.path}${Platform.pathSeparator}camera-output.mp4',
      );
      await cameraFile.writeAsBytes(List<int>.generate(256, (index) => index));
      final startedAt = DateTime.utc(2026, 8, 23, 12);

      final queue = PersistentCaptureQueue(
        Directory('${root.path}${Platform.pathSeparator}queue'),
      );
      await queue.initialize();
      await queue.beginSession(
        QueuedSession(
          sessionId: 'session-1',
          deviceId: 'phone-1',
          startedAtUtc: startedAt,
        ),
      );
      final segment = await queue.enqueueCaptured(
        sessionId: 'session-1',
        segmentIndex: 0,
        capturedAtUtc: startedAt,
        durationMillis: 10000,
        sourceVideoPath: cameraFile.path,
        telemetry: const SegmentTelemetryDocument({
          'schemaVersion': 1,
          'locationSamples': <Object?>[],
          'motionSamples': <Object?>[],
        }),
      );

      expect(
        await cameraFile.exists(),
        isFalse,
        reason: 'camera temporary file is superseded by the durable copy',
      );
      expect(await File(segment.videoPath).exists(), isTrue);
      expect(await File(segment.telemetryPath).exists(), isTrue);
      expect(queue.backlog.value, 1);

      final restarted = PersistentCaptureQueue(
        Directory('${root.path}${Platform.pathSeparator}queue'),
      );
      await restarted.initialize();
      final recovered = (await restarted.sessions()).single.segments.single;
      expect(recovered.videoSha256, segment.videoSha256);
      expect(
        await File(recovered.videoPath).exists(),
        isTrue,
        reason: 'restart must retain an unverified upload',
      );

      await restarted.updateSegment(
        recovered.copyWith(state: SegmentUploadState.awaitingVerification),
      );
      expect(
        await File(recovered.videoPath).exists(),
        isTrue,
        reason: 'an accepted upload is not yet worker verification',
      );

      await restarted.removeVerifiedSegment(recovered);
      expect(await File(recovered.videoPath).exists(), isFalse);
      expect(await File(recovered.telemetryPath).exists(), isFalse);
      expect(restarted.backlog.value, 0);
    },
  );
}
