// The FIAP Next demo build: one screen that records an object and hands the video to a Verge
// Studio gateway. It is a test build beside the field app, not a replacement for it — see
// docs/NEXT-DEMO.md. Run it with:
//
//   flutter run -t lib/main_demo.dart --dart-define=VERGE_GATEWAY_URL=http://<mac>:8790 \
//     --dart-define=VERGE_GATEWAY_CODE=<code>
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:greenv_capture/src/demo/demo_screen.dart';
import 'package:greenv_capture/src/demo/verge_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const DemoApp());
}

final class DemoApp extends StatelessWidget {
  const DemoApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Verge · medir objeto',
    debugShowCheckedModeBanner: false,
    theme: Verge.theme(),
    home: const DemoScreen(),
  );
}
