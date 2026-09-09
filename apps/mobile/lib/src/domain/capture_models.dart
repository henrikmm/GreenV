enum SegmentUploadState { pending, awaitingVerification, failed }

/// The direction of travel a route was driven in.
///
/// Four values and no others. The dashboard joins two passes of the same road by
/// `(rodovia, sentido, km)`, so a second spelling of one direction quietly becomes a second road.
/// The names are the wire values the API accepts; they stay Portuguese because the road does.
enum Sentido { norte, sul, leste, oeste }

final class QueuedSegment {
  const QueuedSegment({
    required this.sessionId,
    required this.segmentIndex,
    required this.idempotencyKey,
    required this.capturedAtUtc,
    required this.durationMillis,
    required this.videoPath,
    required this.videoSha256,
    required this.telemetryPath,
    required this.telemetrySha256,
    this.videoContentType = 'video/mp4',
    this.state = SegmentUploadState.pending,
    this.lastError,
  });

  final String sessionId;
  final int segmentIndex;
  final String idempotencyKey;
  final DateTime capturedAtUtc;
  final int durationMillis;
  final String videoPath;
  final String videoSha256;
  final String telemetryPath;
  final String telemetrySha256;

  /// A phone records MP4; a browser's MediaRecorder records WebM. The API accepts both, so the
  /// upload has to declare what was actually encoded.
  final String videoContentType;

  final SegmentUploadState state;
  final String? lastError;

  QueuedSegment copyWith({
    SegmentUploadState? state,
    String? lastError,
    bool clearError = false,
  }) => QueuedSegment(
    sessionId: sessionId,
    segmentIndex: segmentIndex,
    idempotencyKey: idempotencyKey,
    capturedAtUtc: capturedAtUtc,
    durationMillis: durationMillis,
    videoPath: videoPath,
    videoSha256: videoSha256,
    telemetryPath: telemetryPath,
    telemetrySha256: telemetrySha256,
    videoContentType: videoContentType,
    state: state ?? this.state,
    lastError: clearError ? null : lastError ?? this.lastError,
  );

  Map<String, Object?> toJson() => {
    'sessionId': sessionId,
    'segmentIndex': segmentIndex,
    'idempotencyKey': idempotencyKey,
    'capturedAtUtc': capturedAtUtc.toUtc().toIso8601String(),
    'durationMillis': durationMillis,
    'videoPath': videoPath,
    'videoSha256': videoSha256,
    'telemetryPath': telemetryPath,
    'telemetrySha256': telemetrySha256,
    'videoContentType': videoContentType,
    'state': state.name,
    'lastError': lastError,
  };

  factory QueuedSegment.fromJson(Map<String, Object?> json) => QueuedSegment(
    sessionId: json['sessionId']! as String,
    segmentIndex: json['segmentIndex']! as int,
    idempotencyKey: json['idempotencyKey']! as String,
    capturedAtUtc: DateTime.parse(json['capturedAtUtc']! as String).toUtc(),
    durationMillis: json['durationMillis']! as int,
    videoPath: json['videoPath']! as String,
    videoSha256: json['videoSha256']! as String,
    telemetryPath: json['telemetryPath']! as String,
    telemetrySha256: json['telemetrySha256']! as String,
    videoContentType: json['videoContentType'] as String? ?? 'video/mp4',
    state: SegmentUploadState.values.byName(json['state']! as String),
    lastError: json['lastError'] as String?,
  );
}

final class QueuedSession {
  const QueuedSession({
    required this.sessionId,
    required this.deviceId,
    required this.startedAtUtc,
    this.rodovia,
    this.sentido,
    this.endedAtUtc,
    this.lastSegmentIndex,
    this.completionSent = false,
    this.segments = const [],
  });

  final String sessionId;
  final String deviceId;
  final DateTime startedAtUtc;

  /// The rodovia and the sentido the whole route belongs to. A route is driven along one highway
  /// in one direction for its whole length, so these are recorded once for the session rather
  /// than per segment. Null when the operator left them empty, and null travels all the way to
  /// the measurement packet rather than becoming a road nobody can find.
  final String? rodovia;
  final Sentido? sentido;

  final DateTime? endedAtUtc;
  final int? lastSegmentIndex;
  final bool completionSent;
  final List<QueuedSegment> segments;

  bool get isClosed => endedAtUtc != null && lastSegmentIndex != null;

  QueuedSession copyWith({
    DateTime? endedAtUtc,
    int? lastSegmentIndex,
    bool? completionSent,
    List<QueuedSegment>? segments,
  }) => QueuedSession(
    sessionId: sessionId,
    deviceId: deviceId,
    startedAtUtc: startedAtUtc,
    rodovia: rodovia,
    sentido: sentido,
    endedAtUtc: endedAtUtc ?? this.endedAtUtc,
    lastSegmentIndex: lastSegmentIndex ?? this.lastSegmentIndex,
    completionSent: completionSent ?? this.completionSent,
    segments: List.unmodifiable(segments ?? this.segments),
  );

  Map<String, Object?> toJson() => {
    'sessionId': sessionId,
    'deviceId': deviceId,
    'startedAtUtc': startedAtUtc.toUtc().toIso8601String(),
    'rodovia': rodovia,
    'sentido': sentido?.name,
    'endedAtUtc': endedAtUtc?.toUtc().toIso8601String(),
    'lastSegmentIndex': lastSegmentIndex,
    'completionSent': completionSent,
    'segments': segments.map((segment) => segment.toJson()).toList(),
  };

  factory QueuedSession.fromJson(Map<String, Object?> json) => QueuedSession(
    sessionId: json['sessionId']! as String,
    deviceId: json['deviceId']! as String,
    startedAtUtc: DateTime.parse(json['startedAtUtc']! as String).toUtc(),
    // Absent in a queue written before the app asked for a road. Reading it as null is what lets
    // a capture recorded then still upload today.
    rodovia: json['rodovia'] as String?,
    sentido: _sentido(json['sentido'] as String?),
    endedAtUtc: json['endedAtUtc'] == null
        ? null
        : DateTime.parse(json['endedAtUtc']! as String).toUtc(),
    lastSegmentIndex: json['lastSegmentIndex'] as int?,
    completionSent: json['completionSent']! as bool,
    segments: (json['segments']! as List<Object?>)
        .map(
          (value) =>
              QueuedSegment.fromJson((value! as Map).cast<String, Object?>()),
        )
        .toList(),
  );
}

/// Null for anything the vocabulary does not contain, rather than throwing: a queue file the app
/// cannot fully read must still hand back its segments, and the API would refuse the value anyway.
Sentido? _sentido(String? value) {
  if (value == null) return null;
  for (final sentido in Sentido.values) {
    if (sentido.name == value) return sentido;
  }
  return null;
}

final class RecordedVideo {
  const RecordedVideo({
    required this.path,
    required this.durationMillis,
    this.contentType = 'video/mp4',
  });

  final String path;
  final int durationMillis;
  final String contentType;
}

final class SegmentTelemetryDocument {
  const SegmentTelemetryDocument(this.json);

  final Map<String, Object?> json;
}
