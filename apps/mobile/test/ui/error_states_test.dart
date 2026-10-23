import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/api/http_operations_gateway.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/storage/memory_capture_queue.dart';
import 'package:greenv_capture/src/storage/memory_session_store.dart';
import 'package:greenv_capture/src/ui/capture_app.dart';
import 'package:greenv_capture/src/ui/operations_screens.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import '../support/capture_fakes.dart';

/// The paths the manual test report used to skip: no network, an API that refuses, an empty
/// list, a camera that will not open. Each one is a case in TESTES_MANUAIS.md, and these pin
/// what the screen says when it happens.
void main() {
  group('login', () {
    testWidgets('says the server is unreachable when there is no network', (tester) async {
      final auth = SessionAuthenticator(
        tokenUri: Uri.parse('https://api.example/v2/oauth/token'),
        store: MemorySessionStore(),
        client: MockClient((_) async => throw http.ClientException('offline')),
      );
      await _pump(tester, auth: auth, page: MotivaPage.login);

      await _signIn(tester);

      expect(
        find.text('Sem conexão com o servidor. Verifique a rede e tente novamente.'),
        findsOneWidget,
      );
      expect(find.text('Olá, equipe de campo'), findsNothing);
      // The form is usable again, so the person can retry once the signal is back.
      final button = tester.widget<FilledButton>(find.byKey(const Key('login-button')));
      expect(button.onPressed, isNotNull);
    });

    testWidgets('says the identity provider is down on a 503', (tester) async {
      final auth = SessionAuthenticator(
        tokenUri: Uri.parse('https://api.example/v2/oauth/token'),
        store: MemorySessionStore(),
        client: MockClient((_) async => http.Response('', 503)),
      );
      await _pump(tester, auth: auth, page: MotivaPage.login);

      await _signIn(tester);

      expect(find.textContaining('serviço de autenticação está indisponível'), findsOneWidget);
    });
  });

  group('operation screens', () {
    testWidgets('without network the home screen offers a retry that works', (tester) async {
      final gateway = _seeded()..failure = const SocketExceptionForTest();
      await _pump(tester, gateway: gateway, page: MotivaPage.home);

      expect(find.byKey(const Key('remote-error')), findsOneWidget);
      expect(find.text('Sem resposta da API. Verifique o sinal e tente de novo.'), findsOneWidget);
      // Capture never waits on the API: its card is on screen while the summary fails.
      expect(find.text('PRONTO PARA COLETAR'), findsOneWidget);

      gateway.failure = null;
      await tester.ensureVisible(find.text('Tentar de novo'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Tentar de novo'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('remote-error')), findsNothing);
      expect(find.text('ACIMA DE 30 CM'), findsOneWidget);
    });

    testWidgets('an expired session says so instead of a generic error', (tester) async {
      final gateway = _seeded()..failure = const OperationsFailure(401, 'unauthorized');
      await _pump(tester, gateway: gateway, page: MotivaPage.orders);

      expect(find.text('Sua sessão terminou. Entre de novo.'), findsOneWidget);
    });

    testWidgets('empty lists say what is missing on every screen', (tester) async {
      final gateway = FakeOperationsGateway();
      await _pump(tester, gateway: gateway, page: MotivaPage.home);

      expect(find.text('Nenhum trecho medido ainda.'), findsOneWidget);
      expect(find.textContaining('Nenhuma sessão enviada ainda.'), findsOneWidget);

      await tester.tap(find.byKey(const Key('nav-trechos')));
      await tester.pumpAndSettle();
      expect(find.text('Nenhuma leitura com esse filtro.'), findsOneWidget);

      await tester.tap(find.byKey(const Key('nav-mapa')));
      await tester.pumpAndSettle();
      expect(find.text('Nenhum trecho medido com posição ainda.'), findsOneWidget);

      await tester.tap(find.byKey(const Key('nav-ordens')));
      await tester.pumpAndSettle();
      expect(
        find.text('Nenhuma ordem aberta ainda. Abra uma a partir de um trecho.'),
        findsOneWidget,
      );

      await tester.tap(find.text('Equipes'));
      await tester.pumpAndSettle();
      expect(find.text('Nenhuma equipe cadastrada.'), findsOneWidget);
    });

    testWidgets('a refused order keeps the form open with the API words', (tester) async {
      final gateway = _seeded();
      await _pump(tester, gateway: gateway, page: MotivaPage.stretches);

      await tester.tap(find.byType(StretchTile).first);
      await tester.pumpAndSettle();
      await tester.dragUntilVisible(
        find.byKey(const Key('open-order-button')),
        find
            .descendant(of: find.byType(DraggableScrollableSheet), matching: find.byType(ListView))
            .first,
        const Offset(0, -120),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('open-order-button')));
      await tester.pumpAndSettle();

      gateway.failure = const OperationsFailure(422, 'A equipe escolhida está inativa.');
      await tester.ensureVisible(find.byKey(const Key('submit-order-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('submit-order-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('order-error')), findsOneWidget);
      expect(find.text('A equipe escolhida está inativa.'), findsOneWidget);
      expect(find.text('Nova ordem de serviço'), findsOneWidget);
      expect(gateway.opened, isEmpty);
    });
  });

  group('capture', () {
    testWidgets('a capture made offline waits in the queue and leaves '
        'when the network returns', (tester) async {
      final backend = FakeBackend()..online = false;
      final coordinator = _coordinator(backend: backend);
      addTearDown(coordinator.dispose);
      await _pump(tester, coordinator: coordinator, page: MotivaPage.upload);
      expect(find.text('Tudo enviado'), findsOneWidget);

      await tester.ensureVisible(find.byKey(const Key('record-button')));
      await tester.tap(find.byKey(const Key('record-button')));
      await tester.pump();
      expect(find.text('Rota em andamento'), findsOneWidget);

      await tester.ensureVisible(find.byKey(const Key('record-button')));
      await tester.tap(find.byKey(const Key('record-button')));
      await tester.pump();
      await tester.pump();

      expect(find.text('Iniciar coleta'), findsOneWidget);
      expect(find.text('1 segmento na fila'), findsOneWidget);

      backend.online = true;
      await coordinator.syncBacklog();
      await tester.pump();

      expect(find.text('Tudo enviado'), findsOneWidget);
      expect(backend.uploads, 1);

      await tester.pumpWidget(const SizedBox.shrink());
    });

    testWidgets('a refused camera permission is explained, and the button '
        'stays available to try again', (tester) async {
      final recorder = FakeRecorder()
        ..initializeError = Exception(
          'CameraException(CameraAccessDenied, User denied camera access.)',
        );
      final coordinator = _coordinator(recorder: recorder);
      addTearDown(coordinator.dispose);
      await _pump(tester, coordinator: coordinator, page: MotivaPage.upload);

      await tester.ensureVisible(find.byKey(const Key('record-button')));
      await tester.tap(find.byKey(const Key('record-button')));
      await tester.pump();

      expect(find.textContaining('Permissão da câmera negada'), findsOneWidget);
      expect(find.textContaining('CameraException'), findsNothing);
      expect(coordinator.phase, CapturePhase.error);
      final button = tester.widget<FilledButton>(find.byKey(const Key('record-button')));
      expect(button.onPressed, isNotNull);

      await tester.pumpWidget(const SizedBox.shrink());
    });
  });
}

Future<void> _signIn(WidgetTester tester) async {
  await tester.enterText(find.byKey(const Key('email-field')), 'avaliador@greenv.app');
  await tester.enterText(find.byKey(const Key('password-field')), 'senha');
  await tester.tap(find.byKey(const Key('login-button')));
  await tester.pumpAndSettle();
}

Future<void> _pump(
  WidgetTester tester, {
  required MotivaPage page,
  SessionAuthenticator? auth,
  FakeOperationsGateway? gateway,
  CaptureCoordinator? coordinator,
}) async {
  if (coordinator == null) {
    coordinator = _coordinator();
    addTearDown(coordinator.dispose);
  }
  auth ??= SessionAuthenticator(
    tokenUri: Uri.parse('https://api.example/v2/oauth/token'),
    store: MemorySessionStore(),
    client: MockClient((_) async => http.Response('{}', 401)),
  )..signedIn.value = true;

  await tester.pumpWidget(
    CaptureApp(
      dependencies: AppDependencies(
        operations: gateway ?? FakeOperationsGateway(),
        capture: coordinator,
        authenticator: auth,
      ),
      initialPage: page,
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
}

CaptureCoordinator _coordinator({FakeBackend? backend, FakeRecorder? recorder}) {
  final queue = MemoryCaptureQueue();
  return CaptureCoordinator(
    deviceId: 'phone-1',
    recorder: recorder ?? FakeRecorder(),
    telemetry: FakeTelemetry(),
    queue: queue,
    uploader: QueueUploader(queue: queue, backend: backend ?? (FakeBackend()..online = false)),
    foregroundLease: FakeLease(),
    scheduler: FakeScheduler(),
    identifierGenerator: FakeIdentifierGenerator('session-1'),
    monotonicNanos: () => 123,
    utcNow: () => DateTime.utc(2026, 10, 20, 12),
  );
}

FakeOperationsGateway _seeded() => FakeOperationsGateway()
  ..summary = const ReadingsSummary(total: 1, countsByLevel: {3: 1}, tallestM: 0.48)
  ..stretchRows = [
    MeasuredStretch(
      sessionId: 's1',
      segmentIndex: 0,
      capturedAt: DateTime.utc(2026, 10, 20, 12),
      level: 3,
      extent95P95M: 0.48,
      cellsMeasured: 10,
      cellsAbstained: 2,
      centreLat: -23.57,
      centreLon: -46.63,
      locationQuality: 'good',
      placeLabel: 'Rodoanel Oeste',
      placeDetail: 'SP-021',
    ),
  ];
