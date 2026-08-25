enum SegmentUploadState { pending, awaitingVerification, failed }

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
    state: SegmentUploadState.values.byName(json['state']! as String),
    lastError: json['lastError'] as String?,
  );
}

final class QueuedSession {
  const QueuedSession({
    required this.sessionId,
    required this.deviceId,
    required this.startedAtUtc,
    this.endedAtUtc,
    this.lastSegmentIndex,
    this.completionSent = false,
    this.segments = const [],
  });

  final String sessionId;
  final String deviceId;
  final DateTime startedAtUtc;
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
    endedAtUtc: endedAtUtc ?? this.endedAtUtc,
    lastSegmentIndex: lastSegmentIndex ?? this.lastSegmentIndex,
    completionSent: completionSent ?? this.completionSent,
    segments: List.unmodifiable(segments ?? this.segments),
  );

  Map<String, Object?> toJson() => {
    'sessionId': sessionId,
    'deviceId': deviceId,
    'startedAtUtc': startedAtUtc.toUtc().toIso8601String(),
    'endedAtUtc': endedAtUtc?.toUtc().toIso8601String(),
    'lastSegmentIndex': lastSegmentIndex,
    'completionSent': completionSent,
    'segments': segments.map((segment) => segment.toJson()).toList(),
  };

  factory QueuedSession.fromJson(Map<String, Object?> json) => QueuedSession(
    sessionId: json['sessionId']! as String,
    deviceId: json['deviceId']! as String,
    startedAtUtc: DateTime.parse(json['startedAtUtc']! as String).toUtc(),
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

final class RecordedVideo {
  const RecordedVideo({required this.path, required this.durationMillis});

  final String path;
  final int durationMillis;
}

final class SegmentTelemetryDocument {
  const SegmentTelemetryDocument(this.json);

  final Map<String, Object?> json;
}
