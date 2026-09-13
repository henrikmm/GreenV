/// What the phone reads back from the API, in the vocabulary the screens use.
///
/// The dashboard and this app answer the same questions from the same rows, so the rules that
/// turn a row into words live here once: which level a height falls in, what a reading with no
/// height actually says, how a street and a kilometre post compose into a place. Each is copied
/// from the dashboard's `web-core` on purpose and says so, because two vocabularies for one
/// system is how a stretch reads "nível 2" on one screen and "nível 3" on another.
library;

/// Level thresholds, in metres. Below the first a verge reads as mown; above the second, overdue.
/// The same two numbers the API classifies with and the dashboard colours by.
const double level2FloorM = 0.10;
const double level3FloorM = 0.30;

enum VegetationLevel {
  unknown(0, 'Não avaliado', 'Altura desconhecida'),
  low(1, 'Nível 1', 'h < 10 cm'),
  medium(2, 'Nível 2', '10 ≤ h ≤ 30 cm'),
  high(3, 'Nível 3', 'h > 30 cm');

  const VegetationLevel(this.number, this.label, this.description);

  final int number;
  final String label;
  final String description;

  /// Anything that is not 1, 2 or 3 is unknown — null included. Unknown is not low.
  static VegetationLevel of(int? number) => switch (number) {
    1 => low,
    2 => medium,
    3 => high,
    _ => unknown,
  };
}

/// A measured segment as the readings feed returns it.
final class MeasuredStretch {
  const MeasuredStretch({
    required this.sessionId,
    required this.segmentIndex,
    required this.capturedAt,
    this.measuredAt,
    this.level,
    this.extent95P95M,
    this.extent95MaxM,
    this.cellsMeasured,
    this.cellsAbstained,
    this.coverage,
    this.centreLat,
    this.centreLon,
    this.locationQuality,
    this.placeLabel,
    this.placeDetail,
    this.placeHouseNumber,
    this.placeRoad,
    this.placeKm,
  });

  factory MeasuredStretch.fromJson(Map<String, Object?> json) =>
      MeasuredStretch(
        sessionId: json['sessionId'] as String,
        segmentIndex: json['segmentIndex'] as int,
        capturedAt: DateTime.parse(json['capturedAt'] as String),
        measuredAt: _instant(json['measuredAt']),
        level: json['measurementLevel'] as int?,
        extent95P95M: _decimal(json['measurementExtent95P95M']),
        extent95MaxM: _decimal(json['measurementExtent95MaxM']),
        cellsMeasured: json['measurementCellsMeasured'] as int?,
        cellsAbstained: json['measurementCellsAbstained'] as int?,
        coverage: _decimal(json['measurementCoverage']),
        centreLat: _decimal(json['trackCenterLat']),
        centreLon: _decimal(json['trackCenterLon']),
        locationQuality: json['trackLocationQuality'] as String?,
        placeLabel: json['placeLabel'] as String?,
        placeDetail: json['placeDetail'] as String?,
        placeHouseNumber: json['placeHouseNumber'] as String?,
        placeRoad: json['placeRoad'] as String?,
        placeKm: json['placeKm'] as int?,
      );

  final String sessionId;
  final int segmentIndex;
  final DateTime capturedAt;
  final DateTime? measuredAt;
  final int? level;
  final double? extent95P95M;
  final double? extent95MaxM;
  final int? cellsMeasured;
  final int? cellsAbstained;
  final double? coverage;
  final double? centreLat;
  final double? centreLon;
  final String? locationQuality;
  final String? placeLabel;
  final String? placeDetail;
  final String? placeHouseNumber;
  final String? placeRoad;
  final int? placeKm;

  String get key => '$sessionId:$segmentIndex';

  VegetationLevel get vegetationLevel => VegetationLevel.of(level);

  bool get located => centreLat != null && centreLon != null;

  /// The height a crew plans against, in centimetres, or null when nothing could be measured.
  int? get heightCm =>
      extent95P95M == null ? null : (extent95P95M! * 100).round();

