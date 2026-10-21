import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/demo/demo_settings.dart';
import 'package:greenv_capture/src/demo/gateway_client.dart';
import 'package:greenv_capture/src/demo/verge_theme.dart';

/// The round button. Its ring fills toward the automatic stop, so the person filming can see how
/// long is left without reading a number.
final class RecordButton extends StatelessWidget {
  const RecordButton({super.key, required this.recording, required this.progress, required this.onPressed});

  final bool recording;
  final double progress;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    label: recording ? 'Parar gravação' : 'Começar gravação',
    child: GestureDetector(
      onTap: onPressed,
      child: SizedBox(
        width: 92,
        height: 92,
        child: CustomPaint(
          painter: _RingPainter(progress: recording ? progress : 0, enabled: onPressed != null),
          child: Center(
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              width: recording ? 30 : 64,
              height: recording ? 30 : 64,
              decoration: BoxDecoration(
                color: onPressed == null ? Verge.textDim : Verge.recording,
                borderRadius: BorderRadius.circular(recording ? 6 : 32),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

final class _RingPainter extends CustomPainter {
  _RingPainter({required this.progress, required this.enabled});

  final double progress;
  final bool enabled;

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final radius = size.width / 2 - 3;
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 4
        ..color = enabled ? Verge.emphHi.withValues(alpha: 0.85) : Verge.textDim,
    );
    if (progress > 0) {
      canvas.drawArc(
        Rect.fromCircle(center: center, radius: radius),
        -math.pi / 2,
        2 * math.pi * progress.clamp(0.0, 1.0),
        false,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 4
          ..strokeCap = StrokeCap.round
          ..color = Verge.recording,
      );
    }
  }

  @override
  bool shouldRepaint(_RingPainter old) => old.progress != progress || old.enabled != enabled;
}

/// A half-dial of how far the person has walked around the object, with the 90° the
/// reconstruction wants marked on it.
final class OrbitArc extends StatelessWidget {
  const OrbitArc({super.key, required this.degrees, required this.tooFast});

  final double degrees;
  final bool tooFast;

  static const double target = 90;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 220,
    height: 118,
    child: CustomPaint(
      painter: _OrbitPainter(degrees: degrees, tooFast: tooFast),
      child: Align(
        alignment: const Alignment(0, 0.55),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('${degrees.round()}°', style: Verge.number.copyWith(fontSize: 26)),
            Text(
              degrees >= target ? 'ângulo suficiente' : 'gire em volta do objeto',
              style: Verge.label.copyWith(color: degrees >= target ? Verge.emphHi : Verge.textDim),
            ),
          ],
        ),
      ),
    ),
  );
}

final class _OrbitPainter extends CustomPainter {
  _OrbitPainter({required this.degrees, required this.tooFast});

  final double degrees;
  final bool tooFast;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height - 8);
    final radius = size.width / 2 - 10;
    final rect = Rect.fromCircle(center: center, radius: radius);
    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 6
      ..strokeCap = StrokeCap.round
      ..color = Verge.border;
    canvas.drawArc(rect, math.pi, math.pi, false, track);
    // The dial spans 180°; the person's arc fills it from the left.
    final sweep = math.pi * (degrees / 180).clamp(0.0, 1.0);
    canvas.drawArc(
      rect,
      math.pi,
      sweep,
      false,
      track..color = tooFast ? Verge.busy : (degrees >= OrbitArc.target ? Verge.points : Verge.emphHi),
    );
    final mark = math.pi + math.pi * (OrbitArc.target / 180);
    final inner = center + Offset(math.cos(mark), math.sin(mark)) * (radius - 10);
    final outer = center + Offset(math.cos(mark), math.sin(mark)) * (radius + 10);
    canvas.drawLine(inner, outer, Paint()
      ..strokeWidth = 2
      ..color = Verge.textDim);
  }

  @override
  bool shouldRepaint(_OrbitPainter old) => old.degrees != degrees || old.tooFast != tooFast;
}

