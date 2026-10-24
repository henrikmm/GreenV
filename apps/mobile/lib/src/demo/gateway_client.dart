import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:http/http.dart' as http;

/// The largest video the phone will send. The cloud API sits behind Cloudflare, which cuts a
/// request body at 100 MB, and the API refuses anything over 95 MiB with a 413; refusing on the
/// phone says so before a visitor waits through an upload that cannot land.
const int maxVideoBytes = 95 * 1024 * 1024;

/// Why [bytes] cannot be sent, in words for the screen, or null when it can.
String? videoSizeProblem(int bytes, {int limit = maxVideoBytes}) {
  if (bytes <= limit) return null;
  String mb(int b) => (b / 1e6).toStringAsFixed(1).replaceAll('.', ',');
  return 'O vídeo tem ${mb(bytes)} MB e o limite de envio é ${mb(limit)} MB. '
      'Grave por menos tempo ou em resolução menor.';
}

/// The phone's half of the phone ↔ gateway contract, version 1 (`docs/NEXT-DEMO.md`).
///
/// Three calls and a poll: announce a capture with its metadata, PUT its video, then read its
/// phases until the gateway says it is done. The gateway can be the Mac on the same Wi-Fi or the
/// cloud API; only [baseUri] changes. Every call is a path relative to it, so the same client
/// talks to `http://<mac>:8790` (read as `/v1/`) and `https://greenvapi.matomomitsu.com/v1/demo/`.
final class GatewayClient {
  GatewayClient({
    required Uri baseUri,
    required this.pairingCode,
    http.Client? client,
    this.maxBytes = maxVideoBytes,
  }) : baseUri = normaliseBase(baseUri),
       _client = client ?? http.Client();

  /// Always ends with `/`, so [Uri.resolve] appends to it instead of replacing its last segment.
  final Uri baseUri;
  final String pairingCode;

  /// The size above which [uploadVideo] refuses without opening a connection.
  final int maxBytes;
  final http.Client _client;

  /// A base with no path is the Mac gateway, whose routes live under `/v1/`; any other base is
  /// taken as the prefix itself. Query and fragment are dropped: they would ride on every call.
  static Uri normaliseBase(Uri base) {
    var path = base.path;
    if (path.isEmpty || path == '/') path = '/v1/';
    if (!path.endsWith('/')) path = '$path/';
    return Uri(
      scheme: base.scheme,
      userInfo: base.userInfo,
      host: base.host,
      port: base.hasPort ? base.port : null,
      path: path,
    );
  }

  /// Whether [base] is the Mac intake, where a `received` capture waits for the studio to open it;
  /// the cloud API moves on by itself.
  static bool isMacBase(Uri base) => normaliseBase(base).path == '/v1/';

  /// A call's address under the base. Segments are percent-encoded, so an id stays one segment.
  Uri _at(List<String> segments) => baseUri.resolveUri(Uri(pathSegments: segments));

  static const Duration _shortTimeout = Duration(seconds: 6);

  Map<String, String> get _auth => {'authorization': 'Bearer $pairingCode'};