  /// Where this stretch is, composed the way the dashboard composes it.
  ///
  /// On a highway the kilometre post leads: "SP-021 · KM 12" is what is said over the radio, and
  /// the nearest access road is detail. Off the highway the street is all there is. The house
  /// number is always "aprox." and never in the headline — reverse geocoding answers with the
  /// nearest addressable point, which on a verge is the building across the road.
  Place get place {
    final number = placeHouseNumber;
    final parts = <String>[
      ?(number == null ? null : 'nº aprox. $number'),
      ?placeDetail,
    ];
    if (placeRoad != null && placeKm != null) {
      return Place(
        label: '$placeRoad · KM $placeKm',
        detail: [?placeLabel, ...parts].join(' · '),
      );
    }
    if (placeLabel != null) {
      return Place(label: placeLabel!, detail: parts.join(' · '));
    }
    if (located) {
      return Place(
        label:
            '${centreLat!.toStringAsFixed(4)}, ${centreLon!.toStringAsFixed(4)}',
        detail: 'sem nome resolvido',
      );
    }
    return const Place(label: 'Sem posição registrada', detail: '');
  }

  /// What a reading with no height actually says. Copied from the dashboard, and from the rule
  /// in the measurement code it quotes: a cell only exists where the segmenter retained
  /// vegetation, so zero measured cells with some abstained means vegetation was seen and
  /// could not be measured, and zero of both means the detector retained none — which is as
  /// close to "no grass here" as this system gets and is still not that claim.
  Reading get reading {
    final level = vegetationLevel;
    if (level != VegetationLevel.unknown) {
      return Reading(level.label, level);
    }
    if (cellsMeasured == 0 && cellsAbstained == 0) {
      return const Reading(
        'Nenhuma vegetação detectada',
        VegetationLevel.unknown,
      );
    }
    if (cellsMeasured == 0 && (cellsAbstained ?? 0) > 0) {
      return const Reading(
        'Vegetação vista, sem altura',
        VegetationLevel.unknown,
      );
    }
    return Reading(level.label, level);
  }
}

final class Place {
  const Place({required this.label, required this.detail});

  final String label;
  final String detail;
}

final class Reading {
  const Reading(this.label, this.level);

  final String label;
  final VegetationLevel level;
}

/// One photograph a segment published, with where and when it was taken.
final class SampledFrame {
  const SampledFrame({
    required this.fileName,
    required this.canonicalFrame,
    required this.imageUrl,
    this.capturedAtUtc,
    this.horizontalAccuracyMeters,
    this.locationQuality,
  });

  factory SampledFrame.fromJson(Map<String, Object?> json) => SampledFrame(
    fileName: json['fileName'] as String,
    canonicalFrame: (json['canonicalFrame'] as num).toInt(),
    imageUrl: json['imageUrl'] as String,
    capturedAtUtc: _instant(json['capturedAtUtc']),
    horizontalAccuracyMeters: _decimal(json['horizontalAccuracyMeters']),
    locationQuality: json['locationQuality'] as String?,
  );

  final String fileName;
  final int canonicalFrame;

  /// Absolute, and behind the same bearer the rest of the API is behind — so it is fetched with
  /// headers rather than handed to a plain image widget.
  final String imageUrl;

  final DateTime? capturedAtUtc;
  final double? horizontalAccuracyMeters;
  final String? locationQuality;
}

/// What one photograph contributed to its segment's measurement.
///
/// Heights are above each cell's own local ground, which is the tape-comparable figure, never
/// above the fitted plane — that one reads higher and would inflate what a crew is told.
final class FrameReadings {
  const FrameReadings({
    required this.canonicalFrame,
    required this.cellsVoted,
    required this.evidenceForCells,
    this.extent95MedianM,
    this.extent95MaxM,
    this.largestDisagreementM,
  });

