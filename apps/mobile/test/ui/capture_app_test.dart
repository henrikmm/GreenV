import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/storage/memory_capture_queue.dart';
import 'package:greenv_capture/src/ui/capture_app.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';

import '../support/capture_fakes.dart';

void main() {
  testWidgets('follows login and password recovery screens', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    await tester.pumpWidget(
      CaptureApp(dependencies: AppDependencies(capture: coordinator)),
    );
    expect(find.bySemanticsLabel('motiva'), findsOneWidget);
    expect(find.bySemanticsLabel('GreenV'), findsOneWidget);
    expect(find.text('ENTRAR'), findsOneWidget);

    await tester.tap(find.text('Esqueceu a senha?'));
    await tester.pumpAndSettle();
    expect(find.text('ENVIAR EMAIL'), findsOneWidget);

    await tester.tap(find.text('ENVIAR EMAIL'));
    await tester.pumpAndSettle();
    expect(
      find.text('Digite o código enviado por email para acessar sua conta'),
      findsOneWidget,
    );

    await tester.tap(find.text('ENTRAR'));
    await tester.pumpAndSettle();
    expect(find.text('Olá, Nome'), findsOneWidget);
  });

  testWidgets('navigates home, upload, network summary and map', (
    tester,
  ) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator),
        initialPage: MotivaPage.home,
      ),
    );
    expect(find.text('Visão Geral da Malha'), findsOneWidget);

    await tester.tap(find.byKey(const Key('network-preview')));
    await tester.pumpAndSettle();
    expect(find.text('RESUMO DA MALHA'), findsOneWidget);

    await tester.tap(find.byKey(const Key('nav-mapa')));
    await tester.pumpAndSettle();
    expect(find.text('Search route or area...'), findsOneWidget);

    await tester.tap(find.byKey(const Key('nav-upload')));
    await tester.pumpAndSettle();
    expect(find.text('Faça upload para\nreconhecimento'), findsOneWidget);
  });

  testWidgets('shows upload queue and recording states', (tester) async {
    final coordinator = _coordinator();
    addTearDown(coordinator.dispose);

    await tester.pumpWidget(
      CaptureApp(
        dependencies: AppDependencies(capture: coordinator),
        initialPage: MotivaPage.upload,
      ),
    );
    expect(find.text('Iniciar gravação'), findsOneWidget);
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
        dependencies: AppDependencies(capture: coordinator),
        initialPage: MotivaPage.splash,
      ),
    );
    expect(find.bySemanticsLabel('motiva'), findsOneWidget);
    expect(find.text('ENTRAR'), findsNothing);
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
    monotonicNanos: () => 123,
    utcNow: () => DateTime.utc(2026, 8, 23, 12),
    newSessionId: () => 'session-1',
  );
}
