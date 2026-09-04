import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/api/http_capture_backend.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  final capturedAt = DateTime.utc(2026, 9, 1, 12);
  final session = QueuedSession(
    sessionId: '2c6c2e0c-7f2c-4a2b-9d0f-5a4b1c2d3e4f',
    deviceId: 'greenv-test-device',
    startedAtUtc: capturedAt,
    endedAtUtc: capturedAt.add(const Duration(seconds: 10)),
    lastSegmentIndex: 0,
  );
  final segment = QueuedSegment(
    sessionId: session.sessionId,
    segmentIndex: 0,
    idempotencyKey: '${session.sessionId}:0',
    capturedAtUtc: capturedAt,
    durationMillis: 10000,
    videoPath: 'memory://video',
    videoSha256: 'a' * 64,
    telemetryPath: 'memory://telemetry',
    telemetrySha256: 'b' * 64,
    videoContentType: 'video/webm',
  );

  test('sends the configured bearer token on every route', () async {
    final requests = <http.Request>[];
    final backend = _backend(requests, (request) {
      if (request.method == 'POST' && request.url.path.endsWith('/complete')) {
        return http.Response('', 202);
      }
      if (request.method == 'POST') return http.Response('{}', 201);
      if (request.method == 'PUT') return http.Response('{}', 200);
      return http.Response('{"state":"ready"}', 200);
    });

    await backend.ensureSession(session);
    await backend.uploadSegment(segment);
    await backend.segmentState(segment);
    await backend.completeSession(session);

    expect(requests, hasLength(6));
    for (final request in requests) {
      expect(
        request.headers['authorization'],
        'Bearer greenv-test-token',
        reason: '${request.method} ${request.url.path} was sent unauthenticated',
      );
    }
  });

  test('omits the header when no token was compiled in', () async {
    final requests = <http.Request>[];
    final backend = _backend(
      requests,
      (_) => http.Response('{}', 201),
      token: '',
    );

    await backend.ensureSession(session);

    expect(requests.single.headers.containsKey('authorization'), isFalse);
  });

  test('uploads the recorded container with its segment metadata', () async {
    final requests = <http.Request>[];
    final backend = _backend(requests, (request) {
      if (request.method == 'PUT') return http.Response('{}', 200);
      return http.Response('', 202);
    });

    await backend.uploadSegment(segment);

    final video = requests.first;
    expect(video.method, 'PUT');
    expect(video.url.path, endsWith('/segments/0/video'));
    expect(video.headers['content-type'], 'video/webm');
    expect(video.headers['x-idempotency-key'], '${session.sessionId}:0');
    expect(video.headers['x-content-sha256'], 'a' * 64);
    expect(video.headers['x-captured-at'], capturedAt.toIso8601String());
    expect(video.headers['x-duration-millis'], '10000');
    expect(video.bodyBytes, utf8.encode('video-bytes'));

    final telemetry = requests[1];
    expect(telemetry.headers['content-type'], 'application/json');
    expect(telemetry.headers['x-content-sha256'], 'b' * 64);
  });

  test('reports the status code when the API refuses the token', () async {
    final backend = _backend(
      <http.Request>[],
      (_) => http.Response('{"title":"unauthorized"}', 401),
    );

    await expectLater(
      backend.ensureSession(session),
      throwsA(
        isA<CaptureBackendException>().having(
          (error) => error.statusCode,
          'statusCode',
          401,
        ),
      ),
    );
  });

  test('retries an upload once after a 401 and rebuilds the request', () async {
    // _SegmentUpload finalizes a single-subscription stream, so a retry that re-sent the same
    // request instance would throw instead of retrying. This is the guard on that.
    final tokens = <String?>[];
    var videoAttempts = 0;
    final backend = HttpCaptureBackend(
      baseUri: Uri.parse('https://api.example/'),
      content: _FakeContent(),
      auth: _RotatingAuth(),
      client: MockClient((request) async {
        if (request.method == 'PUT' && request.url.path.endsWith('/video')) {
          videoAttempts++;
          tokens.add(request.headers['authorization']);
          return http.Response('{}', videoAttempts == 1 ? 401 : 200);
        }
        if (request.method == 'PUT') return http.Response('{}', 200);
        return http.Response('', 202);
      }),
    );

    await backend.uploadSegment(segment);

    expect(videoAttempts, 2);
    expect(tokens, ['Bearer tok-1', 'Bearer tok-2']);
  });

  test('does not retry when the provider cannot get a new token', () async {
    var attempts = 0;
    final backend = HttpCaptureBackend(
      baseUri: Uri.parse('https://api.example/'),
      content: _FakeContent(),
      auth: _FailingAuth(),
      client: MockClient((_) async {
        attempts++;
        return http.Response('{"title":"unauthorized"}', 401);
      }),
    );

    await expectLater(
      backend.ensureSession(session),
      throwsA(isA<CaptureBackendException>()),
    );
    expect(attempts, 1);
  });
}

HttpCaptureBackend _backend(
  List<http.Request> requests,
  http.Response Function(http.Request request) respond, {
  String token = 'greenv-test-token',
}) => HttpCaptureBackend(
  baseUri: Uri.parse('https://api.example/'),
  content: _FakeContent(),
  bearerToken: token,
  client: MockClient((request) async {
    requests.add(request);
    return respond(request);
  }),
);

final class _FakeContent implements SegmentContentStore {
  static final Map<String, Uint8List> _bytes = {
    'memory://video': Uint8List.fromList(utf8.encode('video-bytes')),
    'memory://telemetry': Uint8List.fromList(utf8.encode('{"schemaVersion":1}')),
  };

  @override
  Future<int> length(String reference) async => _bytes[reference]!.length;

  @override
  Stream<List<int>> read(String reference) =>
      Stream<List<int>>.value(_bytes[reference]!);
}

/// Hands out a new token on every refresh, so a retry is visibly a different credential.
final class _RotatingAuth implements AuthTokenProvider {
  int _generation = 1;

  @override
  Future<String> accessToken() async => 'tok-$_generation';

  @override
  Future<bool> refresh() async {
    _generation++;
    return true;
  }
}

final class _FailingAuth implements AuthTokenProvider {
  @override
  Future<String> accessToken() async => 'stale';

  @override
  Future<bool> refresh() async => false;
}
