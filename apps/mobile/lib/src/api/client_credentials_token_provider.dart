import 'dart:convert';

import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:http/http.dart' as http;

/// Fetches and caches a machine token from the API's OAuth2 endpoint.
///
/// The capture client is a public client, so the `client_secret` must be provisioned per device at
/// runtime and kept in the platform keychain - never compiled in with `--dart-define`, which is
/// trivially extractable from the binary.
///
/// The token lasts four hours, which suits this client specifically: the upload queue drains over
/// hours while a phone drifts in and out of signal, and a fifteen-minute human token would spend
/// most of that time being refreshed.
final class ClientCredentialsTokenProvider implements AuthTokenProvider {
  ClientCredentialsTokenProvider({
    required this.tokenUri,
    required this.clientId,
    required this.clientSecret,
    http.Client? client,
    DateTime Function()? now,
  }) : _client = client ?? http.Client(),
       _now = now ?? DateTime.now;

  /// Refreshed early, so a token does not expire mid-upload.
  static const Duration _renewBefore = Duration(minutes: 5);

  final Uri tokenUri;
  final String clientId;
  final String clientSecret;

  final http.Client _client;
  final DateTime Function() _now;

  String _token = '';
  DateTime _expiresAt = DateTime.fromMillisecondsSinceEpoch(0);
  Future<bool>? _inFlight;

  @override
  Future<String> accessToken() async {
    if (_token.isNotEmpty && _now().isBefore(_expiresAt.subtract(_renewBefore))) {
      return _token;
    }
    await refresh();
    return _token;
  }

  @override
  Future<bool> refresh() {
    // One request at a time: several queued segments failing together must not each start their
    // own grant.
    return _inFlight ??= _fetch().whenComplete(() => _inFlight = null);
  }

  Future<bool> _fetch() async {
    final response = await _client.post(
      tokenUri,
      headers: {'content-type': 'application/x-www-form-urlencoded'},
      body: {
        'grant_type': 'client_credentials',
        'client_id': clientId,
        'client_secret': clientSecret,
      },
    );

    if (response.statusCode != 200) {
      // Leaves the previous token in place: it may still have minutes left, and a queue that keeps
      // trying is better than one that stops on a single failed grant.
      return false;
    }

    final body = jsonDecode(response.body) as Map<String, Object?>;
    final token = body['access_token'];
    final expiresIn = body['expires_in'];
    if (token is! String || token.isEmpty) {
      return false;
    }

    _token = token;
    _expiresAt = _now().add(
      Duration(seconds: expiresIn is num ? expiresIn.toInt() : 0),
    );
    return true;
  }
}
