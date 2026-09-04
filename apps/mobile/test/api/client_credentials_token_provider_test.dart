import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/api/client_credentials_token_provider.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  final tokenUri = Uri.parse('https://greenvapi.example/v2/oauth/token');

  ClientCredentialsTokenProvider provider(
    http.Client client, {
    DateTime Function()? now,
  }) => ClientCredentialsTokenProvider(
    tokenUri: tokenUri,
    clientId: 'gv-test',
    clientSecret: 'a-secret',
    client: client,
    now: now,
  );

  test('sends the client_credentials grant form the API expects', () async {
    late http.Request seen;
    final client = MockClient((request) async {
      seen = request;
      return http.Response(
        jsonEncode({'access_token': 'tok-1', 'token_type': 'Bearer', 'expires_in': 14400}),
        200,
      );
    });

    expect(await provider(client).accessToken(), 'tok-1');
    expect(seen.url, tokenUri);
    expect(seen.headers['content-type'], startsWith('application/x-www-form-urlencoded'));
    expect(seen.bodyFields['grant_type'], 'client_credentials');
    expect(seen.bodyFields['client_id'], 'gv-test');
    expect(seen.bodyFields['client_secret'], 'a-secret');
  });

  test('reuses a cached token instead of asking again', () async {
    var calls = 0;
    final client = MockClient((_) async {
      calls++;
      return http.Response(
        jsonEncode({'access_token': 'tok-$calls', 'expires_in': 14400}),
        200,
      );
    });

    final subject = provider(client);
    expect(await subject.accessToken(), 'tok-1');
    expect(await subject.accessToken(), 'tok-1');
    expect(calls, 1);
  });

  test('renews before the token actually expires', () async {
    var calls = 0;
    var clock = DateTime.utc(2026, 9, 3, 12);
    final client = MockClient((_) async {
      calls++;
      return http.Response(
        jsonEncode({'access_token': 'tok-$calls', 'expires_in': 14400}),
        200,
      );
    });

    final subject = provider(client, now: () => clock);
    expect(await subject.accessToken(), 'tok-1');

    // Inside the four hours, but within the renewal window - a token must not expire mid-upload.
    clock = clock.add(const Duration(hours: 3, minutes: 56));
    expect(await subject.accessToken(), 'tok-2');
    expect(calls, 2);
  });

  test('keeps the current token when the grant fails', () async {
    var calls = 0;
    final client = MockClient((_) async {
      calls++;
      return calls == 1
          ? http.Response(jsonEncode({'access_token': 'tok-1', 'expires_in': 14400}), 200)
          : http.Response('{"error":"invalid_client"}', 401);
    });

    final subject = provider(client);
    expect(await subject.accessToken(), 'tok-1');
    expect(await subject.refresh(), isFalse);
    // A single failed grant must not empty the queue's credential; the old token may still work.
    expect(await subject.accessToken(), 'tok-1');
  });

  test('coalesces concurrent refreshes into one grant', () async {
    var calls = 0;
    final client = MockClient((_) async {
      calls++;
      await Future<void>.delayed(const Duration(milliseconds: 10));
      return http.Response(jsonEncode({'access_token': 'tok', 'expires_in': 14400}), 200);
    });

    final subject = provider(client);
    await Future.wait([subject.refresh(), subject.refresh(), subject.refresh()]);

    // Several segments failing together must not each start their own grant.
    expect(calls, 1);
  });
}
