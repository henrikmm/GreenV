import 'dart:io';

import 'package:greenv_capture/src/capture/capture_ports.dart';

/// Keeps the refresh token in the app's private directory, so signing in survives closing the app.
///
/// The file sits in the sandbox both Android and iOS give an app, which no other app can read. It
/// is **not** the platform keychain: a rooted or jailbroken device, and a filesystem backup that
/// includes app data, would expose it. Moving to the keychain is the hardening step, and this port
/// is the seam for it - only the constructed instance changes.
final class FileSessionStore implements SessionStore {
  FileSessionStore(this.file);

  final File file;

  @override
  Future<String?> read() async {
    if (!await file.exists()) {
      return null;
    }
    final contents = (await file.readAsString()).trim();
    return contents.isEmpty ? null : contents;
  }

  @override
  Future<void> write(String refreshToken) async {
    await file.parent.create(recursive: true);
    await file.writeAsString(refreshToken, flush: true);
  }

  @override
  Future<void> clear() async {
    if (await file.exists()) {
      await file.delete();
    }
  }
}
