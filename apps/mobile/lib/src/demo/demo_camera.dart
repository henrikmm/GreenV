import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/services.dart';
import 'package:greenv_capture/src/demo/demo_settings.dart';

/// The camera as the demo uses it: one back camera, one format, settings applied and then read
/// back, because the plugin accepts requests it cannot honour and says nothing about it.
///
/// Unlike the car capture (`CameraSegmentRecorder`), this asks for an ordinary rate. The car needs
/// 120–240 fps to see a stretch of road at speed; an object on a table needs a long exposure in a
/// dim hall far more than it needs frames the gateway will throw away when it samples.
final class DemoCamera {
  CameraController? _controller;
  Map<String, Object?> _applied = const {};
  double _minExposure = 0, _maxExposure = 0;

  CameraController? get controller => _controller;
  bool get isReady => _controller?.value.isInitialized ?? false;
  bool get isRecording => _controller?.value.isRecordingVideo ?? false;

  /// What the camera actually accepted, sent with every capture so a comparison can trust it.
  Map<String, Object?> get applied => _applied;
  double get minExposureOffset => _minExposure;
  double get maxExposureOffset => _maxExposure;

  static ResolutionPreset _preset(DemoResolution r) => switch (r) {
    DemoResolution.p720 => ResolutionPreset.high,
    DemoResolution.p1080 => ResolutionPreset.veryHigh,
    DemoResolution.p2160 => ResolutionPreset.ultraHigh,
  };

  /// Opening and closing are serialised. Android pauses the app to show the camera permission
  /// dialog, so a close and a reopen can arrive while the first open is still initialising; run
  /// side by side, they dispose the controller another call is still configuring.
  Future<void> _tail = Future<void>.value();

  Future<void> _serial(Future<void> Function() op) {
    final next = _tail.then((_) => op());
    _tail = next.catchError((Object _) {});
    return next;
  }

  Future<void> open(DemoSettings settings) => _serial(() => _open(settings));

  Future<void> close() => _serial(_close);

  Future<void> _open(DemoSettings settings) async {
    await _close();
    final cameras = await availableCameras();
    if (cameras.isEmpty) throw StateError('nenhuma câmera disponível');
    // The first back camera CameraX lists is the main wide lens on every phone tried so far; the
    // ultra-wide distorts more and is never chosen on purpose.
    final camera = cameras.firstWhere(
      (c) => c.lensDirection == CameraLensDirection.back,
      orElse: () => cameras.first,
    );
    final controller = CameraController(
      camera,
      _preset(settings.resolution),
      fps: settings.fps,
      enableAudio: false,
      imageFormatGroup: ImageFormatGroup.yuv420,
    );
    await controller.initialize();
    // The video is portrait whatever the phone thinks; a sideways clip would reach the gateway
    // with a rotation flag the extraction then has to get right.
    await controller.lockCaptureOrientation(DeviceOrientation.portraitUp);
    await controller.prepareForVideoRecording();
    _controller = controller;

    final supportedStabilization = await controller.getSupportedVideoStabilizationModes();
    var stabilization = VideoStabilizationMode.off;
    if (settings.stabilization) {
      try {
        await controller.setVideoStabilizationMode(VideoStabilizationMode.level1, allowFallback: true);
        stabilization = controller.value.videoStabilizationMode;
      } on Object {
        stabilization = VideoStabilizationMode.off;
      }
    } else {
      await controller.setVideoStabilizationMode(VideoStabilizationMode.off).catchError((_) {});
    }

    _minExposure = await controller.getMinExposureOffset();
    _maxExposure = await controller.getMaxExposureOffset();
    _applied = {
      'lens': camera.name,
      'sensorOrientation': camera.sensorOrientation,
      'previewSize': controller.value.previewSize == null
          ? null
          : '${controller.value.previewSize!.width.round()}x${controller.value.previewSize!.height.round()}',
      'stabilization': stabilization.name,
      'stabilizationSupported': [for (final m in supportedStabilization) m.name],
      'exposureOffsetRange': [_minExposure, _maxExposure],
    };
    await applyLive(settings);
  }

  /// Settings that take effect without reopening the camera.
  Future<void> applyLive(DemoSettings settings) async {
    final controller = _controller;
    if (controller == null) return;
    var torch = false;
    try {
      await controller.setFlashMode(settings.torch ? FlashMode.torch : FlashMode.off);
      torch = settings.torch;
    } on Object {
      torch = false;
    }
    var exposure = 0.0;
    if (_maxExposure > _minExposure) {
      exposure = await controller.setExposureOffset(settings.exposureOffset.clamp(_minExposure, _maxExposure));
    }
    _applied = {..._applied, 'torch': torch, 'exposureOffset': exposure};
  }

  /// Point focus and metering at a tap, in preview coordinates from 0 to 1.
  Future<void> focusAt(double x, double y) async {
    final controller = _controller;
    if (controller == null || isRecording) return;
    final point = Offset(x.clamp(0.0, 1.0), y.clamp(0.0, 1.0));
    await controller.setFocusPoint(point).catchError((_) {});
    await controller.setExposurePoint(point).catchError((_) {});
  }

  Future<void> startRecording(DemoSettings settings) async {
    final controller = _controller;
    if (controller == null) throw StateError('a câmera não está pronta');
    var locked = false;
    if (settings.lockFocusAndExposure) {
      try {
        await controller.setFocusMode(FocusMode.locked);
        await controller.setExposureMode(ExposureMode.locked);
        locked = true;
      } on Object {
        locked = false;
      }
    }
    _applied = {..._applied, 'focusAndExposureLocked': locked};
    await controller.startVideoRecording();
  }

  Future<File> stopRecording() async {
    final controller = _controller;
    if (controller == null || !controller.value.isRecordingVideo) throw StateError('não está gravando');
    final file = await controller.stopVideoRecording();
    await controller.setFocusMode(FocusMode.auto).catchError((_) {});
    await controller.setExposureMode(ExposureMode.auto).catchError((_) {});
    return File(file.path);
  }

  Future<void> _close() async {
    final controller = _controller;
    _controller = null;
    await controller?.dispose();
  }
}