/// Corner brackets marking where the object should sit. Purely a guide; the video records the
/// whole sensor, which is more than the screen shows.
final class Reticle extends StatelessWidget {
  const Reticle({super.key});

  @override
  Widget build(BuildContext context) => IgnorePointer(
    child: Center(
      child: SizedBox(
        width: 230,
        height: 300,
        child: CustomPaint(painter: _ReticlePainter()),
      ),
    ),
  );
}

final class _ReticlePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..color = Verge.emphHi.withValues(alpha: 0.7);
    const l = 26.0;
    final w = size.width, h = size.height;
    for (final (x, y, dx, dy) in [(0.0, 0.0, 1.0, 1.0), (w, 0.0, -1.0, 1.0), (0.0, h, 1.0, -1.0), (w, h, -1.0, -1.0)]) {
      canvas.drawLine(Offset(x, y), Offset(x + dx * l, y), p);
      canvas.drawLine(Offset(x, y), Offset(x, y + dy * l), p);
    }
    // A short line where the object's base belongs.
    canvas.drawLine(Offset(w * 0.35, h - 18), Offset(w * 0.65, h - 18), p..color = Verge.textDim);
  }

  @override
  bool shouldRepaint(_ReticlePainter oldDelegate) => false;
}

/// Small rounded label on a translucent ground, for status over the camera image.
final class Chip2 extends StatelessWidget {
  const Chip2({super.key, required this.text, this.color = Verge.textDim, this.dot = false});

  final String text;
  final Color color;
  final bool dot;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
    decoration: BoxDecoration(
      color: Verge.bgApp.withValues(alpha: 0.72),
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: Verge.border),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (dot) ...[
          Container(width: 8, height: 8, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
          const SizedBox(width: 7),
        ],
        Text(text, style: Verge.label.copyWith(color: dot ? Verge.text : color, letterSpacing: 0.4)),
      ],
    ),
  );
}

const Map<String, String> phaseLabels = {
  'created': 'Captura anunciada',
  'uploading': 'Enviando o vídeo',
  'received': 'Vídeo recebido',
  'loaded': 'Carregado no Verge Studio',
  'extracting': 'Extraindo quadros',
  'extracted': 'Quadros extraídos',
  'uploading-frames': 'Enviando quadros à GPU',
  'waking': 'Acordando a GPU',
  'loading-model': 'Carregando o modelo',
  'inferring': 'Reconstruindo em 3D',
  'inferred': 'GPU terminou',
  'fetched': 'Nuvem de pontos pronta',
  'measured': 'Medido',
  'failed': 'Falhou',
};

/// The phases the gateway has reported, each with the time it took since the one before. Only
/// what has happened is listed: nothing here advances on a clock.
final class PhaseList extends StatelessWidget {
  const PhaseList({super.key, required this.phases, required this.busy});

  final List<CapturePhase> phases;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final rows = <Widget>[];
    for (var i = 0; i < phases.length; i++) {
      final p = phases[i];
      final last = i == phases.length - 1;
      final delta = i == 0 ? null : (p.tMs - phases[i - 1].tMs) / 1000;
      final failed = p.phase == 'failed';
      final detail = _detail(p);
      rows.add(
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 5),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 20,
                child: last && busy && !failed
                    ? const SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2, color: Verge.busy))
                    : Icon(failed ? Icons.close : Icons.check, size: 14, color: failed ? Verge.error : Verge.emphHi),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(phaseLabels[p.phase] ?? p.phase, style: Verge.body.copyWith(color: last ? Verge.emphHi : Verge.text)),
                    if (detail != null) Text(detail, style: Verge.label.copyWith(letterSpacing: 0.2, fontWeight: FontWeight.w400)),
                  ],
                ),
              ),
              if (delta != null) Text('+${delta.toStringAsFixed(1)} s', style: Verge.number.copyWith(color: Verge.textDim, fontSize: 12)),
            ],
          ),
        ),
      );
    }
    if (phases.length > 1) {
      rows.add(const Divider(height: 14));
      rows.add(
        Row(
          children: [
            const SizedBox(width: 28),
            Expanded(child: Text('Total desde o envio', style: Verge.body.copyWith(color: Verge.textDim))),
            Text('${(phases.last.tMs / 1000).toStringAsFixed(1)} s', style: Verge.number),
          ],
        ),
      );
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: rows);
  }

  static String? _detail(CapturePhase p) {
    final d = p.detail;
    if (d == null) return null;
    if (p.phase == 'received' && d['bytes'] is num) {
      final mb = (d['bytes'] as num) / 1e6;
      return '${mb.toStringAsFixed(1)} MB · ${d['megabytesPerSecond'] ?? '?'} MB/s';
    }
    if (p.phase == 'inferred' && d['gpuSeconds'] is num) {
      return '${d['gpu'] ?? 'GPU'} · ${(d['gpuSeconds'] as num).toStringAsFixed(1)} s de GPU${d['mock'] == true ? ' · mock' : ''}';
    }
    if (d['frames'] is num) return '${d['frames']} quadros';
    if (d['gpu'] is String) return d['gpu'] as String;
    if (d['error'] is String) return d['error'] as String;
    return null;
  }
}

