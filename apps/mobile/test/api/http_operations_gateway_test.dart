import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/api/http_operations_gateway.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// What goes over the wire, and what comes back as a screen can use it.
///
/// These pin the contract the phone shares with the dashboard: the same routes, the same query
/// names, the same body for opening an order. A drift here is a field worker seeing a different
/// list from the operations room, so it is pinned by name rather than trusted.
void main() {
  test(
    'asks for the tallest first, one page at a time, as the signed-in person',
    () async {
      late http.Request seen;
      final gateway = _gateway((request) {
        seen = request;
        return (_page, 200);
      });

      final page = await gateway.stretches(level: 3, limit: 25, offset: 50);

      expect(seen.method, 'GET');
      expect(seen.url.path, '/v2/measurements');
      expect(seen.url.queryParameters, {
        'sort': 'HEIGHT_DESC',
        'limit': '25',
        'offset': '50',
        'level': '3',
      });
      expect(seen.headers['authorization'], 'Bearer tok');
      expect(page.total, 9);
      expect(page.hasMore, isTrue);
      expect(page.items.single.place.label, 'Rua do Paraíso');
      expect(
        page.items.single.place.detail,
        'nº aprox. 741 · Paraíso · São Paulo',
      );
      expect(page.items.single.heightCm, 651);
      expect(page.items.single.vegetationLevel, VegetationLevel.high);
    },
  );

  test('sends only the plan when opening an order, never the numbers', () async {
    late http.Request seen;
    final gateway = _gateway((request) {
      seen = request;
      return (_order, 201);
    });

    final order = await gateway.openOrder(
      OrderDraft(
        priority: OrderPriority.high,
        scheduledFor: DateTime(2026, 9, 14),
        notes: '   ',
        targets: [_stretch()],
      ),
    );

    expect(seen.method, 'POST');
    expect(seen.url.path, '/v2/service-orders');
    expect(seen.headers['content-type'], startsWith('application/json'));
    // The area, the level and the position are the API's to derive from the targets; a body
    // that carried them could open an order claiming a height nobody measured.
    expect(jsonDecode(seen.body), {
      'priority': 'alta',
      'teamId': null,
      'scheduledFor': '2026-09-14',
      'notes': null,
      'targets': [
        {
          'sessionId': '01a09274-8b0c-71b8-8aa9-59a766b50711',
          'segmentIndex': 1,
          'windowIndex': null,
        },
      ],
    });
    expect(order.reference, 'OS-ROÇ-202609-1003');
    expect(order.status, OrderStatus.pending);
  });

  test('names the window the order is for, not the whole segment', () async {
    late http.Request seen;
    final gateway = _gateway((request) {
      seen = request;
      return (_order, 201);
    });

    await gateway.openOrder(
      OrderDraft(
        priority: OrderPriority.high,
        targets: [_stretch(windowIndex: 4)],
      ),
    );

    // A window is 25 m of road. Dropping the index would raise the order over the two hundred
    // metres of its segment, at whatever height that segment's summary carries.
    expect((jsonDecode(seen.body) as Map<String, Object?>)['targets'], [
      {
        'sessionId': '01a09274-8b0c-71b8-8aa9-59a766b50711',
        'segmentIndex': 1,
        'windowIndex': 4,
      },
    ]);
  });

  test("reports the API's own words when it refuses", () async {
    final gateway = _gateway(
      (_) => (
        '{"type":"urn:greenv:error:invalid_request","title":"invalid_request",'
            '"status":400,"detail":"targets não deve estar vazio"}',
        400,
      ),
    );

    await expectLater(
      gateway.openOrder(
        OrderDraft(priority: OrderPriority.low, targets: const []),
      ),
      throwsA(
        isA<OperationsFailure>()
            .having((f) => f.status, 'status', 400)
            .having((f) => f.detail, 'detail', 'targets não deve estar vazio'),
      ),
    );
  });

  test('reads the teams route as the bare list it is', () async {
    final gateway = _gateway((_) => (_teams, 200));

    final teams = await gateway.teams();

    expect(teams.map((t) => t.shortName), ['Equipe 1', 'Terceirizada']);
    expect(teams.first.status, TeamStatus.inField);
    expect(teams.first.pendingOrders, 1);
  });
}

