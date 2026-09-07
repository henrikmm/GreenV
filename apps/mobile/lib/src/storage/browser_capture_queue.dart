import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:cross_file/cross_file.dart';
import 'package:flutter/foundation.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

/// The browser capture build's queue. It keeps every artifact in memory because a page has no
/// durable application-documents directory, so unlike [PersistentCaptureQueue] it does not survive
/// a reload: this build is a workstation test harness for the cloud pipeline, not the phone
/// client. Everything else the uploader relies on is real — the SHA-256 digests are computed over
/// the actual bytes, and a segment is dropped only after the worker reports it ready.
final class BrowserCaptureQueue implements CaptureQueue, SegmentContentStore {
  final List<QueuedSession> _sessions = [];
  final Map<String, Uint8List> _artifacts = {};
  final ValueNotifier<int> _backlog = ValueNotifier(0);

  @override
  ValueListenable<int> get backlog => _backlog;

  @override
  Future<List<QueuedSession>> sessions() async => List.unmodifiable(_sessions);

  @override
  Future<void> beginSession(QueuedSession session) async {
    if (_position(session.sessionId) >= 0) return;
    _sessions.add(session);
  }

  @override
  Future<QueuedSegment> enqueueCaptured({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int durationMillis,
    required String sourceVideoPath,
    required String videoContentType,
    required SegmentTelemetryDocument telemetry,
  }) async {
    final position = _position(sessionId);
    if (position < 0) throw StateError('capture session is not registered');
    final existing = _segment(_sessions[position].segments, segmentIndex);
    if (existing != null) return existing;

    // The camera plugin hands back a blob URL; reading it now detaches the segment from the
    // recorder so the next rotation cannot overwrite it.
    final video = await XFile(sourceVideoPath).readAsBytes();
    final telemetryBytes = Uint8List.fromList(
      utf8.encode(jsonEncode(telemetry.json)),
    );
    final prefix = 'memory://$sessionId/${segmentIndex.toString().padLeft(8, '0')}';
    final videoReference = '$prefix/source';
    final telemetryReference = '$prefix/telemetry.json';
    _artifacts[videoReference] = video;
    _artifacts[telemetryReference] = telemetryBytes;

    final queued = QueuedSegment(
      sessionId: sessionId,
      segmentIndex: segmentIndex,
      idempotencyKey: '$sessionId:$segmentIndex',
      capturedAtUtc: capturedAtUtc,
      durationMillis: durationMillis,
      videoPath: videoReference,
      videoSha256: sha256.convert(video).toString(),
      telemetryPath: telemetryReference,
      telemetrySha256: sha256.convert(telemetryBytes).toString(),
      videoContentType: videoContentType,
    );
    final session = _sessions[position];
    _sessions[position] = session.copyWith(
      segments: [...session.segments, queued],
    );
    _refresh();
    return queued;
  }

  @override
  Future<void> closeSession(
    String sessionId, {
    required DateTime endedAtUtc,
    required int lastSegmentIndex,
  }) async {
    final position = _position(sessionId);
    if (position < 0) throw StateError('capture session is not registered');
    _sessions[position] = _sessions[position].copyWith(
      endedAtUtc: endedAtUtc,
      lastSegmentIndex: lastSegmentIndex,
    );
  }

  @override
  Future<void> updateSegment(QueuedSegment segment) async {
    final sessionPosition = _position(segment.sessionId);
    if (sessionPosition < 0) return;
    final session = _sessions[sessionPosition];
    final segments = [...session.segments];
    final position = segments.indexWhere(
      (item) => item.segmentIndex == segment.segmentIndex,
    );
    if (position < 0) return;
    segments[position] = segment;
    _sessions[sessionPosition] = session.copyWith(segments: segments);
  }

  @override
  Future<void> removeVerifiedSegment(QueuedSegment segment) async {
    final position = _position(segment.sessionId);
    if (position < 0) return;
    final session = _sessions[position];
    _sessions[position] = session.copyWith(
      segments: session.segments
          .where((item) => item.segmentIndex != segment.segmentIndex)
          .toList(),
    );
    _artifacts
      ..remove(segment.videoPath)
      ..remove(segment.telemetryPath);
    _refresh();
  }

  @override
  Future<void> markCompletionSent(String sessionId) async {
    final position = _position(sessionId);
    if (position < 0) return;
    _sessions[position] = _sessions[position].copyWith(completionSent: true);
  }

  @override
  Future<int> length(String reference) async => _require(reference).length;

  @override
  Stream<List<int>> read(String reference) =>
      Stream<List<int>>.value(_require(reference));

  Uint8List _require(String reference) {
    final bytes = _artifacts[reference];
    if (bytes == null) throw StateError('queued artifact $reference is gone');
    return bytes;
  }

  int _position(String sessionId) =>
      _sessions.indexWhere((item) => item.sessionId == sessionId);

  void _refresh() {
    _backlog.value = _sessions.fold(
      0,
      (count, session) => count + session.segments.length,
    );
  }

  static QueuedSegment? _segment(List<QueuedSegment> values, int index) {
    for (final value in values) {
      if (value.segmentIndex == index) return value;
    }
    return null;
  }
}
