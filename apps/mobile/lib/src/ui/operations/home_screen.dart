import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/operations/formatting.dart';
import 'package:greenv_capture/src/ui/operations/remote_data.dart';
import 'package:greenv_capture/src/ui/operations/stretch_sheet.dart';
import 'package:greenv_capture/src/ui/operations/stretches_screen.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class _Overview {
  const _Overview({required this.summary, required this.sessions});

  final ReadingsSummary summary;
  final PageOf<CaptureSessionSummary> sessions;
}

final class HomeScreen extends StatelessWidget {
  const HomeScreen({
    required this.operations,
    required this.capture,
    required this.onNavigate,
    required this.onOpenSession,
    required this.onSignOut,
    super.key,
  });

  final OperationsGateway operations;
  final CaptureCoordinator capture;
  final ValueChanged<MotivaPage> onNavigate;

  /// Opens the map framed on one session. A recorded outing is the unit a field worker thinks
  /// in, and until now tapping one did nothing at all.
  final ValueChanged<String> onOpenSession;

  final Future<void> Function() onSignOut;

  Future<_Overview> _load() async {
    final (summary, sessions) = await both(
      operations.readingsSummary(),
      operations.sessions(limit: 5),
    );
    return _Overview(summary: summary, sessions: sessions);
  }

  @override
  Widget build(BuildContext context) => MainScaffold(
    page: MotivaPage.home,
    onNavigate: onNavigate,
    body: SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  'Olá, equipe de campo',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
              ),
              IconButton(
                key: const Key('sign-out-button'),
                tooltip: 'Sair',
                icon: const Icon(Icons.logout_rounded, size: 20, color: motivaMuted),
                onPressed: onSignOut,
              ),
            ],
          ),
          const SizedBox(height: 6),
          const Text(
            'O que foi gravado e o que já foi medido.',
            style: TextStyle(fontSize: 13, height: 1.4, color: motivaMuted),
          ),
          const SizedBox(height: 20),
          HomeCaptureCard(onTap: () => onNavigate(MotivaPage.upload)),
          const SizedBox(height: 24),
          const SectionTitle(title: 'Resumo da operação'),
          const SizedBox(height: 12),
          RemoteData<_Overview>(
            load: _load,
            builder: (context, overview, _) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: MetricCard(
                        label: 'ACIMA DE 30 CM',
                        icon: Icons.warning_rounded,
                        value: '${overview.summary.count(VegetationLevel.high)}',
                        detail: 'trechos críticos',
                        danger: overview.summary.count(VegetationLevel.high) > 0,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: MetricCard(
                        label: 'MEDIDOS',
                        icon: Icons.straighten_rounded,
                        value: '${overview.summary.total}',
                        detail: 'trechos com leitura',
                        green: true,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: MetricCard(
                        label: 'MAIOR ALTURA',
                        icon: Icons.height_rounded,
                        value: centimetres(overview.summary.tallestM),
                        detail: 'percentil 95',
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: MetricCard(
                        label: 'SESSÕES',
                        icon: Icons.videocam_rounded,
                        value: '${overview.sessions.total}',
                        detail: 'capturas enviadas',
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 26),
                SectionTitle(
                  title: 'Trechos mais altos',
                  action: 'Ver todos',
                  onTap: () => onNavigate(MotivaPage.stretches),
                ),
                const SizedBox(height: 12),
                _TopStretches(operations: operations, onNavigate: onNavigate),
                const SizedBox(height: 26),
                const SectionTitle(title: 'Sessões recentes'),
                const SizedBox(height: 12),
                if (overview.sessions.items.isEmpty)
                  const Text(
                    'Nenhuma sessão enviada ainda. A primeira aparece aqui assim que subir.',
                    style: TextStyle(fontSize: 12.5, color: motivaMuted),
                  ),
                for (final session in overview.sessions.items) ...[
                  InkWell(
                    key: Key('session-${session.sessionId}'),
                    borderRadius: BorderRadius.circular(16),
                    onTap: () => onOpenSession(session.sessionId),
                    child: RecentUpload(
                      name:
                          '${formatDateTime(session.startedAt)} · '
                          '${session.rodovia ?? '${session.segmentCount} trechos'}',
                      status: session.measuredSegmentCount == session.segmentCount
                          ? 'Medida'
                          : '${session.measuredSegmentCount} de ${session.segmentCount}',
                      complete: session.measuredSegmentCount == session.segmentCount,
                    ),
                  ),
                  const SizedBox(height: 10),
                ],
              ],
            ),
          ),
          const SizedBox(height: 8),
          ValueListenableBuilder<int>(
            valueListenable: capture.backlog,
            builder: (context, backlog, _) => backlog == 0
                ? const SizedBox.shrink()
                : Text(
                    '$backlog segmento${backlog == 1 ? '' : 's'} deste aparelho ainda na fila de envio.',
                    style: const TextStyle(fontSize: 11.5, color: motivaPurpleDark),
                  ),
          ),
        ],
      ),
    ),
  );
}

final class _TopStretches extends StatelessWidget {
  const _TopStretches({required this.operations, required this.onNavigate});

  final OperationsGateway operations;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => RemoteData<PageOf<MeasuredStretch>>(
    load: () => operations.stretches(limit: 3),
    emptyWhen: (page) => page.items.isEmpty,
    emptyMessage: 'Nenhum trecho medido ainda.',
    builder: (context, page, _) => Column(
      children: [
        for (final stretch in page.items) ...[
          StretchTile(
            stretch: stretch,
            onTap: () => showStretchSheet(context, stretch, operations),
          ),
          const SizedBox(height: 8),
        ],
      ],
    ),
  );
}
