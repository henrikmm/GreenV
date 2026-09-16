import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';

/// A segment the phone uploaded is no longer a stretch: the extractor cuts it into windows of
/// about 25 m and each is measured on its own. These are the places where the app used to assume
/// otherwise.
void main() {
  test('a window is identified by its segment and its own index', () {
    final first = _stretch(windowIndex: 0);
    final second = _stretch(windowIndex: 1);

    // Nine windows of a segment shared one key before the index was read, so selecting one
    // selected all nine and opening one opened all nine.
    expect(first.key, isNot(second.key));
    expect(first.key, 's1:4:0');
    expect(_stretch().key, 's1:4:inteiro');
  });

  test('a window says which stretch of the segment it is', () {
    expect(
      _stretch(windowIndex: 1, start: 25, end: 50).stretchLabel,
      'segmento 4 · 25–50 m',
    );
  });

  test('a window with no interval recorded still names itself', () {
    expect(_stretch(windowIndex: 2).stretchLabel, 'segmento 4 · trecho 2');
    expect(_stretch(windowIndex: 2).range, isNull);
  });

  test('a segment measured whole announces no cut', () {
    // The readings taken before the extractor started cutting. They are still in the database
    // and a row about 200 m must not claim to be about 25.
    expect(_stretch().stretchLabel, isNull);
    expect(_stretch().range, isNull);
  });

  test('the readings feed carries the window through', () {
    final stretch = MeasuredStretch.fromJson(const {
      'sessionId': 's1',
      'segmentIndex': 4,
      'capturedAt': '2026-09-13T14:05:05Z',
      'windowIndex': 3,
      'windowStartMeters': 75.0,
      'windowEndMeters': 99.4,
      'measurementLevel': 3,
      'measurementExtent95P95M': 0.41,
    });

    expect(stretch.windowIndex, 3);
    expect(stretch.range, '75–99 m');
    expect(stretch.heightCm, 41);
  });

  test('a photograph says which window it fed', () {
    final frame = SampledFrame.fromJson(const {
      'fileName': 'frame-0007.jpg',
      'canonicalFrame': 7,
      'imageUrl': 'https://api.example/frames/frame-0007.jpg',
      'windowIndex': 2,
    });

    expect(frame.windowIndex, 2);
  });
}

MeasuredStretch _stretch({int? windowIndex, double? start, double? end}) =>
    MeasuredStretch(
      sessionId: 's1',
      segmentIndex: 4,
      capturedAt: DateTime.utc(2026, 9, 13, 14, 5),
      windowIndex: windowIndex,
      windowStartMeters: start,
      windowEndMeters: end,
    );
