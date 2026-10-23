import 'package:flutter/material.dart';
import 'package:greenv_capture/src/ui/shell.dart';

/// The app's one theme: GreenV's forest green for actions, Motiva's purple as the accent, and
/// the same type scale on every screen.
ThemeData motivaTheme() => ThemeData(
  useMaterial3: true,
  colorScheme: ColorScheme.fromSeed(
    seedColor: greenvForest,
    primary: greenvForest,
    secondary: motivaPurple,
    surface: Colors.white,
  ),
  scaffoldBackgroundColor: const Color(0xFF18231E),
  textTheme: const TextTheme(
    headlineSmall: TextStyle(
      color: motivaInk,
      fontSize: 24,
      height: 1.15,
      fontWeight: FontWeight.w800,
      letterSpacing: -0.5,
    ),
    titleLarge: TextStyle(
      color: motivaInk,
      fontSize: 18,
      fontWeight: FontWeight.w800,
      letterSpacing: -0.2,
    ),
    titleMedium: TextStyle(color: motivaInk, fontWeight: FontWeight.w700),
    bodyLarge: TextStyle(color: motivaInk, fontSize: 16, height: 1.45),
    bodyMedium: TextStyle(color: motivaInk, fontSize: 14, height: 1.4),
    bodySmall: TextStyle(color: motivaMuted, fontSize: 12, height: 1.35),
    labelLarge: TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
  ),
  filledButtonTheme: FilledButtonThemeData(
    style: FilledButton.styleFrom(
      minimumSize: const Size.fromHeight(52),
      backgroundColor: greenvForest,
      foregroundColor: Colors.white,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
    ),
  ),
  outlinedButtonTheme: OutlinedButtonThemeData(
    style: OutlinedButton.styleFrom(
      minimumSize: const Size.fromHeight(52),
      foregroundColor: greenvForest,
      side: const BorderSide(color: motivaLine),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
    ),
  ),
  textButtonTheme: TextButtonThemeData(
    style: TextButton.styleFrom(foregroundColor: motivaPurpleDark, minimumSize: const Size(44, 44)),
  ),
  inputDecorationTheme: InputDecorationTheme(
    filled: true,
    fillColor: Colors.white,
    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: const BorderSide(color: motivaLine),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: const BorderSide(color: greenvForest, width: 1.5),
    ),
    errorBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: const BorderSide(color: Color(0xFFC43E4D)),
    ),
    hintStyle: const TextStyle(color: motivaMuted, fontSize: 14),
  ),
);
