import 'package:camera/camera.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/capture/camera_segment_recorder.dart';

/// The ladder decides what every capture is worth, and nothing at runtime reports what it chose —
/// no platform returns the frame rate it settled on. So the policy is pinned here instead.
void main() {
  final ladder = CameraSegmentRecorder.preferredFormats;

  test('asks for the highest frame rate first', () {
    final rates = ladder.map((f) => f.fps ?? 0).toList();
    expect(rates, isSortedDescending);
    expect(rates.first, 240);
  });

  test('reaches into the range that carries highway speed back inside the graded envelope', () {
    // 120 fps is what puts 86 views in a 20 m stretch at 100 km/h, against 22 at 30 fps.
    expect(ladder.where((f) => f.fps != null && f.fps! >= 120), hasLength(4));
  });

  test('prefers the lower resolution at equal frame rate', () {
    for (var i = 1; i < ladder.length; i++) {
      if (ladder[i].fps == ladder[i - 1].fps) {
        // The worker downscales to a 1024 px long edge, so 1080p only earns its place when a
        // device offers a rate there that it does not offer at 720p.
        expect(ladder[i - 1].resolution, ResolutionPreset.high);
        expect(ladder[i].resolution, ResolutionPreset.veryHigh);
      }
    }
  });

  test('never asks for more resolution than the worker keeps', () {
    // 4K would discard 93% of its pixels before inference and cost the 64 MB upload budget.
    expect(ladder.map((f) => f.resolution), everyElement(isNot(ResolutionPreset.ultraHigh)));
    expect(ladder.map((f) => f.resolution), everyElement(isNot(ResolutionPreset.max)));
  });

  test('ends with a rung that asks for no rate, so a plain 30 fps phone still records', () {
    expect(ladder.last.fps, isNull);
    expect(ladder.last.resolution, ResolutionPreset.high);
  });
}

Matcher get isSortedDescending => predicate<List<int>>(
      (values) {
        for (var i = 1; i < values.length; i++) {
          if (values[i] > values[i - 1]) return false;
        }
        return true;
      },
      'is sorted from highest to lowest',
    );
