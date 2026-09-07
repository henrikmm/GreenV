// ignore_for_file: prefer_initializing_formals

import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:http/http.dart' as http;

/// Raised when the API refuses a sign-in. Carries no detail about which half was wrong, because
/// the API deliberately does not say - a form that distinguishes them is a way to discover which
/// accounts exist.
final class AuthenticationFailure implements Exception {
  const AuthenticationFailure(this.reason);

  final AuthenticationFailureReason reason;

  @override
  String toString() => 'authentication failed: ${reason.name}';
}

enum AuthenticationFailureReason { invalidCredentials, providerUnavailable, unreachable }

/// Signs a person in and keeps their session alive.
///
/// The person, not the build, supplies the credential: a field worker authenticates as themselves,
/// so the capture they upload is attributable and can be revoked for them alone. Machine grants
/// exist in the API for actual machines; a phone in someone's hand is not one.
///
/// Holds a fifteen-minute access token in memory and a seven-day refresh token in [SessionStore].
final class SessionAuthenticator implements AuthTokenProvider {
  SessionAuthenticator({
    required this.tokenUri,
    required SessionStore store,
    http.Client? client,
    DateTime Function()? now,
  }) : _store = store,
       _client = client ?? http.Client(),
       _now = now ?? DateTime.now;

  /// Renewed early so a token cannot expire midway through an upload.
  static const Duration _renewBefore = Duration(minutes: 1);

  final Uri tokenUri;
  final SessionStore _store;
  final http.Client _client;
  final DateTime Function() _now;

  /// Whether a session is open. The login screen watches this to know when to step aside, and the
  /// rest of the app to know when a refresh has failed and the person has to sign in again.
  final ValueNotifier<bool> signedIn = ValueNotifier<bool>(false);

  String _accessToken = '';
  DateTime _expiresAt = DateTime.fromMillisecondsSinceEpoch(0);
  String _refreshToken = '';
  Future<bool>? _inFlight;

  /// Picks up a session left by a previous run. Call once at startup.
  Future<bool> restore() async {
    final stored = await _store.read();
    if (stored == null || stored.isEmpty) {
      return false;
    }
    _refreshToken = stored;
    return refresh();
  }

  Future<void> signIn(String email, String password) async {
    await _grant({
      'grant_type': 'password',
      'username': email.trim(),
      'password': password,
    });
  }

  Future<void> signOut() async {
    _accessToken = '';
    _refreshToken = '';
    _expiresAt = DateTime.fromMillisecondsSinceEpoch(0);
    await _store.clear();
    signedIn.value = false;
  }

  @override
  Future<String> accessToken() async {
    if (_accessToken.isNotEmpty && _now().isBefore(_expiresAt.subtract(_renewBefore))) {
      return _accessToken;
    }
    await refresh();
    return _accessToken;
  }

  @override
  Future<bool> refresh() {
    if (_refreshToken.isEmpty) {
      return Future<bool>.value(false);
    }
    // One rotation at a time. Several queued segments failing together must not each exchange the
    // refresh token: every exchange retires the previous one, so the losers would look like a
    // stolen token being replayed and the API would revoke the whole session.
    return _inFlight ??= _rotate().whenComplete(() => _inFlight = null);
  }

  Future<bool> _rotate() async {
    try {
      await _grant({'grant_type': 'refresh_token', 'refresh_token': _refreshToken});
      return true;
    } on AuthenticationFailure catch (failure) {
      if (failure.reason == AuthenticationFailureReason.invalidCredentials) {
        // The refresh token is spent, expired, or the session was revoked - including by this
        // token having been replayed elsewhere. Either way there is nothing left to refresh with.
        await signOut();
      }
      return false;
    }
  }

  Future<void> _grant(Map<String, String> form) async {
    final http.Response response;
    try {
      response = await _client.post(
        tokenUri,
        headers: {'content-type': 'application/x-www-form-urlencoded'},
        body: form,
      );
    } on Exception {
      throw const AuthenticationFailure(AuthenticationFailureReason.unreachable);
    }

    if (response.statusCode == 503) {
      throw const AuthenticationFailure(AuthenticationFailureReason.providerUnavailable);
    }
    if (response.statusCode != 200) {
      throw const AuthenticationFailure(AuthenticationFailureReason.invalidCredentials);
    }

    final body = jsonDecode(response.body) as Map<String, Object?>;
    final access = body['access_token'];
    final refreshed = body['refresh_token'];
    final expiresIn = body['expires_in'];

    if (access is! String || access.isEmpty || refreshed is! String || refreshed.isEmpty) {
      throw const AuthenticationFailure(AuthenticationFailureReason.invalidCredentials);
    }

    // Persisted before anything else. If the app died between receiving a new refresh token and
    // writing it down, the next run would present the retired one - which the API reads as a
    // stolen token being replayed and answers by revoking the whole session.
    await _store.write(refreshed);

    _refreshToken = refreshed;
    _accessToken = access;
    _expiresAt = _now().add(Duration(seconds: expiresIn is num ? expiresIn.toInt() : 0));
    signedIn.value = true;
  }
}
