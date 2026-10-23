import 'package:flutter/material.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';

/// Level colours are the dashboard's, to the hex, so a stretch is the same red on the phone and
/// on the wall.
Color levelColour(VegetationLevel level) => switch (level) {
  VegetationLevel.high => const Color(0xFFDC2626),
  VegetationLevel.medium => const Color(0xFFCA8A04),
  VegetationLevel.low => const Color(0xFF42BB6F),
  VegetationLevel.unknown => const Color(0xFF777780),
};

Color levelBackground(VegetationLevel level) => levelColour(level).withValues(alpha: 0.16);

String formatDate(DateTime moment) {
  final local = moment.toLocal();
  return '${local.day.toString().padLeft(2, '0')}/'
      '${local.month.toString().padLeft(2, '0')}/${local.year}';
}

String formatDateTime(DateTime moment) {
  final local = moment.toLocal();
  return '${formatDate(moment)}, '
      '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
}

/// The first block of a UUID, which is how a session is named in conversation. Tolerates an
/// id shorter than that rather than throwing on it.
String shortId(String id) => id.length <= 8 ? id : id.substring(0, 8);

String centimetres(double? metres) => metres == null ? '—' : '${(metres * 100).round()} cm';

String squareMetres(double? area) {
  if (area == null) return '—';
  if (area >= 10000) return '${(area / 10000).toStringAsFixed(2)} ha';
  return '${area.round()} m²';
}
