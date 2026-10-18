import 'package:flutter/material.dart';

/// Verge Studio's palette (`measurement/app/src/theme.css`), so the demo screen reads as the
/// studio's capture end rather than as the field app. One accent per state: amber is busy, red is
/// an error, bright white is done. Green belongs to point clouds, as it does in the studio.
abstract final class Verge {
  static const Color bgApp = Color(0xFF0D0D0F);
  static const Color bgPane = Color(0xFF151517);
  static const Color bgHeader = Color(0xFF1A1A1D);
  static const Color border = Color(0xFF2A2A2E);
  static const Color text = Color(0xFFD4D4D8);
  static const Color textDim = Color(0xFF8A8A90);
  static const Color emphHi = Color(0xFFF4F4F6);
  static const Color busy = Color(0xFFF59E0B);
  static const Color error = Color(0xFFB4574F);
  static const Color recording = Color(0xFFE05252);
  static const Color points = Color(0xFF4ADE80);

  /// The studio uses JetBrains Mono for numbers. It is not bundled here; Android's monospace
  /// stands in, which is enough for digits that must not jitter as they change.
  static const String mono = 'monospace';

  static const TextStyle label = TextStyle(color: textDim, fontSize: 11, letterSpacing: 1.2, fontWeight: FontWeight.w600);
  static const TextStyle body = TextStyle(color: text, fontSize: 14, height: 1.35);
  static const TextStyle number = TextStyle(color: emphHi, fontFamily: mono, fontSize: 14);

  static ThemeData theme() => ThemeData(
    brightness: Brightness.dark,
    scaffoldBackgroundColor: bgApp,
    colorScheme: const ColorScheme.dark(
      surface: bgPane,
      primary: emphHi,
      onPrimary: bgApp,
      secondary: busy,
      error: error,
      outline: border,
    ),
    dividerColor: border,
    useMaterial3: true,
  );
}
