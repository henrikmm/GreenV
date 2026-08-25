// ignore_for_file: prefer_initializing_formals

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';
import 'package:uuid/uuid.dart';

enum CapturePhase { idle, preparing, recording, stopping, error }

final class CaptureCoordinator extends ChangeNotifier {
  CaptureCoordinator({
    required this.deviceId,
    required SegmentRecorder recorder,
    required TelemetryCollector telemetry,
    required CaptureQueue queue,
    required QueueUploader uploader,
    required ForegroundLease foregroundLease,
    required SegmentScheduler scheduler,
    required int Function() monotonicNanos,
    DateTime Function()? utcNow,
    String Function()? newSessionId,
    this.segmentDuration = const Duration(seconds: 10),
  }) : _recorder = recorder,
       _telemetry = telemetry,
       _queue = queue,
       _uploader = uploader,
       _foregroundLease = foregroundLease,
       _scheduler = scheduler,
       _monotonicNanos = monotonicNanos,
       _utcNow = utcNow ?? (() => DateTime.now().toUtc()),
       _newSessionId = newSessionId ?? (() => const Uuid().v4());

  final String deviceId;
  final SegmentRecorder _recorder;
  final TelemetryCollector _telemetry;
  final CaptureQueue _queue;
  final QueueUploader _uploader;
  final ForegroundLease _foregroundLease;
  final SegmentScheduler _scheduler;
  final int Function() _monotonicNanos;
  final DateTime Function() _utcNow;
  final String Function() _newSessionId;
  final Duration segmentDuration;

  CapturePhase phase = CapturePhase.idle;
  String? errorMessage;
  String? sessionId;
  int segmentIndex = 0;
  DateTime? recordingStartedAt;
  DateTime? _segmentCapturedAt;
  bool _continueRecording = false;
  bool _rotating = false;

  bool get isRecording => phase == CapturePhase.recording;
  SegmentRecorder get recorder => _recorder;
  TelemetryCollector get telemetry => _telemetry;
  ValueListenable<int> get backlog => _queue.backlog;

  Future<void> syncBacklog() => _uploader.syncOnce();

  Future<void> start() async {
    if (phase != CapturePhase.idle && phase != CapturePhase.error) return;
    phase = CapturePhase.preparing;
    errorMessage = null;
    notifyListeners();
    final startedAt = _utcNow();
    final id = _newSessionId();
    try {
      await _recorder.initialize();
      await _queue.beginSession(
        QueuedSession(
          sessionId: id,
          deviceId: deviceId,
          startedAtUtc: startedAt,
        ),
      );
      await _foregroundLease.acquire();
      sessionId = id;
      segmentIndex = 0;
      recordingStartedAt = startedAt;
      _continueRecording = true;
      await _beginSegment();
    } on Object catch (error) {
      phase = CapturePhase.error;
      errorMessage = error.toString();
      await _foregroundLease.release();
      notifyListeners();
    }
  }

  Future<void> stop() async {
    if (phase != CapturePhase.recording) return;
    phase = CapturePhase.stopping;
    _continueRecording = false;
    _scheduler.cancel();
    notifyListeners();
    await _rotate();
  }

  Future<void> stopForBackground() => stop();

  Future<void> _beginSegment() async {
    final capturedAt = _utcNow();
    _segmentCapturedAt = capturedAt;
    await _telemetry.beginSegment(
      sessionId: sessionId!,
      segmentIndex: segmentIndex,
      capturedAtUtc: capturedAt,
      monotonicStartNanos: _monotonicNanos(),
    );
    await _recorder.start();
    phase = CapturePhase.recording;
    notifyListeners();
    _scheduler.schedule(segmentDuration, _rotate);
  }

  Future<void> _rotate() async {
    if (_rotating || sessionId == null) return;
    _rotating = true;
    _scheduler.cancel();
    try {
      final video = await _recorder.stop();
      final telemetry = await _telemetry.finishSegment();
      await _queue.enqueueCaptured(
        sessionId: sessionId!,
        segmentIndex: segmentIndex,
        capturedAtUtc: _segmentCapturedAt!,
        durationMillis: video.durationMillis,
        sourceVideoPath: video.path,
        telemetry: telemetry,
      );
      _uploader.syncSoon();
      if (_continueRecording) {
        segmentIndex += 1;
        await _beginSegment();
      } else {
        await _queue.closeSession(
          sessionId!,
          endedAtUtc: _utcNow(),
          lastSegmentIndex: segmentIndex,
        );
        await _foregroundLease.release();
        phase = CapturePhase.idle;
        sessionId = null;
        recordingStartedAt = null;
        notifyListeners();
        await _uploader.syncOnce();
      }
    } on Object catch (error) {
      _continueRecording = false;
      await _foregroundLease.release();
      phase = CapturePhase.error;
      errorMessage = error.toString();
      notifyListeners();
    } finally {
      _rotating = false;
    }
  }

  @override
  void dispose() {
    _scheduler.cancel();
    unawaited(_recorder.dispose());
    unawaited(_telemetry.dispose());
    unawaited(_foregroundLease.release());
    super.dispose();
  }
}
