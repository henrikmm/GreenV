import 'package:greenv_capture/src/capture/capture_ports.dart';

/// Keeps the refresh token only for the life of the process.
///
/// This is what the browser build uses. A page has no durable place to put a credential that is
/// both safe from script and safe from a lost laptop - `localStorage` is readable by any script on
/// the origin, which is exactly what the dashboard's HttpOnly cookies avoid. So the browser build
/// asks for a password again after a reload, matching its in-memory capture queue.
final class MemorySessionStore implements SessionStore {
  String? _refreshToken;

  @override
  Future<String?> read() async => _refreshToken;

  @override
  Future<void> write(String refreshToken) async => _refreshToken = refreshToken;

  @override
  Future<void> clear() async => _refreshToken = null;
}
