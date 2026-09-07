import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

final class CameraSegmentRecorder implements SegmentRecorder {
  CameraController? _controller;
  Stopwatch? _segmentClock;

  CameraController? get controller => _controller;

  @override
  bool get isInitialized => _controller?.value.isInitialized ?? false;

  @override
  Widget buildPreview() {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      return const ColoredBox(color: Color(0xFF17151A));
    }
    return CameraPreview(controller);
  }

  @override
  Future<void> initialize() async {
    if (isInitialized) return;
    final available = await availableCameras();
    if (available.isEmpty) throw StateError('no camera is available');
    CameraDescription selected = available.first;
    for (final camera in available) {
      if (camera.lensDirection == CameraLensDirection.back) {
        selected = camera;
        break;
      }
    }
    final controller = CameraController(
      selected,
      ResolutionPreset.high,
      enableAudio: false,
      imageFormatGroup: ImageFormatGroup.yuv420,
    );
    await controller.initialize();
    await controller.prepareForVideoRecording();
    _controller = controller;
  }

  @override
  Future<void> start() async {
    final controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      throw StateError('camera is not initialized');
    }
    _segmentClock = Stopwatch()..start();
    await controller.startVideoRecording();
  }

  @override
  Future<RecordedVideo> stop() async {
    final controller = _controller;
    final clock = _segmentClock;
    if (controller == null ||
        clock == null ||
        !controller.value.isRecordingVideo) {
      throw StateError('camera is not recording');
    }
    final video = await controller.stopVideoRecording();
    clock.stop();
    _segmentClock = null;
    return RecordedVideo(
      path: video.path,
      durationMillis: clock.elapsedMilliseconds.clamp(1, 30000),
      contentType: _container(video.mimeType),
    );
  }

  @override
  Future<void> dispose() async {
    await _controller?.dispose();
    _controller = null;
  }

  /// A browser's MediaRecorder reports something like `video/webm;codecs="vp9,opus"`. The API
  /// matches on type and subtype, so the codec parameters are dropped here rather than sent.
  static String _container(String? mimeType) {
    if (mimeType == null || mimeType.isEmpty) return 'video/mp4';
    return mimeType.split(';').first.trim();
  }
}