/// Builds the client once, in UTF-8. `http.Response(body, status)` with no charset encodes the
/// body as Latin-1, and the en dash in "Zona Norte" has nowhere to go there — so the responder
/// hands back text and a status, and the encoding is decided here, the way the API declares it.
HttpOperationsGateway _gateway((String, int) Function(http.Request) respond) =>
    HttpOperationsGateway(
      baseUri: Uri.parse('https://api.example'),
      auth: _StaticToken(),
      client: MockClient((request) async {
        final (body, status) = respond(request);
        return http.Response.bytes(
          utf8.encode(body),
          status,
          headers: const {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    );

final class _StaticToken implements AuthTokenProvider {
  @override
  Future<String> accessToken() async => 'tok';

  @override
  Future<bool> refresh() async => true;
}

MeasuredStretch _stretch({int? windowIndex}) => MeasuredStretch(
  sessionId: '01a09274-8b0c-71b8-8aa9-59a766b50711',
  segmentIndex: 1,
  capturedAt: DateTime.utc(2026, 9, 11, 18, 51),
  windowIndex: windowIndex,
  level: 3,
  extent95P95M: 6.5,
);

const _page = '''
{"schemaVersion":1,"items":[{"schemaVersion":1,
  "sessionId":"01a09274-8b0c-71b8-8aa9-59a766b50711","segmentIndex":1,"state":"ready",
  "idempotencyKey":"k","capturedAt":"2026-09-11T18:51:59Z","durationMillis":10000,
  "measurementState":"measured","measuredAt":"2026-09-11T19:20:54Z",
  "measurementLevel":3,"measurementExtent95P95M":6.5089928689792576,
  "measurementExtent95MaxM":7.1,"measurementCellsMeasured":6,"measurementCellsAbstained":0,
  "measurementCoverage":1.0,"trackCenterLat":-23.5749,"trackCenterLon":-46.6360,
  "trackLocationQuality":"good","placeLabel":"Rua do Paraíso","placeDetail":"Paraíso · São Paulo",
  "placeHouseNumber":"741","placeRoad":null,"placeKm":null}],
 "total":9,"limit":25,"offset":50,"hasMore":true}
''';

const _order = '''
{"schemaVersion":1,"orderId":"2b0f5d2e-0000-4000-8000-000000000001",
 "reference":"OS-ROÇ-202609-1003","status":"pendente","priority":"alta","teamId":null,
 "scheduledFor":"2026-09-14","notes":null,"equipment":"Trator com braço articulado",
 "areaSquareMetres":77.36,"vegetationLevel":3,"centreLat":-23.57,"centreLon":-46.63,
 "createdBy":"admin@greenv.com.br","createdAt":"2026-09-13T08:00:00Z",
 "updatedAt":"2026-09-13T08:00:00Z",
 "targets":[{"sessionId":"01a09274-8b0c-71b8-8aa9-59a766b50711","segmentIndex":1}],
 "history":[]}
''';

const _teams = '''
[{"schemaVersion":1,"teamId":"t1","slug":"equipe-1","name":"Equipe 1 – Zona Norte",
  "shortName":"Equipe 1","region":"KM 0 – 10","colour":"#5e22f3","initials":"E1",
  "status":"em_campo","pendingOrders":1,"inProgressOrders":0,"completedOrders":2,"totalOrders":3},
 {"schemaVersion":1,"teamId":"t4","slug":"terceirizada","name":"Terceirizada",
  "shortName":"Terceirizada","region":"Sob demanda","colour":"#777","initials":"TC",
  "status":"disponivel","pendingOrders":0,"inProgressOrders":0,"completedOrders":0,"totalOrders":0}]
''';
