import 'dart:async';

import 'package:geolocator/geolocator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/capture/relative_orientation.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:sensors_plus/sensors_plus.dart';

final class PhoneTelemetryCollector implements TelemetryCollector {
  PhoneTelemetryCollector(this._monotonicNanos);

  final int Function() _monotonicNanos;
  final List<Map<String, Object?>> _locations = [];
  final List<Map<String, Object?>> _motions = [];
  final List<StreamSubscription<dynamic>> _subscriptions = [];
  AccelerometerEvent? _gravity;
  UserAccelerometerEvent? _userAcceleration;
  Position? _previousPosition;
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

  @override
  Future<void> beginSegment({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int monotonicStartNanos,
  }) async {
    await _cancelSubscriptions();
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
    await _startLocation();
  }

  Future<void> _startLocation() async {
    if (!await Geolocator.isLocationServiceEnabled()) return;
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.denied ||
        permission == LocationPermission.deniedForever) {
      return;
    }
    _subscriptions.add(
      Geolocator.getPositionStream(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.bestForNavigation,
          distanceFilter: 0,
        ),
      ).listen(_recordLocation, onError: (_) {}),
    );
  }

  void _recordLocation(Position position) {
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
    await _cancelSubscriptions();
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

  Future<void> _cancelSubscriptions() async {
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    _subscriptions.clear();
  }

  @override
  Future<void> dispose() => _cancelSubscriptions();
}