  factory FrameReadings.fromJson(Map<String, Object?> json) => FrameReadings(
    canonicalFrame: (json['canonicalFrame'] as num).toInt(),
    cellsVoted: (json['cellsVoted'] as num).toInt(),
    evidenceForCells: (json['evidenceForCells'] as num? ?? 0).toInt(),
    extent95MedianM: _decimal(json['extent95MedianM']),
    extent95MaxM: _decimal(json['extent95MaxM']),
    largestDisagreementM: _decimal(json['largestDisagreementM']),
  );

  final int canonicalFrame;
  final int cellsVoted;
  final int evidenceForCells;
  final double? extent95MedianM;
  final double? extent95MaxM;
  final double? largestDisagreementM;
}

/// The counters above a list, over everything the filter selected and not over the page.
final class ReadingsSummary {
  const ReadingsSummary({
    required this.total,
    required this.countsByLevel,
    this.tallestM,
  });

  factory ReadingsSummary.fromJson(Map<String, Object?> json) {
    final counts = (json['countsByLevel'] as Map<String, Object?>? ?? const {})
        .map((key, value) => MapEntry(int.parse(key), (value as num).toInt()));
    return ReadingsSummary(
      total: (json['total'] as num).toInt(),
      countsByLevel: counts,
      tallestM: _decimal(json['tallestExtent95M']),
    );
  }

  static const ReadingsSummary empty = ReadingsSummary(
    total: 0,
    countsByLevel: {},
  );

  final int total;
  final Map<int, int> countsByLevel;
  final double? tallestM;

  int count(VegetationLevel level) => countsByLevel[level.number] ?? 0;
}

/// One page of anything, with enough context to ask for the next.
final class PageOf<T> {
  const PageOf({
    required this.items,
    required this.total,
    required this.hasMore,
  });

  final List<T> items;
  final int total;
  final bool hasMore;
}

final class CaptureSessionSummary {
  const CaptureSessionSummary({
    required this.sessionId,
    required this.startedAt,
    required this.segmentCount,
    required this.measuredSegmentCount,
    this.endedAt,
    this.rodovia,
  });

  factory CaptureSessionSummary.fromJson(Map<String, Object?> json) =>
      CaptureSessionSummary(
        sessionId: json['sessionId'] as String,
        startedAt: DateTime.parse(json['startedAt'] as String),
        endedAt: _instant(json['endedAt']),
        segmentCount: (json['segmentCount'] as num).toInt(),
        measuredSegmentCount: (json['measuredSegmentCount'] as num).toInt(),
        rodovia: json['rodovia'] as String?,
      );

  final String sessionId;
  final DateTime startedAt;
  final DateTime? endedAt;
  final int segmentCount;
  final int measuredSegmentCount;
  final String? rodovia;
}

enum TeamStatus {
  inField('em_campo', 'Em campo'),
  available('disponivel', 'Disponível'),
  offDuty('fora_servico', 'Fora de serviço');

  const TeamStatus(this.wire, this.label);

  final String wire;
  final String label;

  static TeamStatus of(String? wire) =>
      values.firstWhere((s) => s.wire == wire, orElse: () => offDuty);
}

final class Team {
  const Team({
    required this.teamId,
    required this.name,
    required this.shortName,
    required this.initials,
    required this.status,
    required this.pendingOrders,
    required this.inProgressOrders,
    required this.completedOrders,
    this.region,
    this.colour,
  });

  factory Team.fromJson(Map<String, Object?> json) => Team(
    teamId: json['teamId'] as String,
    name: json['name'] as String,
    shortName: json['shortName'] as String? ?? json['name'] as String,
    initials: json['initials'] as String? ?? '',
    region: json['region'] as String?,
    colour: json['colour'] as String?,
    status: TeamStatus.of(json['status'] as String?),
    pendingOrders: (json['pendingOrders'] as num? ?? 0).toInt(),
    inProgressOrders: (json['inProgressOrders'] as num? ?? 0).toInt(),
    completedOrders: (json['completedOrders'] as num? ?? 0).toInt(),
  );

