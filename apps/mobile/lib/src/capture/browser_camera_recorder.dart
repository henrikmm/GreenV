import 'dart:async';
import 'dart:js_interop';
import 'dart:ui_web' as ui_web;

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:web/web.dart' as web;

/// Records segments straight from `getUserMedia` and `MediaRecorder`.
///
/// The browser build cannot use `package:camera` here. `camera_web.availableCameras()` opens a
/// stream for every video input device to read its facing mode, with no per-device error handling,
/// so one device that enumerates but refuses to open — a virtual camera whose application is not
/// running is the common case — throws away the whole list, including the real webcams it had
/// already collected. This recorder instead tries the devices in turn and keeps the first that
/// actually opens.
final class BrowserCameraRecorder implements SegmentRecorder {
  BrowserCameraRecorder({this.preferredLabel});

  /// Substring of a device label to try first, case-insensitive. Useful when a workstation has
  /// several cameras and the first working one is not the one pointing at the road.
  final String? preferredLabel;

  static const String _viewType = 'greenv-browser-camera-preview';
  static const List<String> _preferredMimeTypes = [
    'video/mp4',
    'video/webm;codecs=vp9',
    'video/webm;codecs=vp8',
    'video/webm',
  ];

  static bool _viewFactoryRegistered = false;

  web.MediaStream? _stream;
  web.HTMLVideoElement? _preview;
  web.MediaRecorder? _recorder;
  final List<web.Blob> _chunks = [];
  Completer<void>? _recordingStopped;
  Stopwatch? _segmentClock;
  String _mimeType = 'video/webm';
  String? _openedLabel;

  /// What the browser granted, once a camera is open. Null before that, or if it does not say.
  double? grantedFrameRate;

  @override
  double? get capturedFrameRate => grantedFrameRate;

  /// The device the recorder actually opened, for the capture screen and for error reports.
  String? get openedLabel => _openedLabel;

  @override
  bool get isInitialized => _stream != null;

  @override
  Widget buildPreview() {
    if (_preview == null) {
      return const ColoredBox(color: Color(0xFF17151A));
    }
    return const HtmlElementView(viewType: _viewType);
  }

  @override
  Future<void> initialize() async {
    if (isInitialized) return;

    // One permissionless enumeration returns empty labels, so ask for any camera first. The
    // stream is released immediately; it exists only to unlock the device labels.
    final permission = await _open(web.MediaStreamConstraints(video: true.toJS));
    _release(permission);

    final devices = (await web.window.navigator.mediaDevices.enumerateDevices().toDart).toDart
        .where((device) => device.kind == 'videoinput')
        .where((device) => device.deviceId.isNotEmpty)
        .toList();
    if (devices.isEmpty) throw StateError('no camera is available');

    final failures = <String>[];
    for (final device in _ordered(devices)) {
      try {
        _stream = await _open(_constraintsFor(device.deviceId));
        _openedLabel = device.label.isEmpty ? device.deviceId : device.label;
        break;
      } on Object catch (error) {
        failures.add('${device.label.isEmpty ? device.deviceId : device.label}: $error');
      }
    }
    final stream = _stream;
    if (stream == null) {
      throw StateError('no camera could be opened — ${failures.join('; ')}');
    }

    if (!_viewFactoryRegistered) {
      ui_web.platformViewRegistry.registerViewFactory(
        _viewType,
        (int viewId) => _preview ?? web.document.createElement('div'),
      );
      _viewFactoryRegistered = true;
    }

    // The browser is the one platform that reports what it actually granted; AVFoundation and
    // CameraX do not. Worth reading, because it is the difference between "asked for 240" and
    // "recording at 240", and the operator can see it before driving anywhere.
    grantedFrameRate = _grantedFrameRate(stream);

    final preview = web.document.createElement('video') as web.HTMLVideoElement
      ..autoplay = true
      ..muted = true
      ..srcObject = stream;
    preview.setAttribute('playsinline', 'true');
    preview.style
      ..width = '100%'
      ..height = '100%'
      ..objectFit = 'cover';
    _preview = preview;
    _mimeType = _supportedMimeType();
  }

