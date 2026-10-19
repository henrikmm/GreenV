// Exercises the phone's gateway client against a running intake, without a phone:
//   dart run tool/gateway_smoke.dart <base-url> <code> <video.mp4> [--wait]
// <base-url> is what the phone is given: the cloud API's prefix
// (https://greenvapi.matomomitsu.com/v1/demo/, or http://localhost:8080/v1/demo/ locally) or the
// Mac gateway with no path (http://<mac>:8790, read as /v1/).
// It announces a capture, streams the file and prints the phases the gateway reports. With
// --wait it keeps polling until the capture is fetched, measured or failed, and exits non-zero
// unless it ended well.
import 'dart:io';

import 'package:greenv_capture/src/demo/gateway_client.dart';

Future<void> main(List<String> args) async {
  final wait = args.contains('--wait');
  final positional = [for (final a in args) if (a != '--wait') a];
  if (positional.length != 3) {
    stderr.writeln('usage: dart run tool/gateway_smoke.dart <base-url> <code> <video> [--wait]');
    exit(64);
  }
  final client = GatewayClient(baseUri: Uri.parse(positional[0]), pairingCode: positional[1]);
  stdout.writeln('base: ${client.baseUri}');
  final health = await client.health();
  stdout.writeln('health: reachable=${health.reachable} authorised=${health.authorised} contract=${health.contract} ${health.problem ?? ''}');
  if (!health.ready) exit(1);
  final file = File(positional[2]);
  final bytes = await file.length();
  final tooLarge = videoSizeProblem(bytes);
  if (tooLarge != null) {
    stderr.writeln(tooLarge);
    exit(1);
  }
  final id = await client.announce({
    'schema': 'verge-phone-capture/1',
    'capturedAt': DateTime.now().toUtc().toIso8601String(),
    'durationMs': 4000,
    'settings': {'resolution': '1080p', 'fps': 30},
    'applied': {'source': 'gateway_smoke'},
    'bytes': bytes,
  });
  final watch = Stopwatch()..start();
  // The API stores the video under the extension its content-type names, and refuses anything else.
  final lower = file.path.toLowerCase();
  final contentType = lower.endsWith('.mov') ? 'video/quicktime' : lower.endsWith('.webm') ? 'video/webm' : 'video/mp4';
  await client.uploadVideo(id, file, contentType: contentType);
  stdout.writeln('uploaded $id in ${watch.elapsedMilliseconds} ms');

  var status = await client.status(id);
  if (wait) {
    final deadline = DateTime.now().add(const Duration(minutes: 15));
    var printed = 0;
    while (true) {
      for (final p in status.phases.skip(printed)) {
        stdout.writeln('  ${p.phase.padRight(16)} +${p.tMs} ms ${p.detail ?? ''}');
      }
      printed = status.phases.length;
      if (status.finished || DateTime.now().isAfter(deadline)) break;
      await Future<void>.delayed(const Duration(seconds: 1));
      status = await client.status(id);
    }
    stdout.writeln('state: ${status.state}');
  } else {
    for (final p in status.phases) {
      stdout.writeln('  ${p.phase.padRight(16)} +${p.tMs} ms ${p.detail ?? ''}');
    }
  }
  client.close();
  if (wait && status.state != 'fetched' && status.state != 'measured') exit(1);
}