  Future<GatewayHealth> health() async {
    try {
      final response = await _client.get(_at(['health']), headers: _auth).timeout(_shortTimeout);
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
          _at(['captures']),
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
  /// A file over [maxBytes] is refused with a 413 before anything is sent.
  Future<void> uploadVideo(
    String id,
    File file, {
    String contentType = 'video/mp4',
    void Function(int sent, int total)? onProgress,
  }) async {
    final total = await file.length();
    final tooLarge = videoSizeProblem(total, limit: maxBytes);
    if (tooLarge != null) throw GatewayException(tooLarge, statusCode: 413);
    final request = http.StreamedRequest('PUT', _at(['captures', id, 'video']))
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
    final response = await _client.get(_at(['captures', id]), headers: _auth).timeout(_shortTimeout);
    if (response.statusCode != 200) throw GatewayException.fromResponse(response);
    return CaptureStatus.fromJson(jsonDecode(response.body) as Map<String, Object?>);
  }

  /// The depth GPU's state. Unauthenticated: anyone at the stand may see whether it is on.
  /// A 404 is a server without the route; a 502 is RunPod not answering the server.
  Future<GpuStatus> gpuStatus() async {
    final response = await _client.get(_at(['gpu'])).timeout(_shortTimeout);
    if (response.statusCode != 200) throw GatewayException.fromResponse(response);
    return GpuStatus.fromJson(jsonDecode(response.body) as Map<String, Object?>);
  }

  /// Keeps one GPU worker alive (`true`) or lets the endpoint scale to zero again (`false`).
  /// Needs the pairing code, because a warm worker bills by the hour whether or not it measures.
  Future<GpuStatus> setGpuKeepWarm(bool keepWarm) async {
    final response = await _client
        .put(
          _at(['gpu']),
          headers: {..._auth, 'content-type': 'application/json'},
          body: jsonEncode({'keepWarm': keepWarm}),
        )
        // The server waits on RunPod's API before answering, which is slower than a health check.
        .timeout(const Duration(seconds: 20));
    if (response.statusCode != 200) throw GatewayException.fromResponse(response);
    return GpuStatus.fromJson(jsonDecode(response.body) as Map<String, Object?>);
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

/// RunPod's worker counts for the depth endpoint, as the server relays them.
final class GpuWorkers {
  const GpuWorkers({
    this.idle = 0,
    this.initializing = 0,
    this.ready = 0,
    this.running = 0,
    this.throttled = 0,
    this.unhealthy = 0,
  });

  factory GpuWorkers.fromJson(Map<String, Object?> json) {
    int n(String key) => (json[key] as num?)?.toInt() ?? 0;
    return GpuWorkers(
      idle: n('idle'),
      initializing: n('initializing'),
      ready: n('ready'),
      running: n('running'),
      throttled: n('throttled'),
      unhealthy: n('unhealthy'),
    );
  }

  final int idle;
  final int initializing;
  final int ready;
  final int running;
  final int throttled;
  final int unhealthy;

  /// Workers that would take a job now. RunPod reports a warm worker waiting for work under
  /// `ready` or `idle` depending on the moment, so the larger of the two is counted, never the sum.
  int get available => math.max(ready, idle);
}

/// The depth endpoint's scaling and its workers: the body of `GET` and `PUT <base>gpu`.
final class GpuStatus {
  const GpuStatus({
    required this.keepWarm,
    this.workersMin = 0,
    this.workersMax = 0,
    this.idleTimeoutSeconds = 0,
    this.workers = const GpuWorkers(),
  });

  factory GpuStatus.fromJson(Map<String, Object?> json) => GpuStatus(
    keepWarm: json['keepWarm'] == true,
    workersMin: (json['workersMin'] as num?)?.toInt() ?? 0,
    workersMax: (json['workersMax'] as num?)?.toInt() ?? 0,
    idleTimeoutSeconds: (json['idleTimeoutSeconds'] as num?)?.toInt() ?? 0,
    workers: json['workers'] is Map<String, Object?>
        ? GpuWorkers.fromJson(json['workers'] as Map<String, Object?>)
        : const GpuWorkers(),
  );

  final bool keepWarm;
  final int workersMin;
  final int workersMax;
  final int idleTimeoutSeconds;
  final GpuWorkers workers;

  /// One line for the operator. A worker that can take a job wins over everything else; a held
  /// minimum with nothing up yet is a worker on its way.
  String get summary {
    final w = workers;
    if (w.available > 0 || w.running > 0) {
      final ready = '${w.available} ${w.available == 1 ? 'pronta' : 'prontas'}';
      return w.running > 0 ? 'Ligada · $ready · ${w.running} em uso' : 'Ligada · $ready';
    }
    if (w.initializing > 0) return 'Ligando…';
    if (w.throttled > 0) return 'Sem GPU disponível no momento';
    if (keepWarm) return 'Ligando…';
    return 'Desligada (liga sozinha a cada captura)';
  }
}

/// Why a GPU call failed, in words for the operator.
String gpuProblem(Object error) {
  if (error is GatewayException) {
    return switch (error.statusCode) {
      401 || 403 => 'Código de pareamento incorreto',
      404 => 'Este servidor não controla a GPU',
      502 => 'A RunPod não respondeu ao servidor',
      _ => 'Falha ao alterar a GPU (HTTP ${error.statusCode})',
    };
  }
  if (error is SocketException || error is TimeoutException) return 'Sem conexão com o servidor';
  return 'Falha ao alterar a GPU';
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