  @override
  Future<void> start() async {
    final stream = _stream;
    if (stream == null) throw StateError('camera is not initialized');
    _chunks.clear();
    final stopped = Completer<void>();
    _recordingStopped = stopped;

    final recorder = web.MediaRecorder(
      stream,
      web.MediaRecorderOptions(
        mimeType: _mimeType,
        // Scaled with the rate, because MediaRecorder's default is a fixed budget for the whole
        // stream: at 120 fps it would give each frame a quarter of the bits it gives at 30, and
        // compression artefacts are exactly what breaks the feature matching this footage exists
        // for. About 90 kbit per 720p frame, bounded so a fast camera cannot blow the API's 64 MB
        // per-segment limit.
        videoBitsPerSecond: _videoBitrate(),
      ),
    );
    recorder.addEventListener(
      'dataavailable',
      ((web.Event event) {
        final data = (event as web.BlobEvent).data;
        if (data.size > 0) _chunks.add(data);
      }).toJS,
    );
    recorder.addEventListener(
      'stop',
      ((web.Event _) {
        if (!stopped.isCompleted) stopped.complete();
      }).toJS,
    );
    _recorder = recorder;
    _segmentClock = Stopwatch()..start();
    recorder.start();
  }

  @override
  Future<RecordedVideo> stop() async {
    final recorder = _recorder;
    final clock = _segmentClock;
    final stopped = _recordingStopped;
    if (recorder == null || clock == null || stopped == null) {
      throw StateError('camera is not recording');
    }
    recorder.stop();
    await stopped.future;
    clock.stop();
    _recorder = null;
    _segmentClock = null;
    _recordingStopped = null;

    if (_chunks.isEmpty) throw StateError('the recorder produced no video data');
    final blob = web.Blob(
      _chunks.map((chunk) => chunk as JSAny).toList().toJS,
      web.BlobPropertyBag(type: _mimeType),
    );
    _chunks.clear();
    return RecordedVideo(
      // A blob URL, which the queue reads back with XFile before the next rotation overwrites it.
      path: web.URL.createObjectURL(blob),
      durationMillis: clock.elapsedMilliseconds.clamp(1, 30000),
      contentType: _mimeType.split(';').first.trim(),
    );
  }

  @override
  Future<void> dispose() async {
    _release(_stream);
    _stream = null;
    _preview?.srcObject = null;
    _preview = null;
  }

  Iterable<web.MediaDeviceInfo> _ordered(List<web.MediaDeviceInfo> devices) {
    final wanted = preferredLabel?.toLowerCase();
    if (wanted == null || wanted.isEmpty) return devices;
    final matching = devices.where((d) => d.label.toLowerCase().contains(wanted));
    final rest = devices.where((d) => !d.label.toLowerCase().contains(wanted));
    return [...matching, ...rest];
  }

  /// Asks for the fastest capture the device will give at 720p.
  ///
  /// Frame rate is what decides whether a stretch of road can be reconstructed: a twenty-metre
  /// stretch is measured from the frames recorded while crossing it, so 30 fps leaves 22 views at
  /// 100 km/h and 120 fps leaves 86. `ideal` rather than `exact` because an unsatisfiable `exact`
  /// makes getUserMedia reject the device outright, and a 30 fps webcam is still worth opening.
  ///
  /// 720p is deliberate, not a compromise: the worker resizes every frame to a 1024 px long edge,
  /// so asking for more pixels would only spend the frame rate a camera can hold.
  static web.MediaStreamConstraints _constraintsFor(String deviceId) =>
      web.MediaStreamConstraints(
        video: {
          'deviceId': {'exact': deviceId},
          'width': {'ideal': 1280},
          'height': {'ideal': 720},
          'frameRate': {'ideal': 240, 'min': 24},
        }.jsify()!,
        audio: false.toJS,
      );

  static const int _bitsPerFrame = 90_000;
  static const int _minimumBitrate = 2_500_000;
  static const int _maximumBitrate = 24_000_000;

  int _videoBitrate() {
    final rate = grantedFrameRate ?? 30.0;
    return (rate * _bitsPerFrame).round().clamp(_minimumBitrate, _maximumBitrate);
  }

  /// Frames per second the browser settled on, or null when it does not report one.
  ///
  /// This is the app's own reading. The worker measures the encoded file independently and records
  /// it as `nativeFps` in the manifest; when the two disagree, the worker's is the one that counts,
  /// because it describes the bytes that were actually uploaded.
  static double? _grantedFrameRate(web.MediaStream stream) {
    final tracks = stream.getVideoTracks().toDart;
    if (tracks.isEmpty) return null;
    final settings = tracks.first.getSettings();
    final rate = settings.frameRate;
    return rate.isFinite && rate > 0 ? rate : null;
  }

  static Future<web.MediaStream> _open(web.MediaStreamConstraints constraints) async =>
      (await web.window.navigator.mediaDevices.getUserMedia(constraints).toDart);

  static void _release(web.MediaStream? stream) {
    if (stream == null) return;
    for (final track in stream.getTracks().toDart) {
      track.stop();
    }
  }

  static String _supportedMimeType() {
    for (final candidate in _preferredMimeTypes) {
      if (web.MediaRecorder.isTypeSupported(candidate)) return candidate;
    }
    return 'video/webm';
  }
}
