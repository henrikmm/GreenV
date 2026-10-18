// Exercises the phone's gateway client against a running intake, without a phone:
//   dart run tool/gateway_smoke.dart http://<mac>:8790 <code> <video.mp4>
// It announces a capture, streams the file and prints the phases the gateway reports.
import 'dart:io';

import 'package:greenv_capture/src/demo/gateway_client.dart';

Future<void> main(List<String> args) async {
  if (args.length != 3) {
    stderr.writeln('usage: dart run tool/gateway_smoke.dart <base-url> <code> <video>');
    exit(64);
  }
  final client = GatewayClient(baseUri: Uri.parse(args[0]), pairingCode: args[1]);
  final health = await client.health();
  stdout.writeln('health: reachable=${health.reachable} authorised=${health.authorised} contract=${health.contract} ${health.problem ?? ''}');
  if (!health.ready) exit(1);
  final file = File(args[2]);
  final id = await client.announce({
    'schema': 'verge-phone-capture/1',
    'capturedAt': DateTime.now().toUtc().toIso8601String(),
    'durationMs': 4000,
    'settings': {'resolution': '1080p', 'fps': 30},
    'applied': {'source': 'gateway_smoke'},
    'bytes': await file.length(),
  });
  final watch = Stopwatch()..start();
  await client.uploadVideo(id, file);
  stdout.writeln('uploaded $id in ${watch.elapsedMilliseconds} ms');
  final status = await client.status(id);
  for (final p in status.phases) {
    stdout.writeln('  ${p.phase.padRight(10)} +${p.tMs} ms ${p.detail ?? ''}');
  }
  client.close();
}
