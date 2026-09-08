/// Compile-time capture configuration. `String.fromEnvironment` only reads `--dart-define`, so
/// these are baked into the build; there is no runtime environment variable to change afterwards.
final class CaptureConfiguration {
  const CaptureConfiguration._();

  /// Android's alias for the development host, which is what a local Compose stack listens on.
  static const String defaultApiUrl = 'http://10.0.2.2:8080';

  static const String apiUrl = String.fromEnvironment(
    'GREENV_API_URL',
    defaultValue: defaultApiUrl,
  );

  /// Fallback shared token, kept for the pilot capture builds that still ship with one. A signed-in
  /// person's token takes precedence: this is only used before anyone has signed in.
  static const String apiToken = String.fromEnvironment('GREENV_API_TOKEN');

  /// The browser build behaves like production by default: real webcam, real sign-in, real uploads
  /// to [apiUrl].
  ///
  /// Pass `--dart-define=GREENV_WEB_CAPTURE=false` for the design preview, where the camera, the
  /// backend and the session are all mocked and every screen can be opened with `?screen=`. That is
  /// for looking at layout, never for exercising the pipeline - which is why it is the one that has
  /// to be asked for.
  static const bool webCaptureEnabled = bool.fromEnvironment(
    'GREENV_WEB_CAPTURE',
    defaultValue: true,
  );

  static Uri get apiUri => Uri.parse(apiUrl);

  static Uri get tokenUri => apiUri.resolve('/v2/oauth/token');
}
