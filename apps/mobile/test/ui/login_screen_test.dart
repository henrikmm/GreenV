import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/storage/memory_capture_queue.dart';
import 'package:greenv_capture/src/storage/memory_session_store.dart';
import 'package:greenv_capture/src/ui/capture_app.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import '../support/capture_fakes.dart';

/// The login screen used to be a mockup whose button just navigated. These pin it to the API.
void main() {
  testWidgets('signs in with what was typed and opens the app', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    late http.Request seen;
    final auth = _authenticator((request) {
      seen = request;
      return http.Response(_tokens, 200);
    });

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator, authenticator: auth),
      ),
    );

    await tester.enterText(find.byKey(const Key('email-field')), 'operador@motiva.com.br');
    await tester.enterText(find.byKey(const Key('password-field')), 'uma-senha-bem-longa-123');
    await tester.tap(find.byKey(const Key('login-button')));
    await tester.pumpAndSettle();

    expect(seen.bodyFields['username'], 'operador@motiva.com.br');
    expect(seen.bodyFields['password'], 'uma-senha-bem-longa-123');
    expect(auth.signedIn.value, isTrue);
    expect(find.text('Olá, equipe de campo'), findsOneWidget);
  });

  testWidgets('stays on the login screen and explains a refusal', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    final auth = _authenticator((_) => http.Response('{"error":"invalid_client"}', 401));

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator, authenticator: auth),
      ),
    );

    await tester.enterText(find.byKey(const Key('email-field')), 'operador@motiva.com.br');
    await tester.enterText(find.byKey(const Key('password-field')), 'errada');
    await tester.tap(find.byKey(const Key('login-button')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('login-error')), findsOneWidget);
    expect(find.text('E-mail ou senha inválidos.'), findsOneWidget);
    expect(find.text('Olá, equipe de campo'), findsNothing);
  });

  testWidgets('a restored session skips the login screen', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    final store = MemorySessionStore();
    await store.write('ref-from-last-run');
    final auth = SessionAuthenticator(
      tokenUri: Uri.parse('https://api.example/v2/oauth/token'),
      store: store,
      client: MockClient((_) async => http.Response(_tokens, 200)),
    );
    await auth.restore();

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator, authenticator: auth),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Olá, equipe de campo'), findsOneWidget);
  });

  testWidgets('signing out returns to the login screen', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    final auth = _authenticator((_) => http.Response(_tokens, 200));
    await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator, authenticator: auth),
        initialPage: MotivaPage.home,
      ),
    );

    await tester.tap(find.byKey(const Key('sign-out-button')));
    await tester.pumpAndSettle();

    expect(find.text('Entrar'), findsOneWidget);
    expect(auth.signedIn.value, isFalse);
  });

  /// A session that dies while the app is open - expired, or revoked because its refresh token was
  /// replayed - must not leave someone on a screen whose every upload returns 401.
  testWidgets('a lost session sends the person back to sign in', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    var calls = 0;
    final auth = _authenticator((_) {
      calls++;
      return calls == 1 ? http.Response(_tokens, 200) : http.Response('{}', 401);
    });
    await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator, authenticator: auth),
        initialPage: MotivaPage.home,
      ),
    );
    expect(find.text('Olá, equipe de campo'), findsOneWidget);

    await auth.refresh();
    await tester.pumpAndSettle();

    expect(find.text('Entrar'), findsOneWidget);
  });
}

const _tokens =
    '{"access_token":"tok","token_type":"Bearer","expires_in":900,"refresh_token":"ref"}';

SessionAuthenticator _authenticator(http.Response Function(http.Request request) respond) =>
    SessionAuthenticator(
      tokenUri: Uri.parse('https://api.example/v2/oauth/token'),
      store: MemorySessionStore(),
      client: MockClient((request) async => respond(request)),
    );

CaptureCoordinator _coordinator() {
  final queue = MemoryCaptureQueue();
  return CaptureCoordinator(
    deviceId: 'phone-1',
    recorder: FakeRecorder(),
    telemetry: FakeTelemetry(),
    queue: queue,
    uploader: QueueUploader(queue: queue, backend: FakeBackend()..online = false),
    foregroundLease: FakeLease(),
    scheduler: FakeScheduler(),
    monotonicNanos: () => 123,
    utcNow: () => DateTime.utc(2026, 9, 4, 12),
    newSessionId: () => 'session-1',
  );
}
