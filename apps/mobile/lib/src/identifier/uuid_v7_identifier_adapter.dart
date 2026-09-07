import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:uuid/data.dart';
import 'package:uuid/uuid.dart';

/// RFC 9562 UUIDv7 generator that remains ordered if the clock stalls or rolls back.
final class UuidV7IdentifierAdapter implements IdentifierGenerator {
  UuidV7IdentifierAdapter({DateTime Function()? utcNow})
    : _utcNow = utcNow ?? (() => DateTime.now().toUtc());

  final Uuid _uuid = const Uuid();
  final DateTime Function() _utcNow;
  int _lastTimestampMillis = -1;

  @override
  String next() {
    final wallClockMillis = _utcNow().toUtc().millisecondsSinceEpoch;
    final timestampMillis = wallClockMillis > _lastTimestampMillis
        ? wallClockMillis
        : _lastTimestampMillis + 1;
    _lastTimestampMillis = timestampMillis;
    return _uuid.v7(config: V7Options(timestampMillis, null));
  }
}