/// The minimised phase list: where the capture is now, and how long it has taken so far.
final class PhaseSummary extends StatelessWidget {
  const PhaseSummary({super.key, required this.phases, required this.busy});

  final List<CapturePhase> phases;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    if (phases.isEmpty) return const SizedBox.shrink();
    final last = phases.last;
    final failed = last.phase == 'failed';
    return Row(
      children: [
        SizedBox(
          width: 20,
          child: busy && !failed
              ? const SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2, color: Verge.busy))
              : Icon(failed ? Icons.close : Icons.check, size: 14, color: failed ? Verge.error : Verge.emphHi),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            phaseLabels[last.phase] ?? last.phase,
            style: Verge.body.copyWith(color: failed ? Verge.error : Verge.emphHi),
            overflow: TextOverflow.ellipsis,
          ),
        ),
        Text('${(last.tMs / 1000).toStringAsFixed(1)} s', style: Verge.number),
      ],
    );
  }
}

/// The operator's sheet. Everything here changes how the next capture is recorded or where it
/// goes; none of it is meant for a visitor.
final class SettingsSheet extends StatefulWidget {
  const SettingsSheet({
    super.key,
    required this.initial,
    required this.minExposure,
    required this.maxExposure,
    required this.onTestConnection,
  });

  final DemoSettings initial;
  final double minExposure;
  final double maxExposure;
  final Future<GatewayHealth> Function(DemoSettings) onTestConnection;

  @override
  State<SettingsSheet> createState() => _SettingsSheetState();
}

final class _SettingsSheetState extends State<SettingsSheet> {
  late DemoSettings _s = widget.initial;
  late final TextEditingController _url = TextEditingController(text: widget.initial.gatewayUrl);
  late final TextEditingController _code = TextEditingController(text: widget.initial.pairingCode);
  String? _connection;

  @override
  void dispose() {
    _url.dispose();
    _code.dispose();
    super.dispose();
  }

  DemoSettings get _current => _s.copyWith(gatewayUrl: _url.text.trim(), pairingCode: _code.text.trim());

