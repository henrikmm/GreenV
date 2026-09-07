import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/identifier/uuid_v7_identifier_adapter.dart';
import 'package:uuid/uuid_value.dart';

void main() {
  test('generates RFC 9562 version 7 identifiers with the clock timestamp', () {
    final timestamp = DateTime.utc(2026, 8, 26, 12, 34, 56, 789);
    final generator = UuidV7IdentifierAdapter(utcNow: () => timestamp);

    final identifier = generator.next();

    expect(UuidValue.withValidation(identifier).isV7, isTrue);
    expect(_timestampMillis(identifier), timestamp.millisecondsSinceEpoch);
  });

  test('keeps identifiers ordered when generated in the same millisecond', () {
    final timestamp = DateTime.utc(2026, 8, 26, 12, 34, 56, 789);
    final generator = UuidV7IdentifierAdapter(utcNow: () => timestamp);

    final identifiers = List.generate(100, (_) => generator.next());
    final sorted = [...identifiers]..sort();

    expect(identifiers.toSet(), hasLength(identifiers.length));
    expect(identifiers, sorted);
    for (var index = 1; index < identifiers.length; index++) {
      expect(
        _timestampMillis(identifiers[index]),
        _timestampMillis(identifiers[index - 1]) + 1,
      );
    }
  });
}

int _timestampMillis(String identifier) {
  final timestampHex = identifier.substring(0, 8) + identifier.substring(9, 13);
  return int.parse(timestampHex, radix: 16);
}
