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

  /// The deployed API rejects every route but `/actuator/health` without a Bearer token. Empty is
  /// only workable against an unauthenticated local stack.
  static const String apiToken = String.fromEnvironment('GREENV_API_TOKEN');

  /// The browser build is a design preview unless this is set. With it, the page opens the real
  /// webcam and uploads to [apiUrl], which is how a workstation exercises the cloud pipeline.
  static const bool webCaptureEnabled = bool.fromEnvironment('GREENV_WEB_CAPTURE');

  /// Device credentials for the OAuth2 client_credentials grant. Supplying these swaps the
  /// static token for a four-hour one fetched at runtime.
  ///
  /// A compiled-in secret is not a secret - anything in the binary can be read out of it - so this
  /// is for a workstation or a pilot device, and a real fleet provisions them per device into the
  /// platform keychain instead.
  static const String apiClientId = String.fromEnvironment('GREENV_CLIENT_ID');
  static const String apiClientSecret = String.fromEnvironment('GREENV_CLIENT_SECRET');

  static bool get usesClientCredentials =>
      apiClientId.isNotEmpty && apiClientSecret.isNotEmpty;

  static Uri get apiUri => Uri.parse(apiUrl);

  static Uri get tokenUri => apiUri.resolve('/v2/oauth/token');
}
