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

  /// The browser build is a design preview unless this is set. With it, the page opens the real
  /// webcam and uploads to [apiUrl], which is how a workstation exercises the cloud pipeline.
  static const bool webCaptureEnabled = bool.fromEnvironment('GREENV_WEB_CAPTURE');

  static Uri get apiUri => Uri.parse(apiUrl);

  static Uri get tokenUri => apiUri.resolve('/v2/oauth/token');
}
