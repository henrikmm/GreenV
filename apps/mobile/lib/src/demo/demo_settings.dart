import 'dart:convert';
import 'dart:io';

/// Video sizes the demo can ask for. The camera plugin names them by preset, which is a request:
/// the format a phone actually grants is only known from the file, so the gateway probes it.
enum DemoResolution {
  p720('720p'),
  p1080('1080p'),
  p2160('4K');

  const DemoResolution(this.label);
  final String label;

  static DemoResolution parse(Object? value) =>
      DemoResolution.values.firstWhere((r) => r.name == value, orElse: () => DemoResolution.p1080);
}

/// Everything the operator may change before a capture. Hidden behind a long press on the title,
/// because a visitor holding the phone should never meet any of it.
///
/// The defaults are the ones the specification argues for (`docs/NEXT-DEMO.md`): 1080p is more
/// than the depth model's 504 px needs; 30 fps keeps exposure long enough in a dim hall; focus and
/// exposure lock when recording starts, because a focus change shifts the focal length and the
/// reconstruction assumes one camera; stabilisation stays off, because it warps every frame.
final class DemoSettings {
  const DemoSettings({
    this.gatewayUrl = defaultGatewayUrl,
    this.pairingCode = defaultPairingCode,
    this.resolution = DemoResolution.p1080,
    this.fps = 30,
    this.lockFocusAndExposure = true,
    this.stabilization = false,
    this.torch = false,
    this.exposureOffset = 0,
    this.maxSeconds = 15,
    this.debugOverlay = false,
    this.samplingFps = 10,
    this.maxFrames = 112,
  });

  /// Baked in with `--dart-define`, so a build needs no typing on the phone. Without one it is the
  /// cloud API's demo prefix; `http://<mac>:8790` still works and is read as `/v1/`.
  static const String cloudGatewayUrl = 'https://greenvapi.matomomitsu.com/v1/demo/';
  static const String defaultGatewayUrl = String.fromEnvironment('VERGE_GATEWAY_URL', defaultValue: cloudGatewayUrl);
  static const String defaultPairingCode = String.fromEnvironment('VERGE_GATEWAY_CODE');

  static const List<int> fpsChoices = [30, 60];
  static const List<int> durationChoices = [10, 15, 20, 30];

  /// How the studio samples this capture into frames for the GPU. The defaults are the studio's
  /// own (10 a second, at most 112), which is what the first tests ran; above 144 only an RTX PRO
  /// 6000 has the memory, and the studio clamps a request to the card it is deployed on.
  static const List<int> samplingFpsChoices = [5, 6, 8, 10, 15];
  static const List<int> maxFramesChoices = [64, 112, 160, 200, 256];

  final String gatewayUrl;
  final String pairingCode;
  final DemoResolution resolution;
  final int fps;
  final bool lockFocusAndExposure;
  final bool stabilization;
  final bool torch;

  /// Exposure compensation in EV, clamped to what the camera reports when applied.
  final double exposureOffset;
  final int maxSeconds;
  final bool debugOverlay;
  final int samplingFps;
  final int maxFrames;

  bool get hasGateway => gatewayUrl.trim().isNotEmpty;

  /// Whether a change needs the camera torn down and opened again, rather than applied live.
  bool needsReopen(DemoSettings other) =>
      other.resolution != resolution || other.fps != fps || other.stabilization != stabilization;

  DemoSettings copyWith({
    String? gatewayUrl,
    String? pairingCode,
    DemoResolution? resolution,
    int? fps,
    bool? lockFocusAndExposure,
    bool? stabilization,
    bool? torch,
    double? exposureOffset,
    int? maxSeconds,
    bool? debugOverlay,
    int? samplingFps,
    int? maxFrames,
  }) => DemoSettings(
    gatewayUrl: gatewayUrl ?? this.gatewayUrl,
    pairingCode: pairingCode ?? this.pairingCode,
    resolution: resolution ?? this.resolution,
    fps: fps ?? this.fps,
    lockFocusAndExposure: lockFocusAndExposure ?? this.lockFocusAndExposure,
    stabilization: stabilization ?? this.stabilization,
    torch: torch ?? this.torch,
    exposureOffset: exposureOffset ?? this.exposureOffset,
    maxSeconds: maxSeconds ?? this.maxSeconds,
    debugOverlay: debugOverlay ?? this.debugOverlay,
    samplingFps: samplingFps ?? this.samplingFps,
    maxFrames: maxFrames ?? this.maxFrames,
  );

