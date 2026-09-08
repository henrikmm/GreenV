import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

final class PersistentCaptureQueue implements CaptureQueue, SegmentContentStore {
  PersistentCaptureQueue(this.rootDirectory);

  final Directory rootDirectory;
  final ValueNotifier<int> _backlog = ValueNotifier(0);
  Future<void> _tail = Future.value();

  File get _index =>
      File('${rootDirectory.path}${Platform.pathSeparator}queue-v1.json');

  @override
  ValueListenable<int> get backlog => _backlog;

  Future<void> initialize() async {
    await rootDirectory.create(recursive: true);
    _updateBacklog(await _read());
  }

  @override
  Future<List<QueuedSession>> sessions() => _locked(_read);

  @override
  Future<void> beginSession(QueuedSession session) => _locked(() async {
    final values = await _read();
    final existing = _session(values, session.sessionId);
    if (existing != null) {
      if (existing.deviceId != session.deviceId ||
          existing.startedAtUtc != session.startedAtUtc) {
        throw StateError('session id already has different capture metadata');
      }
      return;
    }
    await _write([...values, session]);
  });

  @override
  Future<QueuedSegment> enqueueCaptured({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int durationMillis,
    required String sourceVideoPath,
    required String videoContentType,
    required SegmentTelemetryDocument telemetry,
  }) => _locked(() async {
    final values = await _read();
    final sessionPosition = values.indexWhere(
      (item) => item.sessionId == sessionId,
    );
    if (sessionPosition < 0) {
      throw StateError('capture session is not registered');
    }
    final existing = _segment(values[sessionPosition].segments, segmentIndex);
    if (existing != null) return existing;

    final segmentDirectory = Directory(
      '${rootDirectory.path}${Platform.pathSeparator}$sessionId'
      '${Platform.pathSeparator}${segmentIndex.toString().padLeft(8, '0')}',
    );
    await segmentDirectory.create(recursive: true);
    final video = File(
      '${segmentDirectory.path}${Platform.pathSeparator}source.mp4',
    );
    final telemetryFile = File(
      '${segmentDirectory.path}${Platform.pathSeparator}telemetry.json',
    );
    await File(sourceVideoPath).openRead().pipe(video.openWrite());
    await telemetryFile.writeAsString(jsonEncode(telemetry.json), flush: true);
    final queued = QueuedSegment(
      sessionId: sessionId,
      segmentIndex: segmentIndex,
      idempotencyKey: '$sessionId:$segmentIndex',
      capturedAtUtc: capturedAtUtc,
      durationMillis: durationMillis,
      videoPath: video.path,
      videoSha256: await _sha256(video),
      telemetryPath: telemetryFile.path,
      telemetrySha256: await _sha256(telemetryFile),
      videoContentType: videoContentType,
    );
    final session = values[sessionPosition];
    values[sessionPosition] = session.copyWith(
      segments: [...session.segments, queued],
    );
    await _write(values);
    final source = File(sourceVideoPath);
    if (await source.exists()) await source.delete();
    return queued;
  });

  @override
  Future<void> closeSession(
    String sessionId, {
    required DateTime endedAtUtc,
    required int lastSegmentIndex,
  }) => _locked(() async {
    final values = await _read();
    final position = values.indexWhere((item) => item.sessionId == sessionId);
    if (position < 0) throw StateError('capture session is not registered');
    values[position] = values[position].copyWith(
      endedAtUtc: endedAtUtc,
      lastSegmentIndex: lastSegmentIndex,
    );
    await _write(values);
  });

  @override
  Future<void> updateSegment(QueuedSegment segment) => _locked(() async {
    final values = await _read();
    final sessionPosition = values.indexWhere(
      (item) => item.sessionId == segment.sessionId,
    );
    if (sessionPosition < 0) return;
    final session = values[sessionPosition];
    final segments = [...session.segments];
    final segmentPosition = segments.indexWhere(
      (item) => item.segmentIndex == segment.segmentIndex,
    );
    if (segmentPosition < 0) return;
    segments[segmentPosition] = segment;
    values[sessionPosition] = session.copyWith(segments: segments);
    await _write(values);
  });

  @override
  Future<void> removeVerifiedSegment(QueuedSegment segment) =>
      _locked(() async {
        final values = await _read();
        final sessionPosition = values.indexWhere(
          (item) => item.sessionId == segment.sessionId,
        );
        if (sessionPosition < 0) return;
        final session = values[sessionPosition];
        final segments = session.segments
            .where((item) => item.segmentIndex != segment.segmentIndex)
            .toList();
        values[sessionPosition] = session.copyWith(segments: segments);
        await _write(values);
        final video = File(segment.videoPath);
        final telemetry = File(segment.telemetryPath);
        if (await video.exists()) await video.delete();
        if (await telemetry.exists()) await telemetry.delete();
        final directory = video.parent;
        if (await directory.exists()) await directory.delete(recursive: true);
      });

  @override
  Future<void> markCompletionSent(String sessionId) => _locked(() async {
    final values = await _read();
    final position = values.indexWhere((item) => item.sessionId == sessionId);
    if (position < 0) return;
    values[position] = values[position].copyWith(completionSent: true);
    await _write(values);
  });

  @override
  Future<int> length(String reference) => File(reference).length();

  @override
  Stream<List<int>> read(String reference) => File(reference).openRead();

  Future<List<QueuedSession>> _read() async {
    if (!await _index.exists()) return [];
    final decoded = (jsonDecode(await _index.readAsString())! as Map)
        .cast<String, Object?>();
    return (decoded['sessions']! as List<Object?>)
        .map(
          (value) =>
              QueuedSession.fromJson((value! as Map).cast<String, Object?>()),
        )
        .toList();
  }

  Future<void> _write(List<QueuedSession> sessions) async {
    await rootDirectory.create(recursive: true);
    final temporary = File('${_index.path}.writing');
    await temporary.writeAsString(
      jsonEncode({
        'schemaVersion': 1,
        'sessions': sessions.map((item) => item.toJson()).toList(),
      }),
      flush: true,
    );
    if (await _index.exists()) await _index.delete();
    await temporary.rename(_index.path);
    _updateBacklog(sessions);
  }

  Future<T> _locked<T>(Future<T> Function() action) async {
    final previous = _tail;
    final completed = Completer<void>();
    _tail = completed.future;
    await previous;
    try {
      return await action();
    } finally {
      completed.complete();
    }
  }

  void _updateBacklog(List<QueuedSession> sessions) {
    _backlog.value = sessions.fold(
      0,
      (count, session) => count + session.segments.length,
    );
  }

  static QueuedSession? _session(List<QueuedSession> values, String id) {
    for (final value in values) {
      if (value.sessionId == id) return value;
    }
    return null;
  }

  static QueuedSegment? _segment(List<QueuedSegment> values, int index) {
    for (final value in values) {
      if (value.segmentIndex == index) return value;
    }
    return null;
  }

  static Future<String> _sha256(File file) async =>
      (await sha256.bind(file.openRead()).first).toString();
}
