import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class CameraCard extends StatelessWidget {
  const CameraCard({required this.controller, super.key});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(22),
    child: AspectRatio(
      aspectRatio: 16 / 10,
      child: Stack(
        fit: StackFit.expand,
        children: [
          controller.recorder.buildPreview(),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [Color(0x11000000), Color(0x88000000)],
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
              ),
            ),
          ),
          Positioned(
            left: 12,
            bottom: 11,
            child: _OverlayPill(
              icon: Icons.schedule_rounded,
              text: controller.recordingStartedAt == null
                  ? '00:00:00'
                  : _elapsed(controller.recordingStartedAt!),
            ),
          ),
          const Positioned(
            right: 12,
            bottom: 11,
            child: _OverlayPill(icon: Icons.hd_outlined, text: 'HD'),
          ),
        ],
      ),
    ),
  );

  static String _elapsed(DateTime started) {
    final value = DateTime.now().toUtc().difference(started);
    final hours = value.inHours.toString().padLeft(2, '0');
    final minutes = (value.inMinutes % 60).toString().padLeft(2, '0');
    final seconds = (value.inSeconds % 60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }
}

final class _OverlayPill extends StatelessWidget {
  const _OverlayPill({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
    decoration: BoxDecoration(
      color: const Color(0xB017151A),
      borderRadius: BorderRadius.circular(99),
    ),
    child: Row(
      children: [
        Icon(icon, color: Colors.white, size: 13),
        const SizedBox(width: 4),
        Text(
          text,
          style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
        ),
      ],
    ),
  );
}

/// The accuracy past which the worker stops trusting a fix: `TelemetryAssociator` marks anything
/// vaguer than this `unavailable`, and a segment whose frames are all unavailable yields no
/// measurable trecho. The browser reported 50000 m from an IP-derived position and the app said
/// nothing, so 1.99 MB was recorded, hashed and uploaded for a segment that could never be used.
const usableAccuracyMeters = 25.0;

bool tooVagueToMeasure(double? accuracyMeters) =>
    accuracyMeters == null || accuracyMeters > usableAccuracyMeters;

final class TelemetryRow extends StatelessWidget {
  const TelemetryRow({required this.controller, super.key});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) {
    final accuracy = controller.telemetry.latestHorizontalAccuracyMeters;
    final speed = controller.telemetry.latestSpeedMetersPerSecond;
    final quality = accuracy == null
        ? 'Aguardando'
        : accuracy <= 10
        ? 'Sinal bom'
        : accuracy <= usableAccuracyMeters
        ? 'Sinal médio'
        : 'Sinal fraco';
    return Row(
      children: [
        Expanded(
          child: _StatusTile(
            icon: Icons.gps_fixed_rounded,
            label: 'GPS',
            value: quality,
            detail: accuracy == null ? '—' : '± ${accuracy.toStringAsFixed(1)} m',
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: _StatusTile(
            icon: Icons.speed_rounded,
            label: 'VELOCIDADE',
            value: speed == null ? '— km/h' : '${(speed * 3.6).toStringAsFixed(0)} km/h',
            detail: 'sensor do telefone',
          ),
        ),
      ],
    );
  }
}

final class _StatusTile extends StatelessWidget {
  const _StatusTile({
    required this.icon,
    required this.label,
    required this.value,
    required this.detail,
  });

  final IconData icon;
  final String label;
  final String value;
  final String detail;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(13),
    decoration: cardDecoration(radius: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 17, color: motivaGreen),
            const SizedBox(width: 6),
            Text(
              label,
              style: const TextStyle(
                fontSize: 9,
                letterSpacing: 0.7,
                fontWeight: FontWeight.w800,
                color: motivaMuted,
              ),
            ),
          ],
        ),
        const SizedBox(height: 9),
        Text(value, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w900)),
        Text(detail, style: const TextStyle(fontSize: 10, color: motivaMuted)),
      ],
    ),
  );
}

