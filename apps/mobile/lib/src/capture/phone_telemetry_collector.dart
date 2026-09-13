import 'dart:async';

import 'package:geolocator/geolocator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/capture/capture_runtime.dart';
import 'package:greenv_capture/src/capture/relative_orientation.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:sensors_plus/sensors_plus.dart';

const _streamSettings = LocationSettings(
  accuracy: LocationAccuracy.bestForNavigation,
  distanceFilter: 0,
);

/// The poll carries a deadline the stream must not have: the next poll is scheduled from the
/// reply, so a request that never returns would end polling for the rest of the segment.
const _pollSettings = LocationSettings(
  accuracy: LocationAccuracy.bestForNavigation,
  distanceFilter: 0,
  timeLimit: Duration(seconds: 5),
);

Future<Position> _askThePlatform() =>
    Geolocator.getCurrentPosition(locationSettings: _pollSettings);

final class PhoneTelemetryCollector implements TelemetryCollector {
  /// [positionProbe] and [poller] are seams for tests; the defaults are the real platform and a
  /// real timer, so neither call site has to know they exist.
  PhoneTelemetryCollector(
    this._monotonicNanos, {
    Future<Position> Function()? positionProbe,
    SegmentScheduler? poller,
    this.pollInterval = const Duration(seconds: 1),
  }) : _positionProbe = positionProbe ?? _askThePlatform,
       _poller = poller ?? TimerSegmentScheduler();

  final int Function() _monotonicNanos;
  final Future<Position> Function() _positionProbe;
  final SegmentScheduler _poller;
  final Duration pollInterval;
  final List<Map<String, Object?>> _locations = [];
  final List<Map<String, Object?>> _motions = [];
  final List<StreamSubscription<dynamic>> _subscriptions = [];
  /// Kept apart from the rest because it must outlive a segment; see [beginSegment].
  StreamSubscription<Position>? _locationSubscription;
  AccelerometerEvent? _gravity;
  UserAccelerometerEvent? _userAcceleration;
  Position? _previousPosition;
  DateTime? _lastFixAt;
  bool _polling = false;
  final RelativeOrientation _orientation = RelativeOrientation();
  int? _lastMotionNanos;
  double _sessionDistanceMeters = 0;
  String? _sessionId;
  int? _segmentIndex;
  DateTime? _capturedAtUtc;
  int? _monotonicStartNanos;

  @override
  double? latestSpeedMetersPerSecond;

  @override
  double? latestHorizontalAccuracyMeters;

  /// Starts a segment without disturbing the GNSS receiver.
  ///
  /// The location subscription used to be torn down and rebuilt here with everything else, once
  /// every ten seconds. A receiver does not resume where it left off: a new subscription means a
  /// new location request, and the platform takes seconds to deliver its first fix. The captures
  /// on record show the cost — two to four distinct fixes in a ten-second segment where the
  /// stream is asked for one a second — and the measurement downstream reads the gap as a
  /// vehicle that barely moved.
  ///
  /// Motion sensors are restarted freely: they deliver on the first sample and have no
  /// acquisition to lose.
  @override
  Future<void> beginSegment({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int monotonicStartNanos,
  }) async {
    await _cancelSegmentSubscriptions();
    _locations.clear();
    _motions.clear();
    if (_sessionId != sessionId) {
      _sessionDistanceMeters = 0;
      _previousPosition = null;
    }
    _sessionId = sessionId;
    _segmentIndex = segmentIndex;
    _capturedAtUtc = capturedAtUtc;
    _monotonicStartNanos = monotonicStartNanos;
    _orientation.reset();
    _lastMotionNanos = null;
    _lastFixAt = null;

    _startMotion();
    // Only the first segment of a session opens the stream; the rest inherit a warm one.
    await _startLocation();
  }

  /// A workstation browser exposes no inertial sensors, and asking for one there throws instead of
  /// returning an empty stream. Motion is optional evidence: a segment without it still carries
  /// video and GNSS, so the failure must not end the capture.
  void _startMotion() {
    try {
      _subscriptions
        ..add(
          accelerometerEventStream(samplingPeriod: SensorInterval.gameInterval)
              .listen((event) => _gravity = event, onError: (_) {}),
        )
        ..add(
          userAccelerometerEventStream(
            samplingPeriod: SensorInterval.gameInterval,
          ).listen((event) => _userAcceleration = event, onError: (_) {}),
        )
        ..add(
          gyroscopeEventStream(samplingPeriod: SensorInterval.gameInterval)
              .listen(_recordMotion, onError: (_) {}),
        );
    } on Object {
      // No motion samples for this segment.
    }
  }

