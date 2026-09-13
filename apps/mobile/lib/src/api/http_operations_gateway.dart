// ignore_for_file: prefer_initializing_formals

import 'dart:convert';
import 'dart:typed_data';

import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:http/http.dart' as http;

/// Raised when the API answers with something other than what was asked for.
///
/// Carries the status and the API's own `detail`, which is RFC 7807 everywhere here, so a screen
/// can say "targets não deve estar vazio" rather than "erro desconhecido".
final class OperationsFailure implements Exception {
  const OperationsFailure(this.status, this.detail);

  final int status;
  final String detail;

  bool get unauthorized => status == 401;

  @override
  String toString() => 'operations request failed ($status): $detail';
}

/// The read side over HTTP, with the signed-in person's token on every request.
///
/// Bearer rather than cookies, which is why none of the dashboard's CSRF ceremony appears here:
/// the API takes a bearer as the machine path and skips the double-submit check. The token
/// comes from [AuthTokenProvider], which renews it before it expires, so a 401 here is a
/// session that is actually gone and not one that merely aged.
final class HttpOperationsGateway implements OperationsGateway {
  HttpOperationsGateway({
    required this.baseUri,
    required AuthTokenProvider auth,
    http.Client? client,
  }) : _auth = auth,
       _client = client ?? http.Client();

  final Uri baseUri;
  final AuthTokenProvider _auth;
  final http.Client _client;

  @override
  Future<ReadingsSummary> readingsSummary() async =>
      ReadingsSummary.fromJson(await _getObject('/v2/measurements/summary'));

  @override
  Future<PageOf<MeasuredStretch>> stretches({
    int? level,
    int limit = 25,
    int offset = 0,
  }) async {
    final query = {
      'sort': 'HEIGHT_DESC',
      'limit': '$limit',
      'offset': '$offset',
      if (level != null) 'level': '$level',
    };
    return _page(
      await _getObject('/v2/measurements', query),
      MeasuredStretch.fromJson,
    );
  }

  @override
  Future<PageOf<CaptureSessionSummary>> sessions({int limit = 10}) async =>
      _page(
        await _getObject('/v2/capture-sessions', {'limit': '$limit'}),
        CaptureSessionSummary.fromJson,
      );

  @override
  Future<List<Team>> teams() async {
    final body = await _get('/v2/teams');
    final decoded = jsonDecode(body);
    // The route answers a bare list; a page would be a shape for a thing that has four rows.
    final items = decoded is List
        ? decoded
        : (decoded as Map<String, Object?>)['items'] as List;
    return [
      for (final item in items) Team.fromJson(item as Map<String, Object?>),
    ];
  }

  @override
  Future<PageOf<ServiceOrder>> orders({int limit = 50}) async => _page(
    await _getObject('/v2/service-orders', {'limit': '$limit'}),
    ServiceOrder.fromJson,
  );

  @override
  Future<List<MeasuredStretch>> sessionStretches(String sessionId) async {
    // A session is finite and a map wants all of it at once; the ceiling is the API's own.
    final body = await _getObject('/v2/capture-sessions/$sessionId/segments', {
      'limit': '200',
    });
    return _page(
      body,
      MeasuredStretch.fromJson,
    ).items.where((stretch) => stretch.located).toList();
  }

  @override
  Future<List<SampledFrame>> frames(String sessionId, int segmentIndex) async {
    final body = await _get(
      '/v2/capture-sessions/$sessionId/segments/$segmentIndex/frames',
    );
    final decoded = jsonDecode(body);
    final items = decoded is List
        ? decoded
        : (decoded as Map<String, Object?>)['items'] as List;
    return [
      for (final item in items)
        SampledFrame.fromJson(item as Map<String, Object?>),
    ];
  }

  @override
  Future<FrameReadings?> frameReadings(
    String sessionId,
    int segmentIndex,
    String fileName,
  ) async {
    final request = http.Request(
      'GET',
      _resolve(
        '/v2/capture-sessions/$sessionId/segments/$segmentIndex/frames/$fileName/readings',
      ),
    )..headers['accept'] = 'application/json';
    final response = await _send(request);
    // A packet from before these rows existed answers 404, and that is an answer: the photograph
    // is still worth showing without what it measured.
    if (response.statusCode == 404) {
      return null;
    }
    if (response.statusCode != 200) {
      throw _failure(response);
    }
    return FrameReadings.fromJson(
      jsonDecode(response.body) as Map<String, Object?>,
    );
  }

  @override
  Future<Uint8List> frameImage(String imageUrl) async {
    final request = http.Request('GET', Uri.parse(imageUrl))
      ..headers['accept'] = 'image/jpeg';
    request.headers['authorization'] = 'Bearer ${await _auth.accessToken()}';
    final response = await http.Response.fromStream(
      await _client.send(request),
    );
    if (response.statusCode != 200) {
      throw _failure(response);
    }
    return response.bodyBytes;
  }

  @override
  Future<ServiceOrder> openOrder(OrderDraft draft) async {
    final request = http.Request('POST', _resolve('/v2/service-orders'))
      ..headers['content-type'] = 'application/json'
      ..headers['accept'] = 'application/json'
      ..body = jsonEncode(draft.toJson());
    final response = await _send(request);
    if (response.statusCode != 201 && response.statusCode != 200) {
      throw _failure(response);
    }
    return ServiceOrder.fromJson(
      jsonDecode(response.body) as Map<String, Object?>,
    );
  }

  Future<Map<String, Object?>> _getObject(
    String path, [
    Map<String, String>? query,
  ]) async => jsonDecode(await _get(path, query)) as Map<String, Object?>;

  Future<String> _get(String path, [Map<String, String>? query]) async {
    final request = http.Request('GET', _resolve(path, query))
      ..headers['accept'] = 'application/json';
    final response = await _send(request);
    if (response.statusCode != 200) {
      throw _failure(response);
    }
    return response.body;
  }

  Future<http.Response> _send(http.Request request) async {
    request.headers['authorization'] = 'Bearer ${await _auth.accessToken()}';
    return http.Response.fromStream(await _client.send(request));
  }

  Uri _resolve(String path, [Map<String, String>? query]) =>
      baseUri.resolve(path).replace(queryParameters: query);

  PageOf<T> _page<T>(
    Map<String, Object?> body,
    T Function(Map<String, Object?>) read,
  ) => PageOf(
    items: [
      for (final item in body['items'] as List)
        read(item as Map<String, Object?>),
    ],
    total: (body['total'] as num? ?? 0).toInt(),
    hasMore: body['hasMore'] as bool? ?? false,
  );

  static OperationsFailure _failure(http.Response response) {
    String detail = 'HTTP ${response.statusCode}';
    try {
      final problem = jsonDecode(response.body) as Map<String, Object?>;
      detail = (problem['detail'] ?? problem['title'] ?? detail).toString();
    } on Object {
      // Not a problem document; the status is all there is.
    }
    return OperationsFailure(response.statusCode, detail);
  }
}
