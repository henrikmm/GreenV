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

/// The screens that used to be mockups. These pin them to what the API answered.
void main() {
  testWidgets(
    'lists the stretches the API returned, tallest first, with their reading',
    (tester) async {
      final gateway = _seeded();
      await _open(tester, gateway, MotivaPage.stretches);

      expect(find.text('Trechos medidos'), findsOneWidget);
      expect(find.byType(StretchTile), findsNWidgets(4));
      // The tallest is ranked first, by the API's order and not by anything the screen did.
      expect(find.text('1 · Avenida Armando Ferrentini'), findsOneWidget);
      expect(find.text('914 cm'), findsOneWidget);
      // A stretch with no height says what the detector saw, not "sem vegetação".
      expect(find.text('Vegetação vista, sem altura'), findsOneWidget);
    },
  );

  testWidgets('choosing a level asks the API for that level', (tester) async {
    final gateway = _seeded();
    await _open(tester, gateway, MotivaPage.stretches);

    // The chip carries the count for the whole set, from the summary.
    await tester.tap(find.text('h > 30 cm  2'));
    await tester.pumpAndSettle();

    expect(gateway.lastLevelFilter, 3);
    expect(find.byType(StretchTile), findsNWidgets(2));
    expect(find.text('Vegetação vista, sem altura'), findsNothing);
  });

  testWidgets('opens an order from a stretch with only the plan in the body', (
    tester,
  ) async {
    final gateway = _seeded();
    await _open(tester, gateway, MotivaPage.stretches);

    await tester.tap(find.byType(StretchTile).first);
    await tester.pumpAndSettle();
    await tester.dragUntilVisible(
      find.byKey(const Key('open-order-button')),
      _sheetList,
      const Offset(0, -120),
    );
    await tester.pumpAndSettle();
    expect(find.text('Abrir ordem de serviço'), findsOneWidget);

    await tester.tap(find.byKey(const Key('open-order-button')));
    await tester.pumpAndSettle();
    // Suggested from the level, and changed here to prove the choice travels.
    await tester.tap(find.byKey(const Key('priority-urgente')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('notes-field')),
      'Acesso pela marginal',
    );
    await tester.ensureVisible(find.byKey(const Key('submit-order-button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('submit-order-button')));
    await tester.pumpAndSettle();

    final draft = gateway.opened.single;
    expect(draft.priority, OrderPriority.urgent);
    expect(draft.notes, 'Acesso pela marginal');
    expect(draft.targets.single.sessionId, 's1');
    expect(draft.targets.single.segmentIndex, 0);
    expect(find.text('Ordem OS-ROÇ-202609-1001 aberta.'), findsOneWidget);
  });

  testWidgets('refuses to open an order on a stretch nobody could measure', (
    tester,
  ) async {
    final gateway = _seeded();
    await _open(tester, gateway, MotivaPage.stretches);

    await tester.ensureVisible(find.byType(StretchTile).last);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(StretchTile).last);
    await tester.pumpAndSettle();
    await tester.dragUntilVisible(
      find.byKey(const Key('open-order-button')),
      _sheetList,
      const Offset(0, -120),
    );
    await tester.pumpAndSettle();

    expect(
      find.text('Só um trecho com altura medida pode justificar uma ordem.'),
      findsOneWidget,
    );
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('open-order-button')),
    );
    expect(button.onPressed, isNull);
  });

  testWidgets("shows the API's words and a retry when it cannot load", (
    tester,
  ) async {
    final gateway = _seeded()
      ..failure = const OperationsFailure(503, 'API fora do ar');
    await _open(tester, gateway, MotivaPage.stretches);

    expect(find.byKey(const Key('remote-error')), findsWidgets);
    expect(find.text('API fora do ar'), findsWidgets);
    expect(find.text('Tentar de novo'), findsWidgets);
  });

  testWidgets('the home screen counts what the API counted', (tester) async {
    final gateway = _seeded();
    await _open(tester, gateway, MotivaPage.home);

    expect(find.text('Resumo da operação'), findsOneWidget);
    expect(find.text('ACIMA DE 30 CM'), findsOneWidget);
    expect(find.text('2'), findsWidgets);
    expect(find.text('914 cm'), findsWidgets);
    expect(find.text('Sessões recentes'), findsOneWidget);
    expect(find.textContaining('7 de 8'), findsOneWidget);
  });

  testWidgets('tells two windows of one segment apart, and opens only the '
      'photographs of the one tapped', (tester) async {
    // One uploaded segment, cut into two stretches of 25 m. They sit on the same street and
    // carry the same date, so the range is the only thing that distinguishes them on the list —
    // and before it was read, tapping either opened all four photographs of the segment.
    final gateway = FakeOperationsGateway()
      ..summary = const ReadingsSummary(total: 2, countsByLevel: {3: 1, 1: 1})
      ..stretchRows = [
        _stretch(
          4,
          level: 1,
          height: 0.05,
          place: 'Rua Vergueiro',
          windowIndex: 0,
          startMeters: 0,
          endMeters: 25,
        ),
        _stretch(
          4,
          level: 3,
          height: 0.41,
          place: 'Rua Vergueiro',
          windowIndex: 1,
          startMeters: 25,
          endMeters: 50,
        ),
      ]
      ..frameRows = {
        4: const [
          SampledFrame(
            fileName: 'frame-0001.jpg',
            canonicalFrame: 1,
            imageUrl: 'https://api.example/frames/frame-0001.jpg',
            windowIndex: 0,
          ),
          SampledFrame(
            fileName: 'frame-0002.jpg',
            canonicalFrame: 2,
            imageUrl: 'https://api.example/frames/frame-0002.jpg',
            windowIndex: 0,
          ),
          SampledFrame(
            fileName: 'frame-0003.jpg',
            canonicalFrame: 3,
            imageUrl: 'https://api.example/frames/frame-0003.jpg',
            windowIndex: 1,
          ),
        ],
      };

    await _open(tester, gateway, MotivaPage.stretches);

    expect(find.textContaining('segmento 4 · 0–25 m'), findsOneWidget);
    expect(find.textContaining('segmento 4 · 25–50 m'), findsOneWidget);

    await tester.tap(find.byType(StretchTile).last);
    await tester.pumpAndSettle();

    // The third photograph alone: the two that fed the first window are not this stretch's
    // evidence, and showing them would say the 41 cm was seen in them.
    expect(find.byType(FrameThumb), findsOneWidget);
    expect(find.textContaining('frame-0003.jpg'), findsOneWidget);
  });

  testWidgets(
    'opens a stretch into its photographs and what one of them measured',
    (tester) async {
      final gateway = _seeded();
      await _open(tester, gateway, MotivaPage.stretches);

      await tester.tap(find.byType(StretchTile).first);
      await tester.pumpAndSettle();

      // The first frame is chosen for you, and its bytes come through the gateway rather than
      // through a plain image widget, which could not send the bearer.
      expect(
        gateway.imagesFetched,
        contains('https://api.example/frames/frame-0001.jpg'),
      );
      expect(find.byType(FramePhoto), findsOneWidget);
      expect(find.byType(FrameThumb), findsNWidgets(2));
      expect(find.textContaining('frame-0001.jpg'), findsOneWidget);
      expect(find.text('O QUE ESTE QUADRO MEDIU'), findsOneWidget);
      expect(find.text('58 cm'), findsOneWidget);
      expect(
        find.text('Escolhido como evidência em 2 células.'),
        findsOneWidget,
      );
    },
  );

  testWidgets('choosing another photograph asks for that one\'s reading', (
    tester,
  ) async {
    final gateway = _seeded();
    await _open(tester, gateway, MotivaPage.stretches);

    await tester.tap(find.byType(StretchTile).first);
    await tester.pumpAndSettle();
    // The photograph is 260 px tall, so the strip under it starts below the fold in a 600 px
    // test viewport, exactly as it does on a short phone.
    await tester.dragUntilVisible(
      find.byType(FrameThumb).last,
      _sheetList,
      const Offset(0, -100),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(FrameThumb).last);
    await tester.pumpAndSettle();

    expect(find.textContaining('frame-0002.jpg'), findsOneWidget);
    // Nothing was scripted for the second frame, so the sheet says what that means rather
    // than leaving the first frame's numbers under a different photograph.
    expect(find.textContaining('não votou em nenhuma célula'), findsOneWidget);
  });

  testWidgets('opening a session frames the map on that session alone', (
    tester,
  ) async {
    final gateway = _seeded();
    await _open(tester, gateway, MotivaPage.home);

    await tester.ensureVisible(find.byKey(const Key('session-s1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('session-s1')));
    await tester.pumpAndSettle();

    expect(find.textContaining('desta sessão'), findsOneWidget);
    expect(find.byKey(const Key('clear-session-focus')), findsOneWidget);

    await tester.tap(find.byKey(const Key('clear-session-focus')));
    await tester.pumpAndSettle();
    expect(find.textContaining('desta sessão'), findsNothing);
    expect(find.textContaining('no mapa · toque num ponto'), findsOneWidget);
  });
}

/// The sheet's own scrollable. The frame strip inside it is a ListView too, and dragging that
/// one vertically scrolls nothing at all.
Finder get _sheetList => find
    .descendant(
      of: find.byType(DraggableScrollableSheet),
      matching: find.byType(ListView),
    )
    .first;

FakeOperationsGateway _seeded() => FakeOperationsGateway()
  ..summary = const ReadingsSummary(
    total: 4,
    countsByLevel: {3: 2, 2: 1, 0: 1},
    tallestM: 9.14,
  )
  ..stretchRows = [
    _stretch(0, level: 3, height: 9.14, place: 'Avenida Armando Ferrentini'),
    _stretch(1, level: 3, height: 6.51, place: 'Rua do Paraíso'),
    _stretch(2, level: 2, height: 0.22, place: 'Rua Miranda Guerra'),
    MeasuredStretch(
      sessionId: 's1',
      segmentIndex: 3,
      capturedAt: DateTime.utc(2026, 9, 11, 18, 52),
      cellsMeasured: 0,
      cellsAbstained: 22,
      centreLat: -23.575,
      centreLon: -46.636,
      placeLabel: 'Rua Braz Cubas',
    ),
  ]
  ..frameRows = {
    0: const [
      SampledFrame(
        fileName: 'frame-0001.jpg',
        canonicalFrame: 1,
        imageUrl: 'https://api.example/frames/frame-0001.jpg',
        horizontalAccuracyMeters: 5.2,
        locationQuality: 'good',
      ),
      SampledFrame(
        fileName: 'frame-0002.jpg',
        canonicalFrame: 2,
        imageUrl: 'https://api.example/frames/frame-0002.jpg',
        horizontalAccuracyMeters: 5.4,
        locationQuality: 'good',
      ),
    ],
  }
  ..readingRows = const {
    'frame-0001.jpg': FrameReadings(
      canonicalFrame: 1,
      cellsVoted: 4,
      evidenceForCells: 2,
      extent95MedianM: 0.58,
      extent95MaxM: 0.72,
      largestDisagreementM: 0.22,
    ),
  }
  ..sessionRows = [
    CaptureSessionSummary(
      sessionId: 's1',
      startedAt: DateTime.utc(2026, 9, 11, 18, 51),
      segmentCount: 8,
      measuredSegmentCount: 7,
    ),
  ]
  ..teamRows = const [
    Team(
      teamId: 't1',
      name: 'Equipe 1 – Zona Norte',
      shortName: 'Equipe 1',
      initials: 'E1',
      status: TeamStatus.inField,
      pendingOrders: 1,
      inProgressOrders: 0,
      completedOrders: 2,
    ),
  ];

MeasuredStretch _stretch(
  int index, {
  required int level,
  required double height,
  required String place,
  int? windowIndex,
  double? startMeters,
  double? endMeters,
}) => MeasuredStretch(
  sessionId: 's1',
  segmentIndex: index,
  capturedAt: DateTime.utc(2026, 9, 11, 18, 51, index),
  windowIndex: windowIndex,
  windowStartMeters: startMeters,
  windowEndMeters: endMeters,
  level: level,
  extent95P95M: height,
  cellsMeasured: 10,
  cellsAbstained: 2,
  centreLat: -23.57 - index * 0.001,
  centreLon: -46.63,
  locationQuality: 'good',
  placeLabel: place,
  placeDetail: 'São Paulo',
);

Future<void> _open(
  WidgetTester tester,
  FakeOperationsGateway gateway,
  MotivaPage page,
) async {
  final coordinator = _coordinator();
  addTearDown(coordinator.dispose);
  final auth = SessionAuthenticator(
    tokenUri: Uri.parse('https://api.example/v2/oauth/token'),
    store: MemorySessionStore(),
    client: MockClient((_) async => http.Response('{}', 401)),
  )..signedIn.value = true;

  await tester.pumpWidget(
    CaptureApp(
      dependencies: AppDependencies(
        operations: gateway,
        capture: coordinator,
        authenticator: auth,
      ),
      initialPage: page,
    ),
  );
  await tester.pumpAndSettle();
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
    utcNow: () => DateTime.utc(2026, 9, 4, 12),
  );
}
