import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geolocator/geolocator.dart';
import 'package:greenv_capture/src/capture/phone_telemetry_collector.dart';

import '../support/capture_fakes.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _StubGeolocator platform;

  // sensors_plus sets its sampling period through a method channel and never awaits the reply, so
  // without a handler the refusal escapes the collector's own guard and lands on the zone after
  // the test has finished. Answering it keeps the failure that is being tested the only one.
  const sensors = MethodChannel('dev.fluttercommunity.plus/sensors/method');

  setUp(() {
    platform = _StubGeolocator();
    GeolocatorPlatform.instance = platform;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(sensors, (call) async => null);
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(sensors, null);
    return platform.fixes.close();
  });

  test(
    'asks for a position on every tick while the stream says nothing',
    () async {
      final scheduler = FakeScheduler();
      final probe = _CountingProbe();
      final collector = _collector(probe: probe.next, poller: scheduler);
      addTearDown(collector.dispose);

      await _begin(collector);
      expect(scheduler.duration, const Duration(seconds: 1));

      for (var tick = 0; tick < 3; tick++) {
        await scheduler.fire();
      }
      final document = await collector.finishSegment();

      expect(probe.calls, 3);
      expect(document.json['locations'], hasLength(3));
      expect(
        scheduler.duration,
        isNull,
        reason: 'the poll stops with the segment it was collecting for',
      );
    },
  );

  test('records the same reading once when the stream and the poll both deliver it', () async {
    final scheduler = FakeScheduler();
    final fix = _position(DateTime.utc(2026, 9, 8, 12, 0, 1));
    final collector = _collector(probe: () async => fix, poller: scheduler);
    addTearDown(collector.dispose);

    await _begin(collector);
    platform.fixes.add(fix);
    await Future<void>.delayed(Duration.zero);
    await scheduler.fire();
    await scheduler.fire();
    final document = await collector.finishSegment();

    expect(
      document.json['locations'],
      hasLength(1),
      reason: 'a fix is the same fix whichever way it arrived',
    );
  });
}

PhoneTelemetryCollector _collector({
  required Future<Position> Function() probe,
  required FakeScheduler poller,
}) {
  var nanos = 0;
  return PhoneTelemetryCollector(
    () => nanos += 1000000000,
    positionProbe: probe,
    poller: poller,
  );
}

Future<void> _begin(PhoneTelemetryCollector collector) =>
    collector.beginSegment(
      sessionId: 'session-1',
      segmentIndex: 0,
      capturedAtUtc: DateTime.utc(2026, 9, 8, 12),
      monotonicStartNanos: 0,
    );

/// A probe that answers with a later fix every time, the way a receiver in motion would.
final class _CountingProbe {
  int calls = 0;

  Future<Position> next() async {
    calls += 1;
    return _position(DateTime.utc(2026, 9, 8, 12, 0, calls));
  }
}

Position _position(DateTime timestamp) => Position(
  latitude: -23.5,
  longitude: -46.6,
  timestamp: timestamp,
  accuracy: 4.2,
  altitude: 760,
  altitudeAccuracy: 3,
  heading: 180,
  headingAccuracy: 2,
  speed: 12.5,
  speedAccuracy: 0.5,
);

/// Grants location and hands out a stream the test drives, so the collector's own permission gate
/// runs instead of being stubbed out around.
final class _StubGeolocator extends GeolocatorPlatform {
  final StreamController<Position> fixes =
      StreamController<Position>.broadcast();

  @override
  Future<bool> isLocationServiceEnabled() async => true;

  @override
  Future<LocationPermission> checkPermission() async =>
      LocationPermission.whileInUse;

  @override
  Stream<Position> getPositionStream({LocationSettings? locationSettings}) =>
      fixes.stream;
}