  /// What travels with a capture. The gateway address and code stay on the phone.
  Map<String, Object?> captureJson() => {
    'resolution': resolution.label,
    'fps': fps,
    'lockFocusAndExposure': lockFocusAndExposure,
    'stabilization': stabilization,
    'torch': torch,
    'exposureOffset': exposureOffset,
    'maxSeconds': maxSeconds,
  };

  /// What the studio should extract from this capture. Travels beside the camera settings.
  Map<String, Object?> samplingJson() => {'fps': samplingFps, 'maxFrames': maxFrames};

  Map<String, Object?> toJson() => {
    'gatewayUrl': gatewayUrl,
    'pairingCode': pairingCode,
    'resolution': resolution.name,
    'fps': fps,
    'lockFocusAndExposure': lockFocusAndExposure,
    'stabilization': stabilization,
    'torch': torch,
    'exposureOffset': exposureOffset,
    'maxSeconds': maxSeconds,
    'debugOverlay': debugOverlay,
    'samplingFps': samplingFps,
    'maxFrames': maxFrames,
  };

  factory DemoSettings.fromJson(Map<String, Object?> json) {
    const d = DemoSettings();
    int pick(Object? v, List<int> allowed, int fallback) => v is int && allowed.contains(v) ? v : fallback;
    return DemoSettings(
      gatewayUrl: json['gatewayUrl'] is String && (json['gatewayUrl'] as String).isNotEmpty
          ? json['gatewayUrl'] as String
          : d.gatewayUrl,
      pairingCode: json['pairingCode'] is String && (json['pairingCode'] as String).isNotEmpty
          ? json['pairingCode'] as String
          : d.pairingCode,
      resolution: DemoResolution.parse(json['resolution']),
      fps: pick(json['fps'], fpsChoices, d.fps),
      lockFocusAndExposure: json['lockFocusAndExposure'] is bool ? json['lockFocusAndExposure'] as bool : d.lockFocusAndExposure,
      stabilization: json['stabilization'] is bool ? json['stabilization'] as bool : d.stabilization,
      torch: json['torch'] is bool ? json['torch'] as bool : d.torch,
      exposureOffset: json['exposureOffset'] is num ? (json['exposureOffset'] as num).toDouble().clamp(-4.0, 4.0) : d.exposureOffset,
      maxSeconds: pick(json['maxSeconds'], durationChoices, d.maxSeconds),
      debugOverlay: json['debugOverlay'] is bool ? json['debugOverlay'] as bool : d.debugOverlay,
      samplingFps: pick(json['samplingFps'], samplingFpsChoices, d.samplingFps),
      maxFrames: pick(json['maxFrames'], maxFramesChoices, d.maxFrames),
    );
  }
}

/// One JSON file in the app's private directory. A corrupt file falls back to defaults rather than
/// keeping the demo from opening.
final class DemoSettingsStore {
  DemoSettingsStore(this.file);

  final File file;

  Future<DemoSettings> load() async {
    try {
      if (!await file.exists()) return const DemoSettings();
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is Map<String, Object?>) return DemoSettings.fromJson(decoded);
    } on Object {
      // fall through to defaults
    }
    return const DemoSettings();
  }

  Future<void> save(DemoSettings settings) async {
    await file.parent.create(recursive: true);
    await file.writeAsString(jsonEncode(settings.toJson()), flush: true);
  }
}