  final String teamId;
  final String name;
  final String shortName;
  final String initials;
  final String? region;
  final String? colour;
  final TeamStatus status;
  final int pendingOrders;
  final int inProgressOrders;
  final int completedOrders;
}

enum OrderPriority {
  low('baixa', 'Baixa'),
  medium('media', 'Média'),
  high('alta', 'Alta'),
  urgent('urgente', 'Urgente');

  const OrderPriority(this.wire, this.label);

  final String wire;
  final String label;

  static OrderPriority of(String? wire) =>
      values.firstWhere((p) => p.wire == wire, orElse: () => medium);

  /// The dashboard's default for a stretch of this level, so the form opens already sensible.
  static OrderPriority suggestedFor(VegetationLevel level) => switch (level) {
    VegetationLevel.high => high,
    VegetationLevel.medium => medium,
    _ => low,
  };
}

enum OrderStatus {
  pending('pendente', 'Pendente'),
  inProgress('em_andamento', 'Em andamento'),
  completed('concluida', 'Concluída'),
  cancelled('cancelada', 'Cancelada');

  const OrderStatus(this.wire, this.label);

  final String wire;
  final String label;

  static OrderStatus of(String? wire) =>
      values.firstWhere((s) => s.wire == wire, orElse: () => pending);
}

final class ServiceOrder {
  const ServiceOrder({
    required this.orderId,
    required this.reference,
    required this.status,
    required this.priority,
    required this.createdAt,
    required this.targetCount,
    this.teamId,
    this.scheduledFor,
    this.notes,
    this.equipment,
    this.areaSquareMetres,
    this.vegetationLevel,
  });

  factory ServiceOrder.fromJson(Map<String, Object?> json) => ServiceOrder(
    orderId: json['orderId'] as String,
    reference: json['reference'] as String,
    status: OrderStatus.of(json['status'] as String?),
    priority: OrderPriority.of(json['priority'] as String?),
    teamId: json['teamId'] as String?,
    scheduledFor: json['scheduledFor'] as String?,
    notes: json['notes'] as String?,
    equipment: json['equipment'] as String?,
    areaSquareMetres: _decimal(json['areaSquareMetres']),
    vegetationLevel: json['vegetationLevel'] as int?,
    createdAt: DateTime.parse(json['createdAt'] as String),
    targetCount: (json['targets'] as List<Object?>? ?? const []).length,
  );

  final String orderId;
  final String reference;
  final OrderStatus status;
  final OrderPriority priority;
  final String? teamId;
  final String? scheduledFor;
  final String? notes;
  final String? equipment;
  final double? areaSquareMetres;
  final int? vegetationLevel;
  final DateTime createdAt;
  final int targetCount;
}

/// What the phone sends to open an order. The area, the level and the position are read from
/// the stretches by the API — a client that could set them could open an order claiming a
/// height nobody measured.
final class OrderDraft {
  const OrderDraft({
    required this.priority,
    required this.targets,
    this.teamId,
    this.scheduledFor,
    this.notes,
  });

  final OrderPriority priority;
  final List<MeasuredStretch> targets;
  final String? teamId;
  final DateTime? scheduledFor;
  final String? notes;

  Map<String, Object?> toJson() => {
    'priority': priority.wire,
    'teamId': teamId,
    'scheduledFor': scheduledFor == null
        ? null
        : '${scheduledFor!.year.toString().padLeft(4, '0')}-'
              '${scheduledFor!.month.toString().padLeft(2, '0')}-'
              '${scheduledFor!.day.toString().padLeft(2, '0')}',
    'notes': (notes == null || notes!.trim().isEmpty) ? null : notes!.trim(),
    'targets': [
      for (final target in targets)
        {'sessionId': target.sessionId, 'segmentIndex': target.segmentIndex},
    ],
  };
}

DateTime? _instant(Object? value) =>
    value == null ? null : DateTime.parse(value as String);

double? _decimal(Object? value) =>
    value == null ? null : (value as num).toDouble();
