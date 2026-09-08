import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';

final class AppDependencies {
  const AppDependencies({
    required this.capture,
    required this.authenticator,
    this.mockedPreview = false,
  });

  final CaptureCoordinator capture;

  /// Who is signed in. The shell watches [SessionAuthenticator.signedIn] so a refresh that fails
  /// returns the person to the login screen instead of leaving them on a screen that cannot load.
  final SessionAuthenticator authenticator;

  /// True only for the design preview, where the camera, the backend and the session are fake.
  /// It is what lets `?screen=` open any screen without signing in; every real build refuses.
  final bool mockedPreview;
}
