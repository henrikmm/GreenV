// ignore_for_file: prefer_initializing_formals

import 'dart:convert';

import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:http/http.dart' as http;

/// Talks to `/v2/capture-sessions`. It is built on `package:http` rather than `dart:io` so the
/// same adapter serves the phone and the browser capture build.
final class HttpCaptureBackend implements CaptureBackend {
  HttpCaptureBackend({
    required this.baseUri,
    required SegmentContentStore content,
    this.bearerToken = '',
    http.Client? client,
  }) : _content = content,
       _client = client ?? http.Client();

  final Uri baseUri;

  /// The API rejects every route but `/actuator/health` without this. Empty means the build was
  /// compiled without `GREENV_API_TOKEN`, which only works against an unauthenticated local stack.
  final String bearerToken;

  final SegmentContentStore _content;
  final http.Client _client;

  @override
  Future<void> ensureSession(QueuedSession session) async {
    final response = await _send(
      _jsonRequest('POST', '/v2/capture-sessions', {
        'sessionId': session.sessionId,
        'deviceId': session.deviceId,
        'startedAt': session.startedAtUtc.toIso8601String(),
      }),
    );
    _expect(response, {201});
  }

  @override
  Future<void> uploadSegment(QueuedSegment segment) async {
    await _upload(
      segment,
      'video',
      segment.videoContentType,
      segment.videoPath,
      segment.videoSha256,
    );
    await _upload(
      segment,
      'telemetry',
      'application/json',
      segment.telemetryPath,
      segment.telemetrySha256,
    );
    final completed = await _send(
      http.Request(
        'POST',
        _resolve(
          '/v2/capture-sessions/${segment.sessionId}'
          '/segments/${segment.segmentIndex}/complete',
        ),
      ),
    );
    _expect(completed, {202});
  }

  @override
  Future<String> segmentState(QueuedSegment segment) async {
    final response = await _send(
      http.Request(
        'GET',
        _resolve(
          '/v2/capture-sessions/${segment.sessionId}'
          '/segments/${segment.segmentIndex}',
        ),
      ),
    );
    _expect(response, {200});
    return (jsonDecode(response.body)! as Map<String, Object?>)['state']!
        as String;
  }

  @override
  Future<void> completeSession(QueuedSession session) async {
    final response = await _send(
      _jsonRequest('POST', '/v2/capture-sessions/${session.sessionId}/complete', {
        'lastSegmentIndex': session.lastSegmentIndex,
        'endedAt': session.endedAtUtc!.toIso8601String(),
      }),
    );
    _expect(response, {202});
  }

  Future<void> _upload(
    QueuedSegment segment,
    String objectName,
    String contentType,
    String reference,
    String sha256,
  ) async {
    final request = _SegmentUpload(
      'PUT',
      _resolve(
        '/v2/capture-sessions/${segment.sessionId}'
        '/segments/${segment.segmentIndex}/$objectName',
      ),
      _content.read(reference),
      await _content.length(reference),
    );
    request.headers.addAll({
      'content-type': contentType,
      'x-idempotency-key': segment.idempotencyKey,
      'x-content-sha256': sha256,
      'x-captured-at': segment.capturedAtUtc.toIso8601String(),
      'x-duration-millis': segment.durationMillis.toString(),
    });
    _expect(await _send(request), {200});
  }

  http.Request _jsonRequest(
    String method,
    String path,
    Map<String, Object?> body,
  ) => http.Request(method, _resolve(path))
    ..headers['content-type'] = 'application/json'
    ..bodyBytes = utf8.encode(jsonEncode(body));

  Uri _resolve(String path) => baseUri.resolve(path);

  Future<http.Response> _send(http.BaseRequest request) async {
    if (bearerToken.isNotEmpty) {
      request.headers['authorization'] = 'Bearer $bearerToken';
    }
    return http.Response.fromStream(await _client.send(request));
  }

  static void _expect(http.Response response, Set<int> accepted) {
    if (!accepted.contains(response.statusCode)) {
      throw CaptureBackendException(response.statusCode, response.body);
    }
  }
}

/// Streams a queued artifact instead of holding it in memory, which keeps a phone's upload flat
/// in memory regardless of segment size.
final class _SegmentUpload extends http.BaseRequest {
  _SegmentUpload(super.method, super.url, this._body, this._length);

  final Stream<List<int>> _body;
  final int _length;

  @override
  int? get contentLength => _length;

  @override
  http.ByteStream finalize() {
    super.finalize();
    return http.ByteStream(_body);
  }
}

final class CaptureBackendException implements Exception {
  const CaptureBackendException(this.statusCode, this.body);

  final int statusCode;
  final String body;

  @override
  String toString() => 'capture backend returned HTTP $statusCode';
}
