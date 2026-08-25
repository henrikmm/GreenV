import 'dart:async';

import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

final class MonotonicClock {
  MonotonicClock() {
    _stopwatch.start();
  }

  final Stopwatch _stopwatch = Stopwatch();

  int nowNanos() => _stopwatch.elapsedMicroseconds * 1000;
}

final class TimerSegmentScheduler implements SegmentScheduler {
  Timer? _timer;

  @override
  void schedule(Duration duration, Future<void> Function() callback) {
    cancel();
    _timer = Timer(duration, callback);
  }

  @override
  void cancel() {
    _timer?.cancel();
    _timer = null;
  }
}

final class WakelockForegroundLease implements ForegroundLease {
  @override
  Future<void> acquire() => WakelockPlus.enable();

  @override
  Future<void> release() => WakelockPlus.disable();
}
