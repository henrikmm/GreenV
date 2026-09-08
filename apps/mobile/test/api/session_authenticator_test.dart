import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/storage/memory_session_store.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  final tokenUri = Uri.parse('https://api.example/v2/oauth/token');

  String tokens({String access = 'tok-1', String refresh = 'ref-1', int expiresIn = 900}) =>
      jsonEncode({
        'access_token': access,
        'token_type': 'Bearer',
        'expires_in': expiresIn,
        'refresh_token': refresh,
      });

  SessionAuthenticator subject(
    http.Client client, {
    MemorySessionStore? store,
    DateTime Function()? now,
  }) => SessionAuthenticator(
    tokenUri: tokenUri,
    store: store ?? MemorySessionStore(),
    client: client,
    now: now,
  );

  test('signs in with the password grant the API expects', () async {
    late http.Request seen;
    final auth = subject(MockClient((request) async {
      seen = request;
      return http.Response(tokens(), 200);
    }));

    await auth.signIn(' operador@motiva.com.br ', 'uma-senha-bem-longa-123');

    expect(seen.url, tokenUri);
    expect(seen.bodyFields['grant_type'], 'password');
    // Trimmed, because a keyboard on a phone adds a trailing space more often than not.
    expect(seen.bodyFields['username'], 'operador@motiva.com.br');
    expect(seen.bodyFields['password'], 'uma-senha-bem-longa-123');
    expect(auth.signedIn.value, isTrue);
    expect(await auth.accessToken(), 'tok-1');
  });

  test('reports invalid credentials without saying which half was wrong', () async {
    final auth = subject(MockClient((_) async => http.Response('{"error":"invalid_client"}', 401)));

    await expectLater(
      auth.signIn('operador@motiva.com.br', 'errada'),
      throwsA(
        isA<AuthenticationFailure>().having(
          (failure) => failure.reason,
          'reason',
          AuthenticationFailureReason.invalidCredentials,
        ),
      ),
    );
    expect(auth.signedIn.value, isFalse);
  });

  test('separates an unconfigured identity provider from a bad password', () async {
    final auth = subject(MockClient((_) async => http.Response('{}', 503)));

    await expectLater(
      auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123'),
      throwsA(
        isA<AuthenticationFailure>().having(
          (failure) => failure.reason,
          'reason',
          AuthenticationFailureReason.providerUnavailable,
        ),
      ),
    );
  });

  test('persists the refresh token before anything else uses it', () async {
    final store = MemorySessionStore();
    final auth = subject(MockClient((_) async => http.Response(tokens(refresh: 'ref-1'), 200)),
        store: store);

    await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');

    // Written down immediately: if the app died before this, the next run would present the
    // retired token and the API would revoke the whole session as a replay.
    expect(await store.read(), 'ref-1');
  });

  test('restores a session left by the previous run', () async {
    final store = MemorySessionStore();
    await store.write('ref-from-last-run');

    late http.Request seen;
    final auth = subject(MockClient((request) async {
      seen = request;
      return http.Response(tokens(access: 'tok-2', refresh: 'ref-2'), 200);
    }), store: store);

    expect(await auth.restore(), isTrue);
    expect(seen.bodyFields['grant_type'], 'refresh_token');
    expect(seen.bodyFields['refresh_token'], 'ref-from-last-run');
    // Rotated, and the new one is what survives to the next run.
    expect(await store.read(), 'ref-2');
    expect(auth.signedIn.value, isTrue);
  });

  test('starts signed out when there is nothing to restore', () async {
    var calls = 0;
    final auth = subject(MockClient((_) async {
      calls++;
      return http.Response(tokens(), 200);
    }));

    expect(await auth.restore(), isFalse);
    expect(calls, 0);
    expect(auth.signedIn.value, isFalse);
  });

  test('signs out when the session is gone, so the app can ask again', () async {
    final store = MemorySessionStore();
    var calls = 0;
    final auth = subject(MockClient((_) async {
      calls++;
      return calls == 1 ? http.Response(tokens(), 200) : http.Response('{}', 401);
    }), store: store);

    await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');
    expect(auth.signedIn.value, isTrue);

    // A revoked family or an expired refresh token leaves nothing to retry with.
    expect(await auth.refresh(), isFalse);
    expect(auth.signedIn.value, isFalse);
    expect(await store.read(), isNull);
  });

  test('renews an access token before it expires', () async {
    var calls = 0;
    var clock = DateTime.utc(2026, 9, 4, 12);
    final auth = subject(MockClient((_) async {
      calls++;
      return http.Response(tokens(access: 'tok-$calls', refresh: 'ref-$calls'), 200);
    }), now: () => clock);

    await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');
    expect(await auth.accessToken(), 'tok-1');

    clock = clock.add(const Duration(minutes: 14, seconds: 30));
    expect(await auth.accessToken(), 'tok-2');
  });

  test('coalesces concurrent refreshes into one rotation', () async {
    var calls = 0;
    final auth = subject(MockClient((_) async {
      calls++;
      await Future<void>.delayed(const Duration(milliseconds: 10));
      return http.Response(tokens(refresh: 'ref-$calls'), 200);
    }));

    await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');
    calls = 0;

    await Future.wait([auth.refresh(), auth.refresh(), auth.refresh()]);

    // Every exchange retires the previous refresh token, so three parallel rotations would look
    // like a stolen token being replayed and cost the whole session.
    expect(calls, 1);
  });

  test('signing out forgets the session on this device', () async {
    final store = MemorySessionStore();
    final auth = subject(MockClient((_) async => http.Response(tokens(), 200)), store: store);

    await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');
    await auth.signOut();

    expect(auth.signedIn.value, isFalse);
    expect(await store.read(), isNull);
    expect(await auth.refresh(), isFalse);
  });
}
