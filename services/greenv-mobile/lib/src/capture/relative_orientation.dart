import 'dart:math' as math;

final class RelativeOrientation {
  double x = 0;
  double y = 0;
  double z = 0;
  double w = 1;

  void reset() {
    x = 0;
    y = 0;
    z = 0;
    w = 1;
  }

  void integrate({
    required double radiansPerSecondX,
    required double radiansPerSecondY,
    required double radiansPerSecondZ,
    required double elapsedSeconds,
  }) {
    if (elapsedSeconds <= 0 || elapsedSeconds > 0.25) return;
    final nextX =
        x +
        0.5 *
            (w * radiansPerSecondX +
                y * radiansPerSecondZ -
                z * radiansPerSecondY) *
            elapsedSeconds;
    final nextY =
        y +
        0.5 *
            (w * radiansPerSecondY +
                z * radiansPerSecondX -
                x * radiansPerSecondZ) *
            elapsedSeconds;
    final nextZ =
        z +
        0.5 *
            (w * radiansPerSecondZ +
                x * radiansPerSecondY -
                y * radiansPerSecondX) *
            elapsedSeconds;
    final nextW =
        w -
        0.5 *
            (x * radiansPerSecondX +
                y * radiansPerSecondY +
                z * radiansPerSecondZ) *
            elapsedSeconds;
    final magnitude = math.sqrt(
      nextX * nextX + nextY * nextY + nextZ * nextZ + nextW * nextW,
    );
    if (magnitude == 0 || !magnitude.isFinite) return;
    x = nextX / magnitude;
    y = nextY / magnitude;
    z = nextZ / magnitude;
    w = nextW / magnitude;
  }
}