  Future<void> _startLocation() async {
    try {
      // Already running from an earlier segment: the permission checks below cost a round trip
      // each and the answer cannot have changed mid-session.
      if (_locationSubscription != null) {
        _polling = true;
        _schedulePoll();
        return;
      }
      if (!await Geolocator.isLocationServiceEnabled()) return;
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        return;
      }
      _locationSubscription ??= Geolocator.getPositionStream(
        locationSettings: _streamSettings,
      ).listen(_recordLocation, onError: (_) {});
      // The stream is `watchPosition` on the web, and a network-derived provider fires it only
      // when the position CHANGES: a workstation delivered two fixes 19 ms apart and then nothing
      // for the remaining 9.9 s of the segment, so 61% of its frames carried no position at all.
      // Asking outright once a second gets an answer whatever the provider counts as a change. A
      // phone's GNSS stream already delivers at that rate and the poll returns the same reading,
      // which `_recordLocation` drops.
      _polling = true;
      _schedulePoll();
    } on Object {
      // Capture continues without GNSS; affected frames carry unavailable location evidence.
    }
  }

  void _schedulePoll() => _poller.schedule(pollInterval, _poll);

  Future<void> _poll() async {
    try {
      final position = await _positionProbe();
      // The segment can end while the platform is still thinking, and a fix that arrives after it
      // belongs to no segment.
      if (_polling) _recordLocation(position);
    } on Object {
      // A refused or timed-out probe is not fatal: the stream may still be delivering.
    }
    if (_polling) _schedulePoll();
  }

  void _recordLocation(Position position) {
    // The stream and the poll both deliver, and on a stationary device they deliver the same
    // reading twice. A fix is identified by when the receiver produced it, not by when it reached
    // here, so a repeat is dropped rather than inflating the segment's fix count.
    if (_lastFixAt != null && !position.timestamp.isAfter(_lastFixAt!)) return;
    _lastFixAt = position.timestamp;
    final previous = _previousPosition;
    if (previous != null) {
      _sessionDistanceMeters += Geolocator.distanceBetween(
        previous.latitude,
        previous.longitude,
        position.latitude,
        position.longitude,
      );
    }
    _previousPosition = position;
    latestSpeedMetersPerSecond = position.speed;
    latestHorizontalAccuracyMeters = position.accuracy;
    _locations.add({
      'monotonicNanos': _monotonicNanos(),
      'latitude': position.latitude,
      'longitude': position.longitude,
      'altitudeMeters': position.altitude,
      'horizontalAccuracyMeters': position.accuracy,
      'verticalAccuracyMeters': position.altitudeAccuracy,
      'speedMetersPerSecond': position.speed,
      'speedAccuracyMetersPerSecond': position.speedAccuracy,
      'courseDegrees': position.heading,
      'courseAccuracyDegrees': position.headingAccuracy,
      'distanceFromSessionStartMeters': _sessionDistanceMeters,
    });
  }

  void _recordMotion(GyroscopeEvent rotation) {
    final gravity = _gravity;
    final acceleration = _userAcceleration;
    final nowNanos = _monotonicNanos();
    final previousNanos = _lastMotionNanos;
    if (previousNanos != null) {
      _orientation.integrate(
        radiansPerSecondX: rotation.x,
        radiansPerSecondY: rotation.y,
        radiansPerSecondZ: rotation.z,
        elapsedSeconds: (nowNanos - previousNanos) / 1000000000,
      );
    }
    _lastMotionNanos = nowNanos;
    _motions.add({
      'monotonicNanos': nowNanos,
      'quaternionX': _orientation.x,
      'quaternionY': _orientation.y,
      'quaternionZ': _orientation.z,
      'quaternionW': _orientation.w,
      'gravityX': gravity?.x ?? 0.0,
      'gravityY': gravity?.y ?? 0.0,
      'gravityZ': gravity?.z ?? 0.0,
      'userAccelerationX': acceleration?.x ?? 0.0,
      'userAccelerationY': acceleration?.y ?? 0.0,
      'userAccelerationZ': acceleration?.z ?? 0.0,
      'rotationRateX': rotation.x,
      'rotationRateY': rotation.y,
      'rotationRateZ': rotation.z,
    });
  }

  @override
  Future<SegmentTelemetryDocument> finishSegment() async {
    // The receiver keeps running between segments on purpose: the next one starts with a fix
    // already in hand instead of waiting for acquisition all over again.
    await _cancelSegmentSubscriptions();
    return SegmentTelemetryDocument({
      'schemaVersion': 1,
      'sessionId': _sessionId,
      'segmentIndex': _segmentIndex,
      'capturedAtUtc': _capturedAtUtc!.toUtc().toIso8601String(),
      'monotonicStartNanos': _monotonicStartNanos,
      'frameClockSource': 'segment_anchor',
      'locations': List.unmodifiable(_locations),
      'motions': List.unmodifiable(_motions),
    });
  }

  /// Ends a segment. The GNSS subscription deliberately survives this.
  Future<void> _cancelSegmentSubscriptions() async {
    _polling = false;
    _poller.cancel();
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    _subscriptions.clear();
  }

  @override
  Future<void> dispose() async {
    await _cancelSegmentSubscriptions();
    await _locationSubscription?.cancel();
    _locationSubscription = null;
  }
}
