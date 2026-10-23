/// What the capture screen says when recording cannot start or a segment cannot be closed.
///
/// The coordinator used to show `error.toString()`, so a refused camera permission reached the
/// field worker as `CameraException(CameraAccessDenied, ...)`. The errors come from three plugins
/// and the browser, none of which this layer may import - the coordinator also runs on web - so
/// they are recognised by the words they carry. Anything unrecognised keeps its own text after a
/// sentence a person can act on, because that text is what support will ask for.
String describeCaptureError(Object error) {
  final raw = error.toString();
  if (_anyOf(raw, const [
    'CameraAccessDenied',
    'CameraAccessRestricted',
    'cameraPermission',
    'NotAllowedError',
    'PermissionDenied',
  ])) {
    return 'Permissão da câmera negada. Libere o acesso à câmera nas configurações do aparelho '
        'e toque em "Iniciar coleta" de novo.';
  }
  if (_anyOf(raw, const ['no camera is available', 'NotFoundError'])) {
    return 'Nenhuma câmera disponível neste aparelho.';
  }
  if (_anyOf(raw, const [
    'no usable capture format',
    'no camera could be opened',
    'NotReadableError',
    'cameraNotReadable',
  ])) {
    return 'A câmera está ocupada ou não aceitou gravar. Feche outros aplicativos que usam a '
        'câmera e tente de novo.';
  }
  if (_anyOf(raw, const ['FileSystemException', 'No space left', 'ENOSPC'])) {
    return 'Não foi possível salvar o vídeo no aparelho. Verifique o espaço livre; os segmentos '
        'já gravados continuam na fila.';
  }
  return 'Não foi possível continuar a coleta. Tente de novo. Detalhe técnico: $raw';
}

bool _anyOf(String text, List<String> needles) => needles.any(text.contains);
