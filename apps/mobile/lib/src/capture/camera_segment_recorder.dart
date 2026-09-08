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

  /// Capture formats to try, best first.
  ///
  /// Frame rate is the lever that decides whether a stretch of road can be reconstructed at all: a
  /// twenty-metre stretch is measured from the frames recorded while crossing it, so at 30 fps it
  /// holds 22 views at 100 km/h — below anything the depth model has been graded at — and at 120 fps
  /// it holds 86, back inside that range. So the ladder asks for the highest rate first.
  ///
  /// At equal rate the LOWER resolution wins. The worker resizes every frame to a 1024 px long edge
  /// before publishing and 720p is already 1280 px across, so 1080p would discard 72% of what it
  /// captured while costing the 64 MB upload budget. 1080p is here only because some devices offer a
  /// high-rate format at that resolution and not at 720p.
  ///
  /// The last rung asks for no rate at all, which is what a device with no high-rate format needs.
  static const List<({ResolutionPreset resolution, int? fps})> preferredFormats = [
    (resolution: ResolutionPreset.high, fps: 240),
    (resolution: ResolutionPreset.veryHigh, fps: 240),
    (resolution: ResolutionPreset.high, fps: 120),
    (resolution: ResolutionPreset.veryHigh, fps: 120),
    (resolution: ResolutionPreset.high, fps: 60),
    (resolution: ResolutionPreset.high, fps: null),
  ];

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

    // The two platforms fail differently, which is why this walks a ladder instead of asking once.
    // iOS searches the formats at the requested resolution and clamps to the closest rate it finds,
    // so the first rung nearly always succeeds and yields the fastest format that resolution has.
    // Android asks CameraX for an exact [fps, fps] range and the bind throws when no format offers
    // it, so the lower rungs are what a 30 fps phone lands on.
    //
    // Neither platform reports the rate it actually achieved — `CameraValue` does not carry it — so
    // the app cannot log what it got. The worker measures it per segment and records it in the
    // manifest as `nativeFps`; that is the number to trust.
    Object? lastFailure;
    for (final format in preferredFormats) {
      final controller = CameraController(
        selected,
        format.resolution,
        enableAudio: false,
        imageFormatGroup: ImageFormatGroup.yuv420,
        fps: format.fps,
      );
      try {
        await controller.initialize();
        await controller.prepareForVideoRecording();
        _controller = controller;
        return;
      } on Object catch (error) {
        lastFailure = error;
        await controller.dispose().catchError((_) {});
      }
    }
    throw StateError('no usable capture format on this camera: $lastFailure');
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
