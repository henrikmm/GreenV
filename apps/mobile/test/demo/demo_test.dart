import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/demo/demo_settings.dart';
import 'package:greenv_capture/src/demo/gateway_client.dart';
import 'package:greenv_capture/src/demo/orbit_tracker.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  group('DemoSettings', () {
    test('defaults are the specification\'s: 1080p, 30 fps, lock on, stabilisation off', () {
      const s = DemoSettings();
      expect(s.resolution, DemoResolution.p1080);
      expect(s.fps, 30);
      expect(s.lockFocusAndExposure, isTrue);
      expect(s.stabilization, isFalse);
      expect(s.maxSeconds, 15);
      expect(s.samplingFps, 10);
      expect(s.maxFrames, 112);
      expect(s.samplingJson(), {'fps': 10, 'maxFrames': 112});
    });

    test('round-trips through JSON and refuses values outside the choices', () {
      final s = const DemoSettings().copyWith(
        gatewayUrl: 'http://192.168.0.10:8790',
        pairingCode: '123456',
        resolution: DemoResolution.p2160,
        fps: 60,
        torch: true,
        exposureOffset: 1.5,
        samplingFps: 6,
        maxFrames: 256,
      );
      final back = DemoSettings.fromJson(jsonDecode(jsonEncode(s.toJson())) as Map<String, Object?>);
      expect(back.toJson(), s.toJson());

      final odd = DemoSettings.fromJson({'fps': 240, 'maxSeconds': 3, 'resolution': 'p8k', 'exposureOffset': 40, 'maxFrames': 999});
      expect(odd.maxFrames, 112);
      expect(odd.fps, 30);
      expect(odd.maxSeconds, 15);
      expect(odd.resolution, DemoResolution.p1080);
      expect(odd.exposureOffset, 4.0);
    });

    test('the capture payload never carries the gateway address or code', () {
      final json = const DemoSettings(gatewayUrl: 'http://x', pairingCode: '1').captureJson();
      expect(json.containsKey('gatewayUrl'), isFalse);
      expect(json.containsKey('pairingCode'), isFalse);
      expect(json['resolution'], '1080p');
    });

    test('only format changes reopen the camera', () {
      const s = DemoSettings();
      expect(s.needsReopen(s.copyWith(torch: true)), isFalse);
      expect(s.needsReopen(s.copyWith(exposureOffset: 1)), isFalse);
      expect(s.needsReopen(s.copyWith(fps: 60)), isTrue);
      expect(s.needsReopen(s.copyWith(stabilization: true)), isTrue);
    });

    test('a corrupt settings file falls back to defaults', () async {
      final dir = await Directory.systemTemp.createTemp('demo-settings');
      final file = File('${dir.path}/s.json')..writeAsStringSync('{not json');
      final loaded = await DemoSettingsStore(file).load();
      expect(loaded.toJson(), const DemoSettings().toJson());
      await DemoSettingsStore(file).save(const DemoSettings(fps: 60));
      expect((await DemoSettingsStore(file).load()).fps, 60);
      await dir.delete(recursive: true);
    });
  });

  group('OrbitTracker', () {
    // Phone upright in portrait: gravity along +y.
    void settle(OrbitTracker t) {
      for (var i = 0; i < 50; i++) {
        t.addAccelerometer(0, 9.81, 0);
      }
    }

    test('integrates a turn about gravity into degrees', () {
      final t = OrbitTracker();
      settle(t);
      // 90° over 9 s at 100 Hz, about the vertical (the phone's y axis when upright).
      const rate = (math.pi / 2) / 9;
      for (var i = 0; i < 900; i++) {
        t.addGyroscope(0, rate, 0, 0.01);
      }
      expect(t.degrees, closeTo(90, 1));
      expect(t.tooFast, isFalse);
      expect(t.tooFastEvents, 0);
    });

    test('a nod about the horizontal does not count as walking around', () {
      final t = OrbitTracker();
      settle(t);
      for (var i = 0; i < 300; i++) {
        t.addGyroscope(0.5, 0, 0, 0.01);
      }
      expect(t.degrees, lessThan(1));
    });

    test('flags a fast swing once, not once per sample', () {
      final t = OrbitTracker();
      settle(t);
      for (var i = 0; i < 100; i++) {
        t.addGyroscope(0, 2.0, 0, 0.01);
      }
      expect(t.tooFast, isTrue);
      expect(t.tooFastEvents, 1);
      expect(t.peakDegreesPerSecond, greaterThan(100));
    });

    test('ignores gaps long enough to be a stall', () {
      final t = OrbitTracker();
      settle(t);
      t.addGyroscope(0, 1, 0, 2.0);
      expect(t.degrees, 0);
    });
  });

  group('GatewayClient', () {
    final base = Uri.parse('http://gateway.test:8790');

    test('health reports a wrong code as reachable but not ready', () async {
      final client = GatewayClient(
        baseUri: base,
        pairingCode: 'nope',
        client: MockClient((r) async => http.Response(jsonEncode({'contract': 1, 'authorised': false}), 200)),
      );
      final h = await client.health();
      expect(h.reachable, isTrue);
      expect(h.ready, isFalse);
    });

    test('health turns a refused connection into an answer, not an exception', () async {
      final client = GatewayClient(
        baseUri: base,
        pairingCode: '1',
        client: MockClient((r) async => throw const SocketException('refused')),
      );
      final h = await client.health();
      expect(h.reachable, isFalse);
      expect(h.problem, isNotNull);
    });

    test('announces, streams the video with its length, and reads phases back', () async {
      final dir = await Directory.systemTemp.createTemp('demo-upload');
      final video = File('${dir.path}/v.mp4')..writeAsBytesSync(List<int>.filled(300000, 3));
      var receivedBytes = 0;
      String? contentLength;
      String? auth;
      final client = GatewayClient(
        baseUri: base,
        pairingCode: '123456',
        client: MockClient.streaming((request, body) async {
          auth = request.headers['authorization'];
          if (request.method == 'POST' && request.url.path == '/v1/captures') {
            final meta = jsonDecode(await body.bytesToString()) as Map<String, Object?>;
            expect(meta['schema'], 'verge-phone-capture/1');
            return http.StreamedResponse(Stream.value(utf8.encode(jsonEncode({'id': 'cap-1'}))), 201);
          }
          if (request.method == 'PUT' && request.url.path == '/v1/captures/cap-1/video') {
            contentLength = '${request.contentLength}';
            await for (final chunk in body) {
              receivedBytes += chunk.length;
            }
            return http.StreamedResponse(Stream.value(utf8.encode(jsonEncode({'id': 'cap-1'}))), 200);
          }
          if (request.method == 'GET' && request.url.path == '/v1/captures/cap-1') {
            return http.StreamedResponse(
              Stream.value(utf8.encode(jsonEncode({
                'id': 'cap-1',
                'state': 'received',
                'phases': [
                  {'phase': 'created', 'tMs': 0},
                  {'phase': 'received', 'tMs': 812, 'detail': {'bytes': 300000}},
                ],
              }))),
              200,
            );
          }
          return http.StreamedResponse(const Stream.empty(), 404);
        }),
      );

      final id = await client.announce({'schema': 'verge-phone-capture/1', 'capturedAt': '2026-10-17T20:00:00Z'});
      final progress = <int>[];
      await client.uploadVideo(id, video, onProgress: (sent, total) => progress.add(sent));
      final status = await client.status(id);

      expect(auth, 'Bearer 123456');
      expect(receivedBytes, 300000);
      expect(contentLength, '300000');
      expect(progress.last, 300000);
      expect(status.state, 'received');
      expect(status.finished, isFalse);
      expect(status.phases.last.tMs, 812);
      await dir.delete(recursive: true);
    });

    test('a refused upload surfaces the gateway\'s own reason', () async {
      final dir = await Directory.systemTemp.createTemp('demo-upload');
      final video = File('${dir.path}/v.mp4')..writeAsBytesSync([1, 2, 3]);
      final client = GatewayClient(
        baseUri: base,
        pairingCode: '1',
        client: MockClient.streaming((request, body) async {
          await body.drain<void>();
          return http.StreamedResponse(Stream.value(utf8.encode(jsonEncode({'detail': 'video already received'}))), 409);
        }),
      );
      await expectLater(
        client.uploadVideo('cap-1', video),
        throwsA(isA<GatewayException>().having((e) => e.statusCode, 'status', 409).having((e) => e.message, 'message', contains('already'))),
      );
      await dir.delete(recursive: true);
    });

    test('a base with a path is the prefix, slash or not; a bare host is the Mac\'s /v1/', () {
      String norm(String s) => GatewayClient.normaliseBase(Uri.parse(s)).toString();
      expect(norm('https://greenvapi.matomomitsu.com/v1/demo/'), 'https://greenvapi.matomomitsu.com/v1/demo/');
      expect(norm('https://greenvapi.matomomitsu.com/v1/demo'), 'https://greenvapi.matomomitsu.com/v1/demo/');
      expect(norm('http://192.168.0.10:8790'), 'http://192.168.0.10:8790/v1/');
      expect(norm('http://192.168.0.10:8790/'), 'http://192.168.0.10:8790/v1/');
      expect(norm('https://host/v1/demo/?x=1#y'), 'https://host/v1/demo/');
      expect(GatewayClient.isMacBase(Uri.parse('http://192.168.0.10:8790')), isTrue);
      expect(GatewayClient.isMacBase(Uri.parse('https://greenvapi.matomomitsu.com/v1/demo')), isFalse);
    });

    test('every call stays under a prefixed base', () async {
      final dir = await Directory.systemTemp.createTemp('demo-upload');
      final video = File('${dir.path}/v.mp4')..writeAsBytesSync([1, 2, 3]);
      final seen = <String>[];
      final client = GatewayClient(
        baseUri: Uri.parse('https://greenvapi.matomomitsu.com/v1/demo'),
        pairingCode: '1',
        client: MockClient.streaming((request, body) async {
          await body.drain<void>();
          seen.add('${request.method} ${request.url}');
          final json = switch (request.url.path) {
            '/v1/demo/health' => {'contract': 1, 'authorised': true},
            '/v1/demo/captures/cap-1' => {'id': 'cap-1', 'state': 'fetched', 'phases': <Object?>[]},
            _ => {'id': 'cap-1'},
          };
          final code = request.method == 'POST' ? 201 : 200;
          return http.StreamedResponse(Stream.value(utf8.encode(jsonEncode(json))), code);
        }),
      );

      expect((await client.health()).ready, isTrue);
      final id = await client.announce({'schema': 'verge-phone-capture/1'});
      await client.uploadVideo(id, video);
      expect((await client.status(id)).finished, isTrue);

      expect(seen, [
        'GET https://greenvapi.matomomitsu.com/v1/demo/health',
        'POST https://greenvapi.matomomitsu.com/v1/demo/captures',
        'PUT https://greenvapi.matomomitsu.com/v1/demo/captures/cap-1/video',
        'GET https://greenvapi.matomomitsu.com/v1/demo/captures/cap-1',
      ]);
      await dir.delete(recursive: true);
    });

    test('a base with no path still reaches the Mac gateway under /v1/', () async {
      final seen = <String>[];
      final client = GatewayClient(
        baseUri: base,
        pairingCode: '1',
        client: MockClient((request) async {
          seen.add(request.url.toString());
          return http.Response(jsonEncode({'id': 'cap-1', 'state': 'received', 'contract': 1, 'authorised': true}), 200);
        }),
      );
      await client.health();
      await client.status('cap-1');
      expect(seen, ['http://gateway.test:8790/v1/health', 'http://gateway.test:8790/v1/captures/cap-1']);
    });

    test('a video over the limit is refused on the phone, before a byte is sent', () async {
      final dir = await Directory.systemTemp.createTemp('demo-upload');
      final video = File('${dir.path}/v.mp4')..writeAsBytesSync(List<int>.filled(1001, 0));
      var requests = 0;
      final client = GatewayClient(
        baseUri: base,
        pairingCode: '1',
        maxBytes: 1000,
        client: MockClient.streaming((request, body) async {
          requests++;
          await body.drain<void>();
          return http.StreamedResponse(const Stream.empty(), 200);
        }),
      );
      await expectLater(
        client.uploadVideo('cap-1', video),
        throwsA(isA<GatewayException>().having((e) => e.statusCode, 'status', 413).having((e) => e.message, 'message', contains('limite'))),
      );
      expect(requests, 0);
      await dir.delete(recursive: true);
    });

    test('the limit is 95 MiB, and the refusal names both sizes in Portuguese', () {
      expect(maxVideoBytes, 95 * 1024 * 1024);
      expect(GatewayClient(baseUri: base, pairingCode: '1').maxBytes, maxVideoBytes);
      expect(videoSizeProblem(maxVideoBytes), isNull);
      final message = videoSizeProblem(120000000);
      expect(message, contains('120,0 MB'));
      expect(message, contains('99,6 MB'));
      expect(message, contains('Grave por menos tempo'));
    });
  });

  test('without a dart-define the demo talks to the cloud API\'s demo prefix', () {
    expect(DemoSettings.cloudGatewayUrl, 'https://greenvapi.matomomitsu.com/v1/demo/');
    expect(const DemoSettings().gatewayUrl, DemoSettings.cloudGatewayUrl);
  });
}