  Widget _segmented<T>(String title, List<T> values, T selected, String Function(T) label, void Function(T) onSelect) => Padding(
    padding: const EdgeInsets.only(top: 14),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title.toUpperCase(), style: Verge.label),
        const SizedBox(height: 6),
        SegmentedButton<T>(
          segments: [for (final v in values) ButtonSegment(value: v, label: Text(label(v)))],
          selected: {selected},
          showSelectedIcon: false,
          onSelectionChanged: (set) => setState(() => onSelect(set.first)),
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) {
    final exposureRange = widget.maxExposure > widget.minExposure;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('CONFIGURAÇÃO DO OPERADOR', style: Verge.label.copyWith(color: Verge.emphHi)),
            const SizedBox(height: 14),
            TextField(
              controller: _url,
              keyboardType: TextInputType.url,
              autocorrect: false,
              decoration: const InputDecoration(labelText: 'Gateway', hintText: 'https://…/v1/demo/ ou http://<mac>:8790'),
            ),
            // Letters and digits, typed exactly: the cloud API's code is not the Mac's six digits,
            // and a number pad (or iOS capitalising the first letter) makes it impossible to enter.
            TextField(
              controller: _code,
              keyboardType: TextInputType.visiblePassword,
              autocorrect: false,
              enableSuggestions: false,
              textCapitalization: TextCapitalization.none,
              decoration: const InputDecoration(labelText: 'Código de pareamento'),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                OutlinedButton(
                  onPressed: () async {
                    setState(() => _connection = 'testando…');
                    final h = await widget.onTestConnection(_current);
                    if (!mounted) return;
                    setState(() => _connection = h.ready
                        ? 'conectado (contrato ${h.contract})'
                        : h.reachable
                        ? 'alcançou o gateway, mas o código está errado'
                        : 'sem conexão: ${h.problem}');
                  },
                  child: const Text('Testar conexão'),
                ),
                const SizedBox(width: 12),
                if (_connection != null) Expanded(child: Text(_connection!, style: Verge.body.copyWith(fontSize: 12))),
              ],
            ),
            _segmented<DemoResolution>('Resolução', DemoResolution.values, _s.resolution, (r) => r.label, (r) => _s = _s.copyWith(resolution: r)),
            _segmented<int>('Quadros por segundo', DemoSettings.fpsChoices, _s.fps, (f) => '$f', (f) => _s = _s.copyWith(fps: f)),
            _segmented<int>('Parada automática', DemoSettings.durationChoices, _s.maxSeconds, (d) => '$d s', (d) => _s = _s.copyWith(maxSeconds: d)),
            _segmented<int>('Amostragem (quadros/s)', DemoSettings.samplingFpsChoices, _s.samplingFps, (f) => '$f', (f) => _s = _s.copyWith(samplingFps: f)),
            _segmented<int>('Máximo de quadros', DemoSettings.maxFramesChoices, _s.maxFrames, (n) => '$n', (n) => _s = _s.copyWith(maxFrames: n)),
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                'Acima de 144 só o RTX PRO 6000 tem memória; o Verge corta no limite da placa em uso.',
                style: Verge.label.copyWith(letterSpacing: 0.2, fontWeight: FontWeight.w400),
              ),
            ),
            const SizedBox(height: 8),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Travar foco e exposição ao gravar'),
              subtitle: const Text('Uma câmera só do começo ao fim, como a reconstrução supõe'),
              value: _s.lockFocusAndExposure,
              onChanged: (v) => setState(() => _s = _s.copyWith(lockFocusAndExposure: v)),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Estabilização de vídeo'),
              subtitle: const Text('Desligada por padrão: recorta e deforma cada quadro'),
              value: _s.stabilization,
              onChanged: (v) => setState(() => _s = _s.copyWith(stabilization: v)),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Lanterna'),
              subtitle: const Text('Para galpão escuro; reflexo forte em objeto brilhante'),
              value: _s.torch,
              onChanged: (v) => setState(() => _s = _s.copyWith(torch: v)),
            ),
            if (exposureRange) ...[
              const SizedBox(height: 6),
              Text('COMPENSAÇÃO DE EXPOSIÇÃO · ${_s.exposureOffset.toStringAsFixed(1)} EV', style: Verge.label),
              Slider(
                min: widget.minExposure,
                max: widget.maxExposure,
                value: _s.exposureOffset.clamp(widget.minExposure, widget.maxExposure),
                onChanged: (v) => setState(() => _s = _s.copyWith(exposureOffset: double.parse(v.toStringAsFixed(1)))),
              ),
            ],
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Overlay de diagnóstico'),
              value: _s.debugOverlay,
              onChanged: (v) => setState(() => _s = _s.copyWith(debugOverlay: v)),
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: FilledButton(onPressed: () => Navigator.of(context).pop(_current), child: const Text('Salvar')),
            ),
          ],
        ),
      ),
    );
  }
}
