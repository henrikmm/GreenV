import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/capture/relative_orientation.dart';

void main() {
  test('integrates a normalized segment-relative orientation', () {
    final orientation = RelativeOrientation();

    for (var step = 0; step < 1000; step += 1) {
      orientation.integrate(
        radiansPerSecondX: 0,
        radiansPerSecondY: 0,
        radiansPerSecondZ: math.pi / 2,
        elapsedSeconds: 0.001,
      );
    }

    expect(orientation.x, closeTo(0, 0.001));
    expect(orientation.y, closeTo(0, 0.001));
    expect(orientation.z, closeTo(math.sqrt1_2, 0.001));
    expect(orientation.w, closeTo(math.sqrt1_2, 0.001));
    expect(
      orientation.x * orientation.x +
          orientation.y * orientation.y +
          orientation.z * orientation.z +
          orientation.w * orientation.w,
      closeTo(1, 0.000001),
    );
  });

  test('ignores a stalled sensor interval instead of applying a jump', () {
    final orientation = RelativeOrientation();
    orientation.integrate(
      radiansPerSecondX: 2,
      radiansPerSecondY: 1,
      radiansPerSecondZ: 3,
      elapsedSeconds: 1,
    );

    expect(
      [orientation.x, orientation.y, orientation.z, orientation.w],
      [0, 0, 0, 1],
    );
  });
}
