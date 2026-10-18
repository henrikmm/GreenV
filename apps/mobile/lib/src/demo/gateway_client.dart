import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

/// The phone's half of the phone ↔ gateway contract, version 1 (`docs/NEXT-DEMO.md`).
///
/// Three calls and a poll: announce a capture with its metadata, PUT its video, then read its
/// phases until the gateway says it is done. The gateway can be the Mac on the same Wi-Fi or a
/// cloud service; only [baseUri] changes.
final class GatewayClient {
  GatewayClient({required this.baseUri, required this.pairingCode, http.Client? client})
    : _client = client ?? http.Client();

  final Uri baseUri;
  final String pairingCode;
  final http.Client _client;

  static const Duration _shortTimeout = Duration(seconds: 6);

  Map<String, String> get _auth => {'authorization': 'Bearer $pairingCode'};

  Future<GatewayHealth> health() async {
    try {
      final response = await _client.get(baseUri.resolve('/v1/health'), headers: _auth).timeout(_shortTimeout);
      if (response.statusCode != 200) return GatewayHealth.unreachable('HTTP ${response.statusCode}');
      final body = jsonDecode(response.body) as Map<String, Object?>;
      return GatewayHealth(
        reachable: true,
        authorised: body['authorised'] == true,
        contract: body['contract'] is int ? body['contract'] as int : null,
      );
    } on Object catch (error) {
      return GatewayHealth.unreachable(_describe(error));
    }
  }

  /// Returns the capture id the gateway assigned.
  Future<String> announce(Map<String, Object?> meta) async {
    final response = await _client
        .post(
          baseUri.resolve('/v1/captures'),
          headers: {..._auth, 'content-type': 'application/json'},
          body: jsonEncode(meta),
        )
        .timeout(_shortTimeout);
    if (response.statusCode != 201) throw GatewayException.fromResponse(response);
    final body = jsonDecode(response.body) as Map<String, Object?>;
    final id = body['id'];
    if (id is! String) throw const GatewayException('the gateway returned no capture id');
    return id;
  }

  /// Streams [file] to the gateway, reporting bytes sent. Never reads the whole video into memory.
  Future<void> uploadVideo(
    String id,
    File file, {
    String contentType = 'video/mp4',
    void Function(int sent, int total)? onProgress,
  }) async {
    final total = await file.length();
    final request = http.StreamedRequest('PUT', baseUri.resolve('/v1/captures/$id/video'))
      ..headers.addAll({..._auth, 'content-type': contentType})
      ..contentLength = total;
    final responseFuture = _client.send(request);
    // A refused connection fails this future while the file is still being read; listening now
    // keeps that from surfacing as an unhandled error before the await below reports it.
    unawaited(responseFuture.then((_) {}, onError: (_) {}));
    var sent = 0;
    try {
      await for (final chunk in file.openRead()) {
        request.sink.add(chunk);
        sent += chunk.length;
        onProgress?.call(sent, total);
      }
    } finally {
      await request.sink.close();
    }
    final response = await http.Response.fromStream(await responseFuture.timeout(const Duration(minutes: 10)));
    if (response.statusCode != 200) throw GatewayException.fromResponse(response);
  }

  Future<CaptureStatus> status(String id) async {
    final response = await _client.get(baseUri.resolve('/v1/captures/$id'), headers: _auth).timeout(_shortTimeout);
    if (response.statusCode != 200) throw GatewayException.fromResponse(response);
    return CaptureStatus.fromJson(jsonDecode(response.body) as Map<String, Object?>);
  }

  void close() => _client.close();

  static String _describe(Object error) {
    if (error is SocketException) return 'sem rota até o gateway (${error.osError?.message ?? error.message})';
    if (error is TimeoutException) return 'o gateway não respondeu a tempo';
    return error.toString();
  }
}

final class GatewayHealth {
  const GatewayHealth({required this.reachable, required this.authorised, this.contract, this.problem});

  const GatewayHealth.unreachable(String this.problem) : reachable = false, authorised = false, contract = null;

  final bool reachable;
  final bool authorised;
  final int? contract;
  final String? problem;

  bool get ready => reachable && authorised;
}

final class GatewayException implements Exception {
  const GatewayException(this.message, {this.statusCode});

  factory GatewayException.fromResponse(http.Response response) {
    String detail = response.body;
    try {
      final decoded = jsonDecode(response.body);
      if (decoded is Map && decoded['detail'] is String) detail = decoded['detail'] as String;
    } on Object {
      // keep the raw body
    }
    return GatewayException('HTTP ${response.statusCode}: $detail', statusCode: response.statusCode);
  }

  final String message;
  final int? statusCode;

  @override
  String toString() => message;
}

final class CapturePhase {
  const CapturePhase({required this.phase, required this.tMs, this.detail});

  final String phase;

  /// Milliseconds since the gateway created the capture, on the gateway's clock.
  final int tMs;
  final Map<String, Object?>? detail;
}

final class CaptureStatus {
  const CaptureStatus({required this.id, required this.state, required this.phases, this.result});

  factory CaptureStatus.fromJson(Map<String, Object?> json) => CaptureStatus(
    id: json['id'] as String,
    state: json['state'] as String,
    phases: [
      for (final p in (json['phases'] as List<Object?>? ?? const []))
        if (p is Map<String, Object?>)
          CapturePhase(
            phase: p['phase'] as String,
            tMs: (p['tMs'] as num?)?.toInt() ?? 0,
            detail: p['detail'] is Map<String, Object?> ? p['detail'] as Map<String, Object?> : null,
          ),
    ],
    result: json['result'] is Map<String, Object?> ? json['result'] as Map<String, Object?> : null,
  );

  final String id;
  final String state;
  final List<CapturePhase> phases;
  final Map<String, Object?>? result;

  /// The studio reports `inferred` when the GPU answers and `fetched` once the result is down
  /// and the cloud built; only the second is the end of the wait.
  bool get finished => state == 'fetched' || state == 'measured' || state == 'failed';
}
