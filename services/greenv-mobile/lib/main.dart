import 'package:flutter/widgets.dart';
import 'package:greenv_capture/src/bootstrap/bootstrap.dart';
import 'package:greenv_capture/src/ui/capture_app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final dependencies = await createAppDependencies();
  runApp(CaptureApp(dependencies: dependencies));
}
