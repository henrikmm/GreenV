import 'dart:math' as math;

/// How far the phone has turned around the vertical while recording, and whether it is turning
/// too fast.
///
/// Walking around an object while keeping it centred turns the camera about the world's vertical
/// by the same angle as the walk, so the rotation rate projected on gravity, integrated, is the
/// arc the person has covered. The projection is what keeps a tilt or a wobble from counting.
///
/// Gravity comes from the accelerometer through a low-pass filter. The phone is assumed to be
/// moving gently, which is also what the reconstruction wants.
final class OrbitTracker {
  OrbitTracker({this.tooFastRadPerSecond = 0.8, this.smoothing = 0.15});

  /// Above this, frames blur and neighbouring views share too little. 0.8 rad/s is ~46°/s;
  /// a 90° arc at a comfortable pace takes six to ten seconds, well under it.
  final double tooFastRadPerSecond;

  /// Exponential smoothing weight for the speed estimate; small means slower to react.
  final double smoothing;

  double _gx = 0, _gy = 0, _gz = 0;
  bool _hasGravity = false;
  double _yawRadians = 0;
  double _speed = 0;
  double _peakSpeed = 0;
  int _tooFastEvents = 0;
  bool _wasTooFast = false;

  /// Net turn about the vertical, in degrees, whichever direction the person walks.
  double get degrees => _yawRadians.abs() * 180 / math.pi;

  /// Smoothed angular speed in degrees per second.
  double get degreesPerSecond => _speed * 180 / math.pi;

  double get peakDegreesPerSecond => _peakSpeed * 180 / math.pi;

  bool get tooFast => _speed > tooFastRadPerSecond;

  /// How many separate times the speed crossed the warning line.
  int get tooFastEvents => _tooFastEvents;

  void reset() {
    _yawRadians = 0;
    _speed = 0;
    _peakSpeed = 0;
    _tooFastEvents = 0;
    _wasTooFast = false;
  }

  void addAccelerometer(double x, double y, double z) {
    if (!_hasGravity) {
      _gx = x;
      _gy = y;
      _gz = z;
      _hasGravity = true;
      return;
    }
    const a = 0.1;
    _gx += a * (x - _gx);
    _gy += a * (y - _gy);
    _gz += a * (z - _gz);
  }

  /// One gyroscope sample in rad/s, [dtSeconds] after the previous one.
  void addGyroscope(double x, double y, double z, double dtSeconds) {
    if (dtSeconds <= 0 || dtSeconds > 0.5) return;
    final magnitude = math.sqrt(x * x + y * y + z * z);
    _speed += smoothing * (magnitude - _speed);
    if (_speed > _peakSpeed) _peakSpeed = _speed;
    final nowTooFast = tooFast;
    if (nowTooFast && !_wasTooFast) _tooFastEvents++;
    _wasTooFast = nowTooFast;

    if (!_hasGravity) return;
    final g = math.sqrt(_gx * _gx + _gy * _gy + _gz * _gz);
    if (g < 1e-6) return;
    final yawRate = (x * _gx + y * _gy + z * _gz) / g;
    _yawRadians += yawRate * dtSeconds;
  }

  Map<String, Object?> toJson() => {
    'degrees': double.parse(degrees.toStringAsFixed(1)),
    'peakDegreesPerSecond': double.parse(peakDegreesPerSecond.toStringAsFixed(1)),
    'tooFastEvents': _tooFastEvents,
  };
}
