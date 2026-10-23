import 'package:flutter_test/flutter_test.dart';
import 'package:greenv_capture/src/capture/capture_error_message.dart';

void main() {
  test('names a refused permission on the phone and in the browser', () {
    for (final raw in [
      'CameraException(CameraAccessDenied, User denied camera access.)',
      'CameraException(CameraAccessDeniedWithoutPrompt, ...)',
      'NotAllowedError: Permission denied',
    ]) {
      expect(
        describeCaptureError(Exception(raw)),
        startsWith('Permissão da câmera negada.'),
        reason: raw,
      );
    }
  });

  test('tells a missing camera from a busy one', () {
    expect(
      describeCaptureError(StateError('no camera is available')),
      'Nenhuma câmera disponível neste aparelho.',
    );
    expect(
      describeCaptureError(StateError('no usable capture format on this camera: x')),
      startsWith('A câmera está ocupada'),
    );
  });

  test('keeps the original text of anything it does not recognise', () {
    expect(
      describeCaptureError(StateError('capture session is not registered')),
      endsWith('capture session is not registered'),
    );
  });
}
