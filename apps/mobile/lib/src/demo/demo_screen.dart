import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:greenv_capture/src/demo/demo_camera.dart';
import 'package:greenv_capture/src/demo/demo_settings.dart';
import 'package:greenv_capture/src/demo/demo_widgets.dart';
import 'package:greenv_capture/src/demo/gateway_client.dart';
import 'package:greenv_capture/src/demo/orbit_tracker.dart';
import 'package:greenv_capture/src/demo/verge_theme.dart';
import 'package:path_provider/path_provider.dart';
import 'package:sensors_plus/sensors_plus.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

enum _Stage { opening, ready, recording, uploading, processing, failed }

/// Record an object, send it to the gateway, follow what the gateway reports.
///
/// The camera fills the screen at its own aspect ratio and is cropped at the edges rather than
/// squeezed into a box, which is what made the field app's preview look distorted. Cropping only
/// ever hides what the video still records, never the other way round.
final class DemoScreen extends StatefulWidget {
  const DemoScreen({super.key});

  @override
  State<DemoScreen> createState() => _DemoScreenState();
}

final class _DemoScreenState extends State<DemoScreen> with WidgetsBindingObserver {
  final DemoCamera _camera = DemoCamera();
  final OrbitTracker _orbit = OrbitTracker();
  DemoSettingsStore? _store;
  DemoSettings _settings = const DemoSettings();

  _Stage _stage = _Stage.opening;
  String? _problem;
  GatewayHealth? _health;
  Timer? _healthTimer;

  // Recording.
  final Stopwatch _clock = Stopwatch();
  Timer? _ticker;
  DateTime? _recordStartedAt;
  StreamSubscription<AccelerometerEvent>? _accel;
  StreamSubscription<GyroscopeEvent>? _gyro;
  DateTime? _lastGyro;

  // Sending.
  File? _video;
  Map<String, Object?>? _meta;
  String? _captureId;
  int _sent = 0, _total = 0;
  Stopwatch? _uploadClock;
  CaptureStatus? _status;
  Timer? _poll;

