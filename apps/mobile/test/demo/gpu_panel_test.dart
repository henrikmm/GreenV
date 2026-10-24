import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/demo/demo_widgets.dart';
import 'package:greenv_capture/src/demo/gateway_client.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Map<String, Object?> _status({required bool keepWarm, int ready = 0}) => {
  'keepWarm': keepWarm,
  'workersMin': keepWarm ? 1 : 0,
  'workersMax': 2,
  'idleTimeoutSeconds': 60,
  'workers': {'idle': 0, 'initializing': 0, 'ready': ready, 'running': 0, 'throttled': 0, 'unhealthy': 0},
};

Future<void> _show(WidgetTester tester, GatewayClient? Function() client) => tester.pumpWidget(
  MaterialApp(home: Scaffold(body: GpuPanel(client: client))),
);

Switch _switch(WidgetTester tester) => tester.widget<Switch>(find.byType(Switch));

void main() {
  testWidgets('reads the GPU on open and turns it on with the saved code', (tester) async {
    final requests = <http.Request>[];
    var warm = false;
    await _show(
      tester,
      () => GatewayClient(
        baseUri: Uri.parse('https://greenvapi.matomomitsu.com/v1/demo/'),
        pairingCode: 'secret',
        client: MockClient((r) async {
          requests.add(r);
          if (r.method == 'PUT') warm = (jsonDecode(r.body) as Map)['keepWarm'] == true;
          return http.Response(jsonEncode(_status(keepWarm: warm, ready: warm ? 1 : 0)), 200);
        }),
      ),
    );
    await tester.pump();

    expect(find.text('GPU DA DEMONSTRAÇÃO'), findsOneWidget);
    expect(find.text('Desligada (liga sozinha a cada captura)'), findsOneWidget);
    expect(find.textContaining('US\$ 3,50 por hora'), findsOneWidget);
    expect(_switch(tester).value, isFalse);
    expect(_switch(tester).onChanged, isNotNull);

    await tester.tap(find.byType(Switch));
    await tester.pump();
    await tester.pump();

    final put = requests.singleWhere((r) => r.method == 'PUT');
    expect(put.headers['authorization'], 'Bearer secret');
    expect(jsonDecode(put.body), {'keepWarm': true});
    expect(find.text('Ligada · 1 pronta'), findsOneWidget);
    expect(_switch(tester).value, isTrue);

    // It polls while open and stops once it is gone.
    final before = requests.length;
    await tester.pump(const Duration(seconds: 10));
    expect(requests.length, before + 1);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 30));
    expect(requests.length, before + 1);
  });

  testWidgets('a wrong code is named as such and the switch returns', (tester) async {
    await _show(
      tester,
      () => GatewayClient(
        baseUri: Uri.parse('https://greenvapi.matomomitsu.com/v1/demo/'),
        pairingCode: 'wrong',
        client: MockClient((r) async => r.method == 'PUT'
            ? http.Response(jsonEncode({'detail': 'bad code'}), 403)
            : http.Response(jsonEncode(_status(keepWarm: false)), 200)),
      ),
    );
    await tester.pump();
    await tester.tap(find.byType(Switch));
    await tester.pump();
    await tester.pump();

    expect(find.text('Código de pareamento incorreto'), findsOneWidget);
    expect(_switch(tester).value, isFalse);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('without a saved code the switch is disabled and says why', (tester) async {
    await _show(
      tester,
      () => GatewayClient(
        baseUri: Uri.parse('https://greenvapi.matomomitsu.com/v1/demo/'),
        pairingCode: '',
        client: MockClient((r) async => http.Response(jsonEncode(_status(keepWarm: true, ready: 1)), 200)),
      ),
    );
    await tester.pump();

    expect(find.text('Ligada · 1 pronta'), findsOneWidget);
    expect(find.text('Informe o código de pareamento para controlar a GPU'), findsOneWidget);
    expect(_switch(tester).onChanged, isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('an old server without the route says so instead of a switch state', (tester) async {
    await _show(
      tester,
      () => GatewayClient(
        baseUri: Uri.parse('http://192.168.0.10:8790'),
        pairingCode: '123456',
        client: MockClient((r) async => http.Response('not found', 404)),
      ),
    );
    await tester.pump();

    expect(find.text('Este servidor não controla a GPU'), findsOneWidget);
    expect(_switch(tester).onChanged, isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
