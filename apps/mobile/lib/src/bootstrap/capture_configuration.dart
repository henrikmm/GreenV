import 'package:flutter/foundation.dart';

/// Compile-time capture configuration. `String.fromEnvironment` only reads `--dart-define`, so
/// these are baked into the build; there is no runtime environment variable to change afterwards.
final class CaptureConfiguration {
  const CaptureConfiguration._();

  /// The deployed API, which a release build talks to unless told otherwise. The APK handed out
  /// for installation used to depend on someone remembering the `--dart-define`; built without
  /// it, it pointed at the emulator's host alias and could not sign anyone in on a real phone.
  static const String deployedApiUrl = 'https://greenvapi.matomomitsu.com';

  /// Android's alias for the development host, which is what a local Compose stack listens on.
  static const String localApiUrl = 'http://10.0.2.2:8080';

  static const String defaultApiUrl = kReleaseMode
      ? deployedApiUrl
      : localApiUrl;

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
