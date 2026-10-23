import 'dart:async';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/ui/capture/capture_widgets.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class CaptureScreen extends StatefulWidget {
  const CaptureScreen({required this.controller, required this.onNavigate, super.key});

  final CaptureCoordinator controller;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

final class _CaptureScreenState extends State<CaptureScreen> {
  Timer? _secondTick;

  @override
  void initState() {
    super.initState();
    _secondTick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted && widget.controller.isRecording) setState(() {});
    });
  }

  @override
  void dispose() {
    _secondTick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) => MainScaffold(
      page: MotivaPage.upload,
      onNavigate: widget.onNavigate,
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.controller.isRecording) ...[
              _CaptureHeading(controller: widget.controller),
              const SizedBox(height: 12),
              CameraCard(controller: widget.controller),
              const SizedBox(height: 10),
              TelemetryRow(controller: widget.controller),
              if (tooVagueToMeasure(
                widget.controller.telemetry.latestHorizontalAccuracyMeters,
              )) ...[
                const SizedBox(height: 10),
                GnssWarningCard(
                  accuracyMeters: widget.controller.telemetry.latestHorizontalAccuracyMeters,
                ),
              ],
              const SizedBox(height: 10),
            ] else
              _UploadHero(controller: widget.controller),
            QueueCard(controller: widget.controller),
            if (widget.controller.errorMessage != null) ...[
              const SizedBox(height: 10),
              CaptureErrorCard(message: widget.controller.errorMessage!),
            ],
            if (widget.controller.isRecording) ...[
              const SizedBox(height: 12),
              RecordControl(controller: widget.controller),
            ],
          ],
        ),
      ),
    ),
  );
}

final class _UploadHero extends StatelessWidget {
  const _UploadHero({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      const Text(
        'Nova coleta',
        style: TextStyle(
          color: motivaInk,
          fontSize: 24,
          fontWeight: FontWeight.w900,
          letterSpacing: -0.5,
        ),
      ),
      const SizedBox(height: 6),
      const Text(
        'Posicione o telefone com segurança antes de começar.',
        style: TextStyle(color: motivaMuted, fontSize: 13, height: 1.4),
      ),
      const SizedBox(height: 18),
      Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: motivaLine),
          boxShadow: const [
            BoxShadow(color: Color(0x10123E31), blurRadius: 18, offset: Offset(0, 8)),
          ],
        ),
        child: Column(
          children: [
            Container(
              height: 172,
              margin: const EdgeInsets.all(10),
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [Color(0xFFE8F2EC), Color(0xFFDDEBE3)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                borderRadius: BorderRadius.circular(18),
              ),
              child: const Stack(
                fit: StackFit.expand,
                children: [
                  CustomPaint(painter: _FieldPreviewPainter()),
                  Center(
                    child: CircleAvatar(
                      radius: 31,
                      backgroundColor: greenvForest,
                      child: Icon(Icons.videocam_rounded, color: Colors.white, size: 29),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 8, 18, 18),
              child: Column(
                children: [
                  const Text(
                    'Grave o trecho da rodovia',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -0.2,
                    ),
                  ),
                  const SizedBox(height: 7),
                  const Text(
                    'A câmera, o GPS e os sensores serão registrados juntos. A coleta continua mesmo sem internet.',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 12, height: 1.45, color: motivaMuted),
                  ),
                  const SizedBox(height: 16),
                  const Row(
                    children: [
                      Expanded(
                        child: _ReadyIndicator(icon: Icons.camera_alt_outlined, label: 'Câmera'),
                      ),
                      SizedBox(width: 8),
                      Expanded(
                        child: _ReadyIndicator(
                          icon: Icons.location_on_outlined,
                          label: 'Localização',
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  RecordControl(controller: controller),
                ],
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 14),
    ],
  );
}

final class _ReadyIndicator extends StatelessWidget {
  const _ReadyIndicator({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
    decoration: BoxDecoration(color: greenvMint, borderRadius: BorderRadius.circular(12)),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(icon, size: 16, color: motivaGreen),
        const SizedBox(width: 6),
        Flexible(
          child: Text(
            label,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: motivaGreen, fontSize: 11, fontWeight: FontWeight.w800),
          ),
        ),
      ],
    ),
  );
}

final class _CaptureHeading extends StatelessWidget {
  const _CaptureHeading({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Rota em andamento',
              key: Key('capture-state'),
              style: TextStyle(fontSize: 23, fontWeight: FontWeight.w900, letterSpacing: -0.4),
            ),
            const SizedBox(height: 4),
            Text(
              'Segmento ${controller.segmentIndex + 1} sendo gravado',
              style: const TextStyle(fontSize: 12, color: motivaMuted),
            ),
          ],
        ),
      ),
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
        decoration: BoxDecoration(
          color: const Color(0xFFFFE8E8),
          borderRadius: BorderRadius.circular(99),
          border: Border.all(color: const Color(0xFFFFD0D4)),
        ),
        child: const Row(
          children: [
            Icon(Icons.fiber_manual_record, size: 10, color: Color(0xFFD8334A)),
            SizedBox(width: 4),
            Text(
              'GRAVANDO',
              style: TextStyle(
                color: Color(0xFFB52D42),
                fontSize: 9,
                fontWeight: FontWeight.w900,
                letterSpacing: 0.6,
              ),
            ),
          ],
        ),
      ),
    ],
  );
}

final class _FieldPreviewPainter extends CustomPainter {
  const _FieldPreviewPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final hill = Paint()..color = const Color(0xFFBFD9C7);
    canvas.drawOval(
      Rect.fromLTWH(-45, size.height * .46, size.width * .72, size.height * .65),
      hill,
    );
    canvas.drawOval(
      Rect.fromLTWH(size.width * .48, size.height * .38, size.width * .70, size.height * .72),
      Paint()..color = const Color(0xFFA8CEB5),
    );
    final road = Path()
      ..moveTo(size.width * .37, size.height * 1.05)
      ..cubicTo(
        size.width * .47,
        size.height * .72,
        size.width * .65,
        size.height * .70,
        size.width * .60,
        size.height * .34,
      );
    canvas.drawPath(
      road,
      Paint()
        ..color = const Color(0xFFD8DCD8)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 38
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawPath(
      road,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