  /// The phase list grows to a dozen lines; minimised it is one, so the camera stays visible.
  bool _phasesExpanded = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WakelockPlus.enable();
    unawaited(_boot());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _healthTimer?.cancel();
    _ticker?.cancel();
    _poll?.cancel();
    _accel?.cancel();
    _gyro?.cancel();
    unawaited(_camera.close());
    WakelockPlus.disable();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Never record invisibly: leaving the app abandons a recording in progress.
    // `inactive` is also what a permission dialog causes, so only a real pause closes the camera.
    if (state == AppLifecycleState.paused || state == AppLifecycleState.hidden) {
      if (_stage == _Stage.recording) unawaited(_abandonRecording());
      if (_stage != _Stage.uploading && _stage != _Stage.processing) unawaited(_camera.close());
    } else if (state == AppLifecycleState.resumed && !_camera.isReady) {
      unawaited(_openCamera());
    }
  }

  Future<void> _boot() async {
    final documents = await getApplicationDocumentsDirectory();
    _store = DemoSettingsStore(File('${documents.path}${Platform.pathSeparator}verge-demo-settings.json'));
    _settings = await _store!.load();
    await _openCamera();
    _healthTimer = Timer.periodic(const Duration(seconds: 3), (_) => _checkHealth());
    unawaited(_checkHealth());
  }

  Future<void> _openCamera() async {
    setState(() {
      _stage = _stage == _Stage.processing || _stage == _Stage.uploading ? _stage : _Stage.opening;
      _problem = null;
    });
    try {
      await _camera.open(_settings);
      if (!mounted) return;
      setState(() {
        if (_stage == _Stage.opening) _stage = _Stage.ready;
      });
    } on CameraException catch (e, stack) {
      debugPrint('demo camera: ${e.code} ${e.description}\n$stack');
      _fail('Câmera: ${e.description ?? e.code}');
    } on Object catch (e, stack) {
      debugPrint('demo camera: $e\n$stack');
      _fail('Câmera: $e');
    }
  }

  GatewayClient? _client([DemoSettings? s]) {
    final settings = s ?? _settings;
    if (!settings.hasGateway) return null;
    final uri = Uri.tryParse(settings.gatewayUrl.trim());
    if (uri == null || !uri.hasScheme) return null;
    return GatewayClient(baseUri: uri, pairingCode: settings.pairingCode);
  }

  bool get _onMac {
    final uri = Uri.tryParse(_settings.gatewayUrl.trim());
    return uri != null && uri.hasScheme && GatewayClient.isMacBase(uri);
  }

  Future<GatewayHealth> _healthOf(DemoSettings s) async {
    final client = _client(s);
    if (client == null) return const GatewayHealth.unreachable('nenhum gateway configurado');
    try {
      return await client.health();
    } finally {
      client.close();
    }
  }

  Future<void> _checkHealth() async {
    if (_stage == _Stage.recording) return;
    final h = await _healthOf(_settings);
    if (mounted) setState(() => _health = h);
  }

  // ---------------------------------------------------------------------------------------------
  // Recording

  /// True while the camera is being told to start or stop. The stage only changes once the
  /// camera has answered, so without this a quick second tap starts a second recording on top
  /// of the first — which the plugin refuses, leaving the first one running unseen.
  bool _toggling = false;

  Future<void> _toggleRecording() async {
    if (_toggling) return;
    _toggling = true;
    try {
      if (_stage == _Stage.recording) return await _stopRecording();
      if (_stage != _Stage.ready) return;
      await _startRecording();
    } finally {
      _toggling = false;
    }
  }

  Future<void> _startRecording() async {
    try {
      await _camera.startRecording(_settings);
    } on Object catch (e) {
      // Whatever the plugin left running is not a capture anyone asked for.
      if (_camera.isRecording) await _discardStrayRecording();
      return _fail('Não foi possível gravar: $e');
    }
    _recordStartedAt = DateTime.now().toUtc();
    _orbit.reset();
    _lastGyro = null;
    _accel = accelerometerEventStream(samplingPeriod: SensorInterval.gameInterval)
        .listen((e) => _orbit.addAccelerometer(e.x, e.y, e.z));
    _gyro = gyroscopeEventStream(samplingPeriod: SensorInterval.gameInterval).listen((e) {
      final now = e.timestamp;
      final last = _lastGyro;
      _lastGyro = now;
      if (last != null) _orbit.addGyroscope(e.x, e.y, e.z, now.difference(last).inMicroseconds / 1e6);
    });
    _clock
      ..reset()
      ..start();
    _ticker = Timer.periodic(const Duration(milliseconds: 100), (_) {
      if (!mounted) return;
      if (_clock.elapsed.inMilliseconds >= _settings.maxSeconds * 1000) {
        if (_stage == _Stage.recording) unawaited(_toggleRecording());
      } else {
        setState(() {});
      }
    });
    setState(() => _stage = _Stage.recording);
  }

  void _stopSensors() {
    _ticker?.cancel();
    _ticker = null;
    unawaited(_accel?.cancel());
    unawaited(_gyro?.cancel());
    _accel = null;
    _gyro = null;
    _clock.stop();
  }

  Future<void> _discardStrayRecording() async {
    try {
      final file = await _camera.stopRecording();
      await file.delete();
    } on Object {
      // nothing to keep
    }
  }

  Future<void> _abandonRecording() async {
    _stopSensors();
    try {
      final file = await _camera.stopRecording();
      await file.delete();
    } on Object {
      // nothing to keep
    }
    if (mounted) setState(() => _stage = _Stage.ready);
  }

  /// Shorter than this is a tap that bounced, not a capture: the first real test sent a 57 ms,
  /// zero-byte video. A stop this early is ignored rather than turned into an error.
  static const int _minimumRecordingMs = 1500;

  Future<void> _stopRecording() async {
    if (_stage != _Stage.recording) return;
    if (_clock.elapsedMilliseconds < _minimumRecordingMs) return;
    final durationMs = _clock.elapsedMilliseconds;
    _stopSensors();
    final stoppedAt = DateTime.now().toUtc();
    File file;
    try {
      file = await _camera.stopRecording();
    } on Object catch (e) {
      return _fail('A gravação não terminou: $e');
    }
    final bytes = await file.length();
    if (bytes == 0) {
      unawaited(file.delete().catchError((_) => file));
      return _fail('A câmera devolveu um vídeo vazio. Grave de novo.');
    }
    // Checked before announcing, so the gateway never holds a capture whose video cannot arrive.
    final tooLarge = videoSizeProblem(bytes);
    if (tooLarge != null) {
      unawaited(file.delete().catchError((_) => file));
      _meta = null;
      return _fail(tooLarge);
    }
    final previous = _video;
    if (previous != null && previous.path != file.path) unawaited(previous.delete().catchError((_) => previous));
    _video = file;
    _meta = {
      'schema': 'verge-phone-capture/1',
      'capturedAt': _recordStartedAt!.toIso8601String(),
      'recordStoppedAt': stoppedAt.toIso8601String(),
      'durationMs': durationMs,
      'app': {'entry': 'main_demo', 'build': '1.0.0+1'},
      'device': {'os': Platform.operatingSystem, 'osVersion': Platform.operatingSystemVersion},
      'settings': _settings.captureJson(),
      'sampling': _settings.samplingJson(),
      'applied': _camera.applied,
      'orbit': _orbit.toJson(),
      'bytes': bytes,
    };
    await _send();
  }

  // ---------------------------------------------------------------------------------------------
  // Sending

  Future<void> _send() async {
    final file = _video, meta = _meta;
    if (file == null || meta == null) return;
    final client = _client();
    if (client == null) {
      return _fail('Nenhum gateway configurado. Segure o título por 2 s para configurar.');
    }
    setState(() {
      _stage = _Stage.uploading;
      _problem = null;
      _status = null;
      _sent = 0;
      _total = meta['bytes'] as int;
      _uploadClock = Stopwatch()..start();
    });
    try {
      _captureId ??= await client.announce(meta);
      await client.uploadVideo(
        _captureId!,
        file,
        onProgress: (sent, total) {
          if (!mounted) return;
          // Repainting on every 64 KiB chunk is wasted work; a percent is enough.
          if (sent == total || (sent - _sent) > total / 100) setState(() => _sent = sent);
        },
      );
      _uploadClock?.stop();
      if (!mounted) return;
      setState(() => _stage = _Stage.processing);
      _startPolling(client);
    } on GatewayException catch (e) {
      client.close();
      // A capture the gateway already holds the video for is not worth announcing again.
      if (e.statusCode == 409) {
        setState(() => _stage = _Stage.processing);
        return _startPolling(_client()!);
      }
      _fail('Envio falhou: ${e.message}');
    } on Object catch (e) {
      client.close();
      _fail('Envio falhou: $e');
    }
  }

  void _startPolling(GatewayClient client) {
    _poll?.cancel();
    final id = _captureId!;
    final deadline = DateTime.now().add(const Duration(minutes: 15));
    Future<void> tick() async {
      try {
        final status = await client.status(id);
        if (!mounted) return;
        setState(() => _status = status);
        if (status.finished || DateTime.now().isAfter(deadline)) {
          _poll?.cancel();
          client.close();
        }
      } on Object {
        // a missed poll is not a failure; the next one tries again
      }
    }

    unawaited(tick());
    _poll = Timer.periodic(const Duration(milliseconds: 700), (_) => tick());
  }

  void _newCapture() {
    _poll?.cancel();
    if (_camera.isRecording) {
      _stopSensors();
      unawaited(_discardStrayRecording());
    }
    setState(() {
      _captureId = null;
      _meta = null;
      _status = null;
      _problem = null;
      _stage = _camera.isReady ? _Stage.ready : _Stage.opening;
    });
    if (!_camera.isReady) unawaited(_openCamera());
  }

  void _fail(String message) {
    if (!mounted) return;
    setState(() {
      _stage = _Stage.failed;
      _problem = message;
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Settings

  Future<void> _openSettings() async {
    if (_stage == _Stage.recording) return;
    final updated = await showModalBottomSheet<DemoSettings>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Verge.bgPane,
      builder: (_) => SettingsSheet(
        initial: _settings,
        minExposure: _camera.minExposureOffset,
        maxExposure: _camera.maxExposureOffset,
        onTestConnection: _healthOf,
        gpuClient: _client,
      ),
    );
    if (updated == null || !mounted) return;
    final reopen = _settings.needsReopen(updated);
    setState(() => _settings = updated);
    await _store?.save(updated);
    if (reopen) {
      await _openCamera();
    } else {
      await _camera.applyLive(updated);
      if (mounted) setState(() {});
    }
    unawaited(_checkHealth());
  }

  // ---------------------------------------------------------------------------------------------
  // Layout

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Verge.bgApp,
    body: Stack(
      fit: StackFit.expand,
      children: [
        _preview(),
        if (_stage == _Stage.ready || _stage == _Stage.recording) const Reticle(),
        SafeArea(
          child: Column(
            children: [
              _topBar(),
              if (_settings.debugOverlay) _diagnostics(),
              const Spacer(),
              _bottom(),
            ],
          ),
        ),
      ],
    ),
  );

  Widget _preview() {
    final controller = _camera.controller;
    if (controller == null || !controller.value.isInitialized) {
      return const ColoredBox(color: Verge.bgApp);
    }
    // previewSize is landscape; the screen is portrait.
    final ratio = controller.value.aspectRatio;
    return LayoutBuilder(
      builder: (context, constraints) => GestureDetector(
        onTapUp: (d) => _camera.focusAt(d.localPosition.dx / constraints.maxWidth, d.localPosition.dy / constraints.maxHeight),
        child: ClipRect(
          child: FittedBox(
            fit: BoxFit.cover,
            child: SizedBox(width: 1000, height: 1000 * ratio, child: CameraPreview(controller)),
          ),
        ),
      ),
    );
  }

  Widget _topBar() {
    final h = _health;
    final (label, color) = switch (h) {
      null => ('procurando o gateway', Verge.textDim),
      GatewayHealth(ready: true) => ('gateway conectado', Verge.points),
      GatewayHealth(reachable: true) => ('código de pareamento errado', Verge.busy),
      _ => (_settings.hasGateway ? 'gateway fora do ar' : 'sem gateway', Verge.error),
    };
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      child: Row(
        children: [
          // Two seconds, so a visitor's thumb resting on the title never opens it.
          RawGestureDetector(
            gestures: {
              LongPressGestureRecognizer: GestureRecognizerFactoryWithHandlers<LongPressGestureRecognizer>(
                () => LongPressGestureRecognizer(duration: const Duration(seconds: 2)),
                (r) => r.onLongPress = _openSettings,
              ),
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
              decoration: BoxDecoration(
                color: Verge.bgApp.withValues(alpha: 0.72),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Verge.border),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('VERGE', style: Verge.label.copyWith(color: Verge.emphHi, letterSpacing: 2.4)),
                  const SizedBox(width: 8),
                  Text('medir objeto', style: Verge.label.copyWith(letterSpacing: 0.6, fontWeight: FontWeight.w400)),
                ],
              ),
            ),
          ),
          const Spacer(),
          Chip2(text: label, color: color, dot: true),
        ],
      ),
    );
  }

  Widget _diagnostics() {
    final c = _camera.controller?.value;
    final lines = <String>[
      'pedido ${_settings.resolution.label} @ ${_settings.fps} fps · preview ${c?.previewSize?.width.round()}x${c?.previewSize?.height.round()}',
      'estab. ${_camera.applied['stabilization']} (aceita ${_camera.applied['stabilizationSupported']})',
      'exposição ${_camera.applied['exposureOffset']} EV · faixa ${_camera.applied['exposureOffsetRange']}',
      'lanterna ${_camera.applied['torch']} · trava ${_settings.lockFocusAndExposure}',
      if (_meta != null) 'último: ${((_meta!['bytes'] as int) / 1e6).toStringAsFixed(1)} MB em ${(_meta!['durationMs'] as int) / 1000} s · giro ${_orbit.degrees.round()}°',
      if (_uploadClock != null && _total > 0)
        'envio ${(_uploadClock!.elapsedMilliseconds / 1000).toStringAsFixed(1)} s · ${(_total / 1e6 / (_uploadClock!.elapsedMilliseconds.clamp(1, 1 << 31) / 1000)).toStringAsFixed(1)} MB/s',
    ];
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      padding: const EdgeInsets.all(10),
      width: double.infinity,
      decoration: BoxDecoration(color: Verge.bgApp.withValues(alpha: 0.78), borderRadius: BorderRadius.circular(8)),
      child: Text(lines.join('\n'), style: Verge.number.copyWith(fontSize: 11, color: Verge.text)),
    );
  }

  Widget _panel({required Widget child}) => Container(
    width: double.infinity,
    margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
    padding: const EdgeInsets.fromLTRB(18, 16, 18, 18),
    decoration: BoxDecoration(
      color: Verge.bgPane.withValues(alpha: 0.94),
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: Verge.border),
    ),
    child: child,
  );

  Widget _bottom() {
    switch (_stage) {
      case _Stage.opening:
        return _panel(child: Text('Abrindo a câmera…', style: Verge.body));
      case _Stage.ready:
      case _Stage.recording:
        return _recordControls();
      case _Stage.uploading:
        final fraction = _total == 0 ? 0.0 : _sent / _total;
        return _panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('ENVIANDO O VÍDEO', style: Verge.label),
              const SizedBox(height: 10),
              LinearProgressIndicator(value: fraction, color: Verge.busy, backgroundColor: Verge.border, minHeight: 6),
              const SizedBox(height: 8),
              Text(
                '${(_sent / 1e6).toStringAsFixed(1)} / ${(_total / 1e6).toStringAsFixed(1)} MB',
                style: Verge.number,
              ),
            ],
          ),
        );
      case _Stage.processing:
        final status = _status;
        final busy = status == null || !status.finished;
        return _panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // The header is the toggle: the whole row is the target, not just the chevron, so it
              // can be hit while holding the phone in one hand.
              InkWell(
                onTap: () => setState(() => _phasesExpanded = !_phasesExpanded),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(
                    children: [
                      Text(busy ? 'PROCESSANDO' : 'PRONTO', style: Verge.label.copyWith(color: busy ? Verge.busy : Verge.emphHi)),
                      const Spacer(),
                      Text(_phasesExpanded ? 'minimizar' : 'detalhes', style: Verge.label.copyWith(letterSpacing: 0.3)),
                      Icon(_phasesExpanded ? Icons.expand_more : Icons.expand_less, size: 20, color: Verge.textDim),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 6),
              if (status != null && _phasesExpanded)
                ConstrainedBox(
                  constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.5),
                  child: SingleChildScrollView(reverse: true, child: PhaseList(phases: status.phases, busy: busy)),
                )
              else if (status != null)
                PhaseSummary(phases: status.phases, busy: busy),
              if (status?.state == 'received' && _onMac)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text('O vídeo está no Mac. Abra-o no Verge Studio.', style: Verge.body.copyWith(color: Verge.textDim)),
                ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(onPressed: _newCapture, child: const Text('Nova captura')),
              ),
            ],
          ),
        );
      case _Stage.failed:
        return _panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('ALGO DEU ERRADO', style: Verge.label.copyWith(color: Verge.error)),
              const SizedBox(height: 8),
              Text(_problem ?? '', style: Verge.body),
              const SizedBox(height: 12),
              Row(
                children: [
                  if (_video != null && _meta != null)
                    Expanded(child: FilledButton(onPressed: _send, child: const Text('Enviar de novo'))),
                  if (_video != null && _meta != null) const SizedBox(width: 10),
                  Expanded(child: OutlinedButton(onPressed: _newCapture, child: const Text('Nova captura'))),
                ],
              ),
            ],
          ),
        );
    }
  }

  Widget _recordControls() {
    final recording = _stage == _Stage.recording;
    final elapsed = _clock.elapsedMilliseconds / 1000;
    return Padding(
      padding: const EdgeInsets.only(bottom: 18),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (recording) ...[
            OrbitArc(degrees: _orbit.degrees, tooFast: _orbit.tooFast),
            const SizedBox(height: 4),
            AnimatedOpacity(
              opacity: _orbit.tooFast ? 1 : 0,
              duration: const Duration(milliseconds: 150),
              child: const Chip2(text: 'mais devagar', color: Verge.busy, dot: true),
            ),
            const SizedBox(height: 10),
          ] else
            Padding(
              padding: const EdgeInsets.fromLTRB(28, 0, 28, 16),
              child: Text(
                'Enquadre o objeto inteiro, com a base, a 1–1,5 m.\nGrave girando devagar em volta dele.',
                textAlign: TextAlign.center,
                style: Verge.body.copyWith(color: Verge.emphHi, shadows: const [Shadow(blurRadius: 8, color: Colors.black)]),
              ),
            ),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              SizedBox(
                width: 96,
                child: recording
                    ? Text(_formatClock(elapsed), textAlign: TextAlign.right, style: Verge.number.copyWith(fontSize: 16))
                    : null,
              ),
              const SizedBox(width: 18),
              RecordButton(
                recording: recording,
                progress: elapsed / _settings.maxSeconds,
                onPressed: _camera.isReady ? _toggleRecording : null,
              ),
              const SizedBox(width: 18),
              SizedBox(
                width: 96,
                // What the studio will extract from this much video at the chosen sampling.
                child: recording
                    ? Text(
                        '≈ ${(elapsed * _settings.samplingFps).clamp(0, _settings.maxFrames).round()} quadros',
                        style: Verge.label.copyWith(letterSpacing: 0.3),
                      )
                    : null,
              ),
            ],
          ),
        ],
      ),
    );
  }

  static String _formatClock(double seconds) {
    final s = seconds.floor();
    final tenths = ((seconds - s) * 10).floor();
    return '00:${s.toString().padLeft(2, '0')}.$tenths';
  }
}