final class QueueCard extends StatelessWidget {
  const QueueCard({required this.controller, super.key});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<int>(
    valueListenable: controller.backlog,
    builder: (context, backlog, _) => Container(
      key: const Key('queue-card'),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: backlog == 0 ? greenvMint : motivaLilac,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: backlog == 0 ? const Color(0xFFCDE7D8) : const Color(0xFFDED5FF)),
      ),
      child: Row(
        children: [
          const CircleAvatar(
            radius: 16,
            backgroundColor: Color(0xCCFFFFFF),
            child: Icon(Icons.cloud_done_outlined, size: 17, color: motivaGreen),
          ),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  backlog == 0
                      ? 'Tudo enviado'
                      : '$backlog segmento${backlog == 1 ? '' : 's'} na fila',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                    color: backlog == 0 ? greenvForest : motivaPurpleDark,
                  ),
                ),
                const Text(
                  'Os arquivos ficam protegidos até a confirmação do envio.',
                  style: TextStyle(fontSize: 10, color: motivaMuted),
                ),
              ],
            ),
          ),
          Icon(
            backlog == 0 ? Icons.check_circle_rounded : Icons.sync_rounded,
            color: motivaGreen,
            size: 20,
          ),
        ],
      ),
    ),
  );
}

final class RecordControl extends StatelessWidget {
  const RecordControl({required this.controller, super.key});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) {
    final busy =
        controller.phase == CapturePhase.preparing || controller.phase == CapturePhase.stopping;
    final recording = controller.isRecording;
    return FilledButton.icon(
      key: const Key('record-button'),
      onPressed: busy
          ? null
          : recording
          ? controller.stop
          : controller.start,
      style: FilledButton.styleFrom(
        backgroundColor: recording ? const Color(0xFFB52D42) : greenvForest,
      ),
      icon: busy
          ? const SizedBox.square(
              dimension: 16,
              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
            )
          : Icon(recording ? Icons.stop_rounded : Icons.videocam_rounded, size: 17),
      label: Text(
        controller.phase == CapturePhase.stopping
            ? 'Finalizando…'
            : controller.phase == CapturePhase.preparing
            ? 'Preparando…'
            : recording
            ? 'Encerrar coleta'
            : 'Iniciar coleta',
      ),
    );
  }
}

final class CaptureErrorCard extends StatelessWidget {
  const CaptureErrorCard({required this.message, super.key});

  final String message;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFFFFECE9),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFFFD5CF)),
    ),
    child: Row(
      children: [
        const Icon(Icons.warning_amber_rounded, color: Color(0xFFB5283D), size: 20),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            message,
            maxLines: 3,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, height: 1.35),
          ),
        ),
      ],
    ),
  );
}

/// Says out loud what the GPS tile only implies: at this accuracy the capture is not worth its
/// bytes. It is deliberately louder than the tile, because the tile reads as a reading and this is
/// a decision the person can still act on — stepping outside, or not driving the route yet.
final class GnssWarningCard extends StatelessWidget {
  const GnssWarningCard({required this.accuracyMeters, super.key});

  final double? accuracyMeters;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('gnss-warning'),
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFFFFF4E3),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFF6DCB6)),
    ),
    child: Row(
      children: [
        const Icon(Icons.gps_off_rounded, color: greenvAmber, size: 20),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            accuracyMeters == null
                ? 'Sem posição do GPS. Sem ela esta coleta não gera um trecho '
                      'medível: confira a permissão de localização.'
                : 'GPS com ± ${accuracyMeters!.toStringAsFixed(1)} m de erro, '
                      'acima do limite de '
                      '${usableAccuracyMeters.toStringAsFixed(0)} m. Nesta '
                      'precisão a coleta não gera um trecho medível.',
            style: const TextStyle(fontSize: 12, height: 1.35),
          ),
        ),
      ],
    ),
  );
}
