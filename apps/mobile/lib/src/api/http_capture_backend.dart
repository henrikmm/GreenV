import 'dart:convert';
import 'dart:io';

import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

final class HttpCaptureBackend implements CaptureBackend {
  HttpCaptureBackend(
    this.baseUri, {
    required String bearerToken,
    HttpClient? client,
  }) : _authorizationHeader = _bearerAuthorization(bearerToken),
       _client = client ?? HttpClient();

  final Uri baseUri;
  final String _authorizationHeader;
  final HttpClient _client;

  @override
  Future<void> ensureSession(QueuedSession session) async {
    final response = await _json('POST', '/v2/capture-sessions', {
      'sessionId': session.sessionId,
      'deviceId': session.deviceId,
      'startedAt': session.startedAtUtc.toIso8601String(),
    });
    _expect(response, {HttpStatus.created});
  }

  @override
  Future<void> uploadSegment(QueuedSegment segment) async {
    await _upload(
      segment,
      'video',
      'video/mp4',
      segment.videoPath,
      segment.videoSha256,
    );
    await _upload(
      segment,
      'telemetry',
      ContentType.json.mimeType,
      segment.telemetryPath,
      segment.telemetrySha256,
    );
    final completed = await _request(
      'POST',
      '/v2/capture-sessions/${segment.sessionId}/segments/${segment.segmentIndex}/complete',
    );
    _expect(completed, {HttpStatus.accepted});
  }

  @override
  Future<String> segmentState(QueuedSegment segment) async {
    final response = await _request(
      'GET',
      '/v2/capture-sessions/${segment.sessionId}/segments/${segment.segmentIndex}',
    );
    _expect(response, {HttpStatus.ok});
    return (jsonDecode(response.body)! as Map<String, Object?>)['state']!
        as String;
  }

  @override
  Future<void> completeSession(QueuedSession session) async {
    final response = await _json(
      'POST',
      '/v2/capture-sessions/${session.sessionId}/complete',
      {
        'lastSegmentIndex': session.lastSegmentIndex,
        'endedAt': session.endedAtUtc!.toIso8601String(),
      },
    );
    _expect(response, {HttpStatus.accepted});
  }

  Future<void> _upload(
    QueuedSegment segment,
    String objectName,
    String contentType,
    String path,
    String sha256,
  ) async {
    final file = File(path);
    final request = await _client.openUrl(
      'PUT',
      baseUri.resolve(
        '/v2/capture-sessions/${segment.sessionId}/segments/${segment.segmentIndex}/$objectName',
      ),
    );
    _authorize(request);
    request.headers
      ..contentType = ContentType.parse(contentType)
      ..set('X-Idempotency-Key', segment.idempotencyKey)
      ..set('X-Content-SHA256', sha256)
      ..set('X-Captured-At', segment.capturedAtUtc.toIso8601String())
      ..set('X-Duration-Millis', segment.durationMillis.toString())
      ..contentLength = await file.length();
    await request.addStream(file.openRead());
    final response = await request.close();
    final body = await utf8.decoder.bind(response).join();
    _expect(_ApiResponse(response.statusCode, body), {HttpStatus.ok});
  }

  Future<_ApiResponse> _json(
    String method,
    String path,
    Map<String, Object?> body,
  ) async {
    final encoded = utf8.encode(jsonEncode(body));
    final request = await _client.openUrl(method, baseUri.resolve(path));
    _authorize(request);
    request.headers.contentType = ContentType.json;
    request.contentLength = encoded.length;
    request.add(encoded);
    final response = await request.close();
    return _ApiResponse(
      response.statusCode,
      await utf8.decoder.bind(response).join(),
    );
  }

  Future<_ApiResponse> _request(String method, String path) async {
    final request = await _client.openUrl(method, baseUri.resolve(path));
    _authorize(request);
    final response = await request.close();
    return _ApiResponse(
      response.statusCode,
      await utf8.decoder.bind(response).join(),
    );
  }

  static void _expect(_ApiResponse response, Set<int> accepted) {
    if (!accepted.contains(response.statusCode)) {
      throw CaptureBackendException(response.statusCode, response.body);
    }
  }

  void _authorize(HttpClientRequest request) {
    request.headers.set(HttpHeaders.authorizationHeader, _authorizationHeader);
  }

  static String _bearerAuthorization(String token) {
    final normalized = token.trim();
    if (normalized.length < 32) {
      throw ArgumentError('bearerToken must contain at least 32 characters');
    }
    return 'Bearer $normalized';
  }
}

final class CaptureBackendException implements Exception {
  const CaptureBackendException(this.statusCode, this.body);

  final int statusCode;
  final String body;

  @override
  String toString() => 'capture backend returned HTTP $statusCode';
}

final class _ApiResponse {
  const _ApiResponse(this.statusCode, this.body);

  final int statusCode;
  final String body;
}
