import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/api/http_capture_backend.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

void main() {
  const token = 'greenv-test-only-bearer-token-000000000000';

  test('rejects a missing or short API token before sending data', () {
    expect(
      () => HttpCaptureBackend(
        Uri.parse('https://api.example.com'),
        bearerToken: 'short',
      ),
      throwsArgumentError,
    );
  });

  test('sends the Bearer token on JSON API requests', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final backend = HttpCaptureBackend(
      Uri.parse('http://${server.address.host}:${server.port}'),
      bearerToken: token,
    );
    final session = QueuedSession(
      sessionId: '01991a35-6a00-7000-8000-000000000001',
      deviceId: 'test-phone',
      startedAtUtc: DateTime.utc(2026, 8, 30),
    );

    final response = () async {
      final request = await server.first;
      expect(
        request.headers.value(HttpHeaders.authorizationHeader),
        'Bearer $token',
      );
      await request.drain<void>();
      request.response
        ..statusCode = HttpStatus.created
        ..headers.contentType = ContentType.json
        ..write('{"sessionId":"${session.sessionId}"}');
      await request.response.close();
    }();

    await backend.ensureSession(session);
    await response;
    await server.close(force: true);
  });

  test('sends the Bearer token on both files and segment completion', () async {
    final directory = await Directory.systemTemp.createTemp(
      'greenv-authorized-upload-',
    );
    final video = File('${directory.path}${Platform.pathSeparator}source.mp4');
    final telemetry = File(
      '${directory.path}${Platform.pathSeparator}telemetry.json',
    );
    await video.writeAsBytes([1, 2, 3]);
    await telemetry.writeAsString('{}');
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final backend = HttpCaptureBackend(
      Uri.parse('http://${server.address.host}:${server.port}'),
      bearerToken: token,
    );
    final segment = QueuedSegment(
      sessionId: '01991a35-6a00-7000-8000-000000000001',
      segmentIndex: 0,
      idempotencyKey: 'test-phone:session:0',
      capturedAtUtc: DateTime.utc(2026, 8, 30),
      durationMillis: 10000,
      videoPath: video.path,
      videoSha256: List.filled(64, '0').join(),
      telemetryPath: telemetry.path,
      telemetrySha256: List.filled(64, '1').join(),
    );
    final receivedPaths = <String>[];

    final responses = () async {
      await for (final request in server) {
        expect(
          request.headers.value(HttpHeaders.authorizationHeader),
          'Bearer $token',
        );
        receivedPaths.add(request.uri.path);
        await request.drain<void>();
        request.response.statusCode = request.uri.path.endsWith('/complete')
            ? HttpStatus.accepted
            : HttpStatus.ok;
        await request.response.close();
        if (receivedPaths.length == 3) break;
      }
    }();

    try {
      await backend.uploadSegment(segment);
      await responses;
      expect(receivedPaths, [
        '/v2/capture-sessions/${segment.sessionId}/segments/0/video',
        '/v2/capture-sessions/${segment.sessionId}/segments/0/telemetry',
        '/v2/capture-sessions/${segment.sessionId}/segments/0/complete',
      ]);
    } finally {
      await server.close(force: true);
      await directory.delete(recursive: true);
    }
  });
}
