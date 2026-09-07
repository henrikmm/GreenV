import 'package:flutter/widgets.dart';
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

void main() {
  testWidgets('follows login and password recovery screens', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    await tester.pumpWidget(
      CaptureApp(dependencies: AppDependencies(capture: coordinator, authenticator: _authenticator())),
    );
    expect(find.bySemanticsLabel('motiva'), findsOneWidget);
    expect(find.bySemanticsLabel('GreenV'), findsOneWidget);
    expect(find.text('Entrar'), findsOneWidget);

    await tester.tap(find.text('Esqueceu a senha?'));
    await tester.pumpAndSettle();
    expect(find.text('Enviar código'), findsOneWidget);

    await tester.tap(find.text('Enviar código'));
    await tester.pumpAndSettle();
    expect(
      find.text('Enviamos seis dígitos para o seu e-mail corporativo.'),
      findsOneWidget,
    );

    // Recovery is a mockup - no code is sent and none is verified - so it returns to the login
    // screen instead of opening the app. It used to walk straight in.
    await tester.tap(find.text('Voltar para entrar'));
    await tester.pumpAndSettle();
    expect(find.text('Bem-vindo'), findsOneWidget);
    expect(find.text('Olá, equipe de campo'), findsNothing);
  });

  testWidgets('navigates home, upload, network summary and map', (
    tester,
  ) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(
          capture: coordinator,
          authenticator: await _signedIn(),
        ),
        initialPage: MotivaPage.home,
      ),
    );
    expect(find.text('Malha monitorada'), findsOneWidget);

    await tester.ensureVisible(find.text('Ver mapa'));
    await tester.tap(find.text('Ver mapa'));
    await tester.pumpAndSettle();
    expect(find.text('RESUMO DA MALHA'), findsOneWidget);

    await tester.tap(find.byKey(const Key('nav-mapa')));
    await tester.pumpAndSettle();
    expect(find.text('Buscar rodovia, trecho ou km'), findsOneWidget);

    await tester.tap(find.byKey(const Key('nav-upload')));
    await tester.pumpAndSettle();
    expect(find.text('Grave o trecho da rodovia'), findsOneWidget);
  });

  testWidgets('shows upload queue and recording states', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(
          capture: coordinator,
          authenticator: await _signedIn(),
        ),
        initialPage: MotivaPage.upload,
      ),
    );
    expect(find.text('Iniciar coleta'), findsOneWidget);
    expect(find.text('Tudo enviado'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('record-button')));
    await tester.tap(find.byKey(const Key('record-button')));
    await tester.pump();
    expect(find.text('Rota em andamento'), findsOneWidget);
    expect(find.text('Encerrar coleta'), findsOneWidget);
    expect(find.text('GRAVANDO'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('renders the separate splash frame', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator, authenticator: _authenticator()),
        initialPage: MotivaPage.splash,
      ),
    );
    expect(find.bySemanticsLabel('motiva'), findsOneWidget);
    expect(find.text('Entrar'), findsNothing);
  });
}

CaptureCoordinator _coordinator() {
  final queue = MemoryCaptureQueue();
  return CaptureCoordinator(
    deviceId: 'phone-1',
    recorder: FakeRecorder(),
    telemetry: FakeTelemetry(),
    queue: queue,
    uploader: QueueUploader(
      queue: queue,
      backend: FakeBackend()..online = false,
    ),
    foregroundLease: FakeLease(),
    scheduler: FakeScheduler(),
    identifierGenerator: FakeIdentifierGenerator('session-1'),
    monotonicNanos: () => 123,
    utcNow: () => DateTime.utc(2026, 8, 23, 12),
  );
}

/// An authenticator wired to a stub token endpoint. [respond] decides what the API says, so a test
/// can drive a successful sign-in or a refusal without a server.
/// An authenticator that has already signed in, for the screens behind the guard.
Future<SessionAuthenticator> _signedIn() async {
  final auth = _authenticator();
  await auth.signIn('operador@motiva.com.br', 'uma-senha-bem-longa-123');
  return auth;
}

SessionAuthenticator _authenticator({
  http.Response Function(http.Request request)? respond,
}) => SessionAuthenticator(
  tokenUri: Uri.parse('https://api.example/v2/oauth/token'),
  store: MemorySessionStore(),
  client: MockClient(
    (request) async =>
        respond?.call(request) ??
        http.Response(
          '{"access_token":"tok","token_type":"Bearer","expires_in":900,'
          '"refresh_token":"ref"}',
          200,
        ),
  ),
);
