import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:greenv_capture/src/api/http_operations_gateway.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/shell.dart';
import 'package:latlong2/latlong.dart';

/// The screens that read back what was uploaded, and the one that opens an order.
///
/// Every number on these screens came from the API in this run. The previous home screen said
/// "1.240 km", "12 alertas" and "291 trechos" from string literals, over a map painted by hand;
/// a field worker had no way to tell that from a measurement. Now a screen that cannot reach the
/// API says so and offers to try again, and capture — which lives elsewhere — never waits on it.
///
/// Level colours are the dashboard's, to the hex, so a stretch is the same red on the phone and
/// on the wall.
Color levelColour(VegetationLevel level) => switch (level) {
  VegetationLevel.high => const Color(0xFFDC2626),
  VegetationLevel.medium => const Color(0xFFCA8A04),
  VegetationLevel.low => const Color(0xFF42BB6F),
  VegetationLevel.unknown => const Color(0xFF777780),
};

Color levelBackground(VegetationLevel level) =>
    levelColour(level).withValues(alpha: 0.16);

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

String centimetres(double? metres) =>
    metres == null ? '—' : '${(metres * 100).round()} cm';

String squareMetres(double? area) {
  if (area == null) return '—';
  if (area >= 10000) return '${(area / 10000).toStringAsFixed(2)} ha';
  return '${area.round()} m²';
}

/// What a person sees while the API answers, and what they see when it does not.
///
/// The API scales to zero and takes ten to twenty-five seconds to wake. For the first two
/// seconds this shows a plain spinner, because a warm answer arrives before there is anything
/// worth explaining; past that it says the server is waking and counts, since a number that
/// moves is the difference between waiting and thinking the screen has hung. A failure shows
/// the API's own words and a button, not a blank card.
final class RemoteData<T> extends StatefulWidget {
  const RemoteData({
    required this.load,
    required this.builder,
    this.emptyWhen,
    this.emptyMessage = 'Nada por aqui ainda.',
    super.key,
  });

  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data, VoidCallback reload)
  builder;
  final bool Function(T data)? emptyWhen;
  final String emptyMessage;

  @override
  State<RemoteData<T>> createState() => _RemoteDataState<T>();
}

final class _RemoteDataState<T> extends State<RemoteData<T>> {
  late Future<T> _future = widget.load();
  int _waitingSeconds = 0;
  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    _startTicking();
  }

  void _startTicking() {
    _ticker?.cancel();
    _waitingSeconds = 0;
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() => _waitingSeconds += 1);
    });
    // `whenComplete` hands back a second future that fails with the same error, and one nobody
    // listens to is an unhandled rejection on every failed load. The FutureBuilder above is the
    // listener that matters; this one only stops the clock.
    _future.whenComplete(() {
      _ticker?.cancel();
      _ticker = null;
    }).ignore();
  }

  void _reload() {
    setState(() {
      _future = widget.load();
      _startTicking();
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<T>(
    future: _future,
    builder: (context, snapshot) {
      if (snapshot.connectionState != ConnectionState.done) {
        return _Waiting(seconds: _waitingSeconds);
      }
      if (snapshot.hasError) {
        return _Failed(error: snapshot.error!, retry: _reload);
      }
      final data = snapshot.data as T;
      if (widget.emptyWhen?.call(data) ?? false) {
        return _Empty(message: widget.emptyMessage);
      }
      return widget.builder(context, data, _reload);
    },
  );
}

final class _Waiting extends StatelessWidget {
  const _Waiting({required this.seconds});

  final int seconds;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 40),
    child: Column(
      children: [
        const SizedBox(
          width: 22,
          height: 22,
          child: CircularProgressIndicator(
            strokeWidth: 2.5,
            color: greenvForest,
          ),
        ),
        const SizedBox(height: 14),
        Text(
          seconds < 2 ? 'Carregando…' : 'Acordando o servidor…',
          style: const TextStyle(fontWeight: FontWeight.w800, color: motivaInk),
        ),
        if (seconds >= 2) ...[
          const SizedBox(height: 4),
          Text(
            'A API desliga quando ninguém a usa e leva alguns segundos para subir. ${seconds}s.',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 11.5, color: motivaMuted),
          ),
        ],
      ],
    ),
  );
}

final class _Failed extends StatelessWidget {
  const _Failed({required this.error, required this.retry});

  final Object error;
  final VoidCallback retry;

  @override
  Widget build(BuildContext context) {
    final detail = switch (error) {
      OperationsFailure(unauthorized: true) =>
        'Sua sessão terminou. Entre de novo.',
      OperationsFailure(:final detail) => detail,
      _ => 'Sem resposta da API. Verifique o sinal e tente de novo.',
    };
    return Container(
      key: const Key('remote-error'),
      margin: const EdgeInsets.symmetric(vertical: 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFFFFECE9),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: const Color(0xFFFFD5CF)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Não foi possível carregar',
            style: TextStyle(
              fontWeight: FontWeight.w800,
              color: Color(0xFFC72F2F),
            ),
          ),
          const SizedBox(height: 4),
          Text(detail, style: const TextStyle(fontSize: 12, color: motivaInk)),
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: retry,
            style: OutlinedButton.styleFrom(
              minimumSize: const Size.fromHeight(40),
            ),
            child: const Text('Tentar de novo'),
          ),
        ],
      ),
    );
  }
}

final class _Empty extends StatelessWidget {
  const _Empty({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 36),
    child: Column(
      children: [
        const Icon(Icons.straighten_rounded, size: 34, color: motivaLine),
        const SizedBox(height: 10),
        Text(
          message,
          textAlign: TextAlign.center,
          style: const TextStyle(color: motivaMuted),
        ),
      ],
    ),
  );
}

// ---------------------------------------------------------------------------------------------
// Início
// ---------------------------------------------------------------------------------------------

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
    final summary = operations.readingsSummary();
    final sessions = operations.sessions(limit: 5);
    return _Overview(summary: await summary, sessions: await sessions);
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
                icon: const Icon(
                  Icons.logout_rounded,
                  size: 20,
                  color: motivaMuted,
                ),
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
                        value:
                            '${overview.summary.count(VegetationLevel.high)}',
                        detail: 'trechos críticos',
                        danger:
                            overview.summary.count(VegetationLevel.high) > 0,
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
                      status:
                          session.measuredSegmentCount == session.segmentCount
                          ? 'Medida'
                          : '${session.measuredSegmentCount} de ${session.segmentCount}',
                      complete:
                          session.measuredSegmentCount == session.segmentCount,
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
                    style: const TextStyle(
                      fontSize: 11.5,
                      color: motivaPurpleDark,
                    ),
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

// ---------------------------------------------------------------------------------------------
// Trechos
// ---------------------------------------------------------------------------------------------

final class StretchesScreen extends StatefulWidget {
  const StretchesScreen({
    required this.operations,
    required this.onNavigate,
    super.key,
  });

  final OperationsGateway operations;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  State<StretchesScreen> createState() => _StretchesScreenState();
}

final class _StretchesScreenState extends State<StretchesScreen> {
  static const int pageSize = 25;

  VegetationLevel? _level;
  int _offset = 0;
  // Changing the filter or the page must ask the API again; a key change does that.
  int _generation = 0;

  void _pick(VegetationLevel? level) => setState(() {
    _level = _level == level ? null : level;
    _offset = 0;
    _generation += 1;
  });

  void _page(int offset) => setState(() {
    _offset = offset;
    _generation += 1;
  });

  @override
  Widget build(BuildContext context) => MainScaffold(
    page: MotivaPage.stretches,
    onNavigate: widget.onNavigate,
    body: SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'Trechos medidos',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 6),
          const Text(
            'Toda leitura, a mais alta primeiro. Toque num trecho para ver e abrir uma ordem.',
            style: TextStyle(fontSize: 13, height: 1.4, color: motivaMuted),
          ),
          const SizedBox(height: 16),
          RemoteData<ReadingsSummary>(
            key: ValueKey('summary-$_generation'),
            load: widget.operations.readingsSummary,
            builder: (context, summary, _) =>
                LevelChips(summary: summary, selected: _level, onPick: _pick),
          ),
          const SizedBox(height: 14),
          RemoteData<PageOf<MeasuredStretch>>(
            key: ValueKey('page-$_generation'),
            load: () => widget.operations.stretches(
              level: _level?.number,
              limit: pageSize,
              offset: _offset,
            ),
            emptyWhen: (page) => page.items.isEmpty,
            emptyMessage: 'Nenhuma leitura com esse filtro.',
            builder: (context, page, _) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final (index, stretch) in page.items.indexed) ...[
                  StretchTile(
                    stretch: stretch,
                    rank: _offset + index + 1,
                    onTap: () =>
                        showStretchSheet(context, stretch, widget.operations),
                  ),
                  const SizedBox(height: 8),
                ],
                if (page.total > pageSize)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Row(
                      children: [
                        Text(
                          '${_offset + 1}–${_offset + page.items.length} de ${page.total}',
                          style: const TextStyle(
                            fontSize: 11.5,
                            color: motivaMuted,
                          ),
                        ),
                        const Spacer(),
                        TextButton(
                          onPressed: _offset == 0
                              ? null
                              : () => _page(_offset - pageSize),
                          child: const Text('Anterior'),
                        ),
                        TextButton(
                          onPressed: page.hasMore
                              ? () => _page(_offset + pageSize)
                              : null,
                          child: const Text('Próxima'),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

/// The level chips, each carrying its count so choosing one is an informed tap.
final class LevelChips extends StatelessWidget {
  const LevelChips({
    required this.summary,
    required this.selected,
    required this.onPick,
    super.key,
  });

  final ReadingsSummary summary;
  final VegetationLevel? selected;
  final ValueChanged<VegetationLevel?> onPick;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 8,
    runSpacing: 8,
    children: [
      _Chip(
        label: 'Todos',
        count: summary.total,
        colour: greenvForest,
        active: selected == null,
        onTap: () => onPick(null),
      ),
      for (final level in const [
        VegetationLevel.high,
        VegetationLevel.medium,
        VegetationLevel.low,
        VegetationLevel.unknown,
      ])
        _Chip(
          label: level.description,
          count: summary.count(level),
          colour: levelColour(level),
          active: selected == level,
          onTap: () => onPick(level),
        ),
    ],
  );
}

final class _Chip extends StatelessWidget {
  const _Chip({
    required this.label,
    required this.count,
    required this.colour,
    required this.active,
    required this.onTap,
  });

  final String label;
  final int count;
  final Color colour;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    borderRadius: BorderRadius.circular(99),
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
      decoration: BoxDecoration(
        color: active ? colour.withValues(alpha: 0.12) : Colors.white,
        borderRadius: BorderRadius.circular(99),
        border: Border.all(color: active ? colour : motivaLine, width: 1.4),
      ),
      child: Text(
        '$label  $count',
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w800,
          color: active ? colour : motivaMuted,
        ),
      ),
    ),
  );
}

final class StretchTile extends StatelessWidget {
  const StretchTile({
    required this.stretch,
    required this.onTap,
    this.rank,
    super.key,
  });

  final MeasuredStretch stretch;
  final VoidCallback onTap;
  final int? rank;

  @override
  Widget build(BuildContext context) {
    final reading = stretch.reading;
    final place = stretch.place;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: cardDecoration(radius: 16),
        child: Row(
          children: [
            Container(
              width: 6,
              height: 42,
              decoration: BoxDecoration(
                color: levelColour(reading.level),
                borderRadius: BorderRadius.circular(3),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    rank == null ? place.label : '$rank · ${place.label}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  if (place.detail.isNotEmpty)
                    Text(
                      place.detail,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11, color: motivaMuted),
                    ),
                  const SizedBox(height: 4),
                  Text(
                    '${formatDate(stretch.capturedAt)} · ${stretch.locationQuality ?? 'sem GPS'}',
                    style: const TextStyle(fontSize: 10.5, color: motivaMuted),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  stretch.heightCm == null ? '—' : '${stretch.heightCm} cm',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w900,
                    color: levelColour(reading.level),
                  ),
                ),
                Container(
                  margin: const EdgeInsets.only(top: 3),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 7,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: levelBackground(reading.level),
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: Text(
                    reading.label,
                    style: TextStyle(
                      fontSize: 9.5,
                      fontWeight: FontWeight.w800,
                      color: levelColour(reading.level),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// One stretch, and the button that turns it into work.
Future<void> showStretchSheet(
  BuildContext context,
  MeasuredStretch stretch,
  OperationsGateway operations,
) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  backgroundColor: Colors.white,
  shape: const RoundedRectangleBorder(
    borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
  ),
  builder: (sheetContext) =>
      StretchSheet(stretch: stretch, operations: operations),
);

/// One stretch: its photographs, what each of them measured, the facts, and the button that
/// turns it into work.
///
/// The photographs are the point. The dashboard opens a stretch into a strip of frames and a
/// panel saying what the chosen one contributed, and a field worker standing at the verge has
/// more use for that than anyone at a desk: the picture is how they know the row is the stretch
/// in front of them. This is that, laid down a phone instead of across a screen.
final class StretchSheet extends StatefulWidget {
  const StretchSheet({
    required this.stretch,
    required this.operations,
    super.key,
  });

  final MeasuredStretch stretch;
  final OperationsGateway operations;

  @override
  State<StretchSheet> createState() => _StretchSheetState();
}

final class _StretchSheetState extends State<StretchSheet> {
  late final Future<List<SampledFrame>> _frames = widget.operations.frames(
    widget.stretch.sessionId,
    widget.stretch.segmentIndex,
  );
  SampledFrame? _selected;

  @override
  Widget build(BuildContext context) {
    final stretch = widget.stretch;
    final reading = stretch.reading;
    final place = stretch.place;
    final canOrder = stretch.vegetationLevel != VegetationLevel.unknown;

    // A sheet, not a screen: it opens at seven tenths of the phone so the map stays visible
    // behind it, drags to almost full, and scrolls inside rather than pushing the button off
    // the bottom. The old one was a fixed column that ran past the edge on a real handset.
    return DraggableScrollableSheet(
      initialChildSize: 0.72,
      minChildSize: 0.4,
      maxChildSize: 0.95,
      expand: false,
      builder: (context, scrollController) => ListView(
        controller: scrollController,
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        children: [
          Center(
            child: Container(
              width: 44,
              height: 4,
              decoration: BoxDecoration(
                color: const Color(0xFFCAD5CD),
                borderRadius: BorderRadius.circular(99),
              ),
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Flexible(
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 9,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: levelBackground(reading.level),
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: Text(
                    reading.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      color: levelColour(reading.level),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Text(
                stretch.heightCm == null ? '-' : '${stretch.heightCm} cm',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w900,
                  color: levelColour(reading.level),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(place.label, style: Theme.of(context).textTheme.titleLarge),
          if (place.detail.isNotEmpty)
            Text(
              place.detail,
              style: const TextStyle(fontSize: 12.5, color: motivaMuted),
            ),
          const SizedBox(height: 16),
          FutureBuilder<List<SampledFrame>>(
            future: _frames,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const SizedBox(
                  height: 78,
                  child: Center(
                    child: SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2.2,
                        color: greenvForest,
                      ),
                    ),
                  ),
                );
              }
              // A segment with no manifest answers 409, which is an answer and not a failure:
              // the rest of the sheet is still worth reading.
              final frames = snapshot.hasError
                  ? const <SampledFrame>[]
                  : snapshot.data!;
              if (frames.isEmpty) {
                return const Text(
                  'Este trecho não publicou quadros.',
                  style: TextStyle(fontSize: 12, color: motivaMuted),
                );
              }
              final selected = _selected ?? frames.first;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  FramePhoto(
                    key: ValueKey(selected.fileName),
                    frame: selected,
                    operations: widget.operations,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '${selected.fileName} · '
                    '${selected.capturedAtUtc == null ? 'sem horário' : formatDateTime(selected.capturedAtUtc!)}'
                    ' · precisão ${selected.horizontalAccuracyMeters?.toStringAsFixed(1) ?? '?'} m',
                    style: const TextStyle(fontSize: 11, color: motivaMuted),
                  ),
                  const SizedBox(height: 10),
                  SizedBox(
                    height: 58,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: frames.length,
                      separatorBuilder: (_, _) => const SizedBox(width: 6),
                      itemBuilder: (context, index) => FrameThumb(
                        frame: frames[index],
                        operations: widget.operations,
                        active: frames[index].fileName == selected.fileName,
                        onTap: () => setState(() => _selected = frames[index]),
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  FrameMeasurement(
                    key: ValueKey('leitura-${selected.fileName}'),
                    stretch: stretch,
                    frame: selected,
                    operations: widget.operations,
                  ),
                ],
              );
            },
          ),
          const SizedBox(height: 16),
          _Facts(
            rows: [
              ('Capturado em', formatDateTime(stretch.capturedAt)),
              if (stretch.measuredAt != null)
                ('Medido em', formatDateTime(stretch.measuredAt!)),
              ('Altura máxima', centimetres(stretch.extent95MaxM)),
              (
                'Células',
                stretch.cellsMeasured == null
                    ? '-'
                    : '${stretch.cellsMeasured} medidas · ${stretch.cellsAbstained ?? 0} sem evidência',
              ),
              if (stretch.coverage != null)
                ('Cobertura', '${(stretch.coverage! * 100).round()}%'),
              ('GPS', stretch.locationQuality ?? 'sem posição'),
              (
                'Trecho',
                '${stretch.segmentIndex} da sessão ${shortId(stretch.sessionId)}',
              ),
            ],
          ),
          const SizedBox(height: 18),
          FilledButton.icon(
            key: const Key('open-order-button'),
            onPressed: canOrder
                ? () async {
                    Navigator.of(context).pop();
                    await showOrderForm(context, [stretch], widget.operations);
                  }
                : null,
            icon: const Icon(Icons.assignment_add, size: 18),
            label: const Text('Abrir ordem de serviço'),
          ),
          if (!canOrder)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text(
                'Só um trecho com altura medida pode justificar uma ordem.',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 11.5, color: motivaMuted),
              ),
            ),
        ],
      ),
    );
  }
}

/// A photograph from behind the bearer, fetched as bytes and held while the sheet is open.
///
/// `Image.network` cannot do this: the route needs an Authorization header, and without one the
/// API answers 401 and the widget shows a broken icon with no way to say why.
final class FramePhoto extends StatefulWidget {
  const FramePhoto({required this.frame, required this.operations, super.key});

  final SampledFrame frame;
  final OperationsGateway operations;

  @override
  State<FramePhoto> createState() => _FramePhotoState();
}

final class _FramePhotoState extends State<FramePhoto> {
  late final Future<Uint8List> _bytes = widget.operations.frameImage(
    widget.frame.imageUrl,
  );

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(14),
    child: ColoredBox(
      color: motivaCanvas,
      child: SizedBox(
        height: 260,
        width: double.infinity,
        child: FutureBuilder<Uint8List>(
          future: _bytes,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const Center(
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    strokeWidth: 2.2,
                    color: greenvForest,
                  ),
                ),
              );
            }
            if (snapshot.hasError) {
              return const Center(
                child: Text(
                  'Não foi possível carregar a foto.',
                  style: TextStyle(fontSize: 12, color: motivaMuted),
                ),
              );
            }
            // Contained, not cropped: the frames come out of the camera upright, 576 by 1024,
            // and cropping one into a wide box throws away the verge it was taken for.
            return Image.memory(snapshot.data!, fit: BoxFit.contain);
          },
        ),
      ),
    ),
  );
}

final class FrameThumb extends StatefulWidget {
  const FrameThumb({
    required this.frame,
    required this.operations,
    required this.active,
    required this.onTap,
    super.key,
  });

  final SampledFrame frame;
  final OperationsGateway operations;
  final bool active;
  final VoidCallback onTap;

  @override
  State<FrameThumb> createState() => _FrameThumbState();
}

final class _FrameThumbState extends State<FrameThumb> {
  late final Future<Uint8List> _bytes = widget.operations.frameImage(
    widget.frame.imageUrl,
  );

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: widget.onTap,
    child: Container(
      width: 52,
      height: 58,
      decoration: BoxDecoration(
        color: motivaCanvas,
        borderRadius: BorderRadius.circular(9),
        border: Border.all(
          color: widget.active ? greenvForest : Colors.transparent,
          width: 2.2,
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: FutureBuilder<Uint8List>(
        future: _bytes,
        builder: (context, snapshot) => snapshot.hasData
            ? Image.memory(snapshot.data!, fit: BoxFit.cover)
            : const SizedBox.shrink(),
      ),
    ),
  );
}

/// What this photograph contributed, in the same words the dashboard uses.
final class FrameMeasurement extends StatefulWidget {
  const FrameMeasurement({
    required this.stretch,
    required this.frame,
    required this.operations,
    super.key,
  });

  final MeasuredStretch stretch;
  final SampledFrame frame;
  final OperationsGateway operations;

  @override
  State<FrameMeasurement> createState() => _FrameMeasurementState();
}

final class _FrameMeasurementState extends State<FrameMeasurement> {
  late final Future<FrameReadings?> _readings = widget.operations.frameReadings(
    widget.stretch.sessionId,
    widget.stretch.segmentIndex,
    widget.frame.fileName,
  );

  @override
  Widget build(BuildContext context) => FutureBuilder<FrameReadings?>(
    future: _readings,
    builder: (context, snapshot) {
      if (snapshot.connectionState != ConnectionState.done) {
        return const SizedBox(height: 20);
      }
      final readings = snapshot.hasError ? null : snapshot.data;
      if (readings == null || readings.cellsVoted == 0) {
        return const Text(
          'Este quadro não votou em nenhuma célula. Ou não alcançou o piso de voxels em lugar '
          'nenhum da faixa, ou o pacote desta medição não guardou o detalhe.',
          style: TextStyle(fontSize: 11, color: motivaMuted, height: 1.45),
        );
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'O QUE ESTE QUADRO MEDIU',
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w800,
              letterSpacing: 0.6,
              color: motivaMuted,
            ),
          ),
          const SizedBox(height: 6),
          _Facts(
            rows: [
              ('Células votadas', '${readings.cellsVoted}'),
              ('Altura mediana', centimetres(readings.extent95MedianM)),
              ('Maior altura', centimetres(readings.extent95MaxM)),
              if (readings.largestDisagreementM != null)
                (
                  'Maior discordância',
                  centimetres(readings.largestDisagreementM),
                ),
            ],
          ),
          if (readings.evidenceForCells > 0)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                'Escolhido como evidência em ${readings.evidenceForCells} '
                '${readings.evidenceForCells == 1 ? 'célula' : 'células'}.',
                style: const TextStyle(fontSize: 10.5, color: motivaMuted),
              ),
            ),
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Text(
              'Altura acima do solo local de cada célula. A discordância é a diferença entre o '
              'que este quadro votou e o que a célula concluiu com todos os quadros.',
              style: TextStyle(
                fontSize: 10.5,
                color: motivaMuted,
                height: 1.45,
              ),
            ),
          ),
        ],
      );
    },
  );
}

final class _Facts extends StatelessWidget {
  const _Facts({required this.rows});

  final List<(String, String)> rows;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
    decoration: BoxDecoration(
      color: motivaCanvas,
      borderRadius: BorderRadius.circular(14),
    ),
    child: Column(
      children: [
        for (final (label, value) in rows)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: const TextStyle(fontSize: 12, color: motivaMuted),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    value,
                    textAlign: TextAlign.right,
                    style: const TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
          ),
      ],
    ),
  );
}

// ---------------------------------------------------------------------------------------------
// Abrir ordem
// ---------------------------------------------------------------------------------------------

Future<ServiceOrder?> showOrderForm(
  BuildContext context,
  List<MeasuredStretch> targets,
  OperationsGateway operations,
) => showModalBottomSheet<ServiceOrder>(
  context: context,
  isScrollControlled: true,
  backgroundColor: Colors.white,
  shape: const RoundedRectangleBorder(
    borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
  ),
  builder: (sheetContext) =>
      OrderForm(targets: targets, operations: operations),
);

final class OrderForm extends StatefulWidget {
  const OrderForm({required this.targets, required this.operations, super.key});

  final List<MeasuredStretch> targets;
  final OperationsGateway operations;

  @override
  State<OrderForm> createState() => _OrderFormState();
}

final class _OrderFormState extends State<OrderForm> {
  late OrderPriority _priority = OrderPriority.suggestedFor(_worst);
  String? _teamId;
  DateTime? _scheduledFor;
  final _notes = TextEditingController();
  bool _sending = false;
  String? _error;

  VegetationLevel get _worst => widget.targets
      .map((t) => t.vegetationLevel)
      .reduce((a, b) => a.number >= b.number ? a : b);

  @override
  void dispose() {
    _notes.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      final order = await widget.operations.openOrder(
        OrderDraft(
          priority: _priority,
          teamId: _teamId,
          scheduledFor: _scheduledFor,
          notes: _notes.text,
          targets: widget.targets,
        ),
      );
      if (!mounted) return;
      Navigator.of(context).pop(order);
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text('Ordem ${order.reference} aberta.')),
      );
    } on Object catch (failure) {
      if (!mounted) return;
      setState(() {
        _sending = false;
        _error = failure is OperationsFailure
            ? failure.detail
            : 'Sem resposta da API. A ordem não foi aberta.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final tallest = widget.targets
        .map((t) => t.extent95P95M ?? 0)
        .fold<double>(0, (a, b) => a > b ? a : b);
    return Padding(
      padding: EdgeInsets.fromLTRB(
        20,
        14,
        20,
        20 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              widget.targets.length > 1
                  ? 'Nova OS combinada'
                  : 'Nova ordem de serviço',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 12),
            _Facts(
              rows: [
                ('Trechos', '${widget.targets.length}'),
                ('Nível', _worst.label),
                ('Maior altura p90', centimetres(tallest)),
              ],
            ),
            const SizedBox(height: 16),
            const _FieldLabel('Prioridade'),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final priority in OrderPriority.values)
                  ChoiceChip(
                    key: Key('priority-${priority.wire}'),
                    label: Text(priority.label),
                    selected: _priority == priority,
                    onSelected: (_) => setState(() => _priority = priority),
                  ),
              ],
            ),
            const SizedBox(height: 14),
            const _FieldLabel('Equipe'),
            RemoteData<List<Team>>(
              load: widget.operations.teams,
              builder: (context, teams, _) => DropdownButtonFormField<String?>(
                key: const Key('team-field'),
                initialValue: _teamId,
                items: [
                  const DropdownMenuItem<String?>(
                    child: Text('Sem equipe atribuída'),
                  ),
                  for (final team in teams)
                    DropdownMenuItem<String?>(
                      value: team.teamId,
                      child: Text(team.name),
                    ),
                ],
                onChanged: (value) => setState(() => _teamId = value),
              ),
            ),
            const SizedBox(height: 14),
            const _FieldLabel('Data prevista'),
            OutlinedButton.icon(
              key: const Key('date-field'),
              onPressed: () async {
                final now = DateTime.now();
                final picked = await showDatePicker(
                  context: context,
                  initialDate: _scheduledFor ?? now,
                  firstDate: now.subtract(const Duration(days: 1)),
                  lastDate: now.add(const Duration(days: 365)),
                );
                if (picked != null) setState(() => _scheduledFor = picked);
              },
              icon: const Icon(Icons.event_rounded, size: 18),
              label: Text(
                _scheduledFor == null
                    ? 'Escolher data'
                    : formatDate(_scheduledFor!),
              ),
            ),
            const SizedBox(height: 14),
            const _FieldLabel('Observações'),
            TextField(
              key: const Key('notes-field'),
              controller: _notes,
              maxLines: 3,
              decoration: const InputDecoration(
                hintText: 'Acesso, restrições, o que a equipe precisa saber…',
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                key: const Key('order-error'),
                style: const TextStyle(
                  color: Color(0xFFC72F2F),
                  fontSize: 12.5,
                ),
              ),
            ],
            const SizedBox(height: 18),
            FilledButton(
              key: const Key('submit-order-button'),
              onPressed: _sending ? null : _submit,
              child: Text(_sending ? 'Abrindo…' : 'Abrir ordem'),
            ),
          ],
        ),
      ),
    );
  }
}

final class _FieldLabel extends StatelessWidget {
  const _FieldLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Text(
      text,
      style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800),
    ),
  );
}

// ---------------------------------------------------------------------------------------------
// Mapa
// ---------------------------------------------------------------------------------------------

final class MapScreen extends StatefulWidget {
  const MapScreen({
    required this.operations,
    required this.onNavigate,
    this.sessionId,
    this.onClearSession,
    super.key,
  });

  final OperationsGateway operations;
  final ValueChanged<MotivaPage> onNavigate;

  /// When set, the map holds one session and frames itself on it. Opening a session is a
  /// question about that outing, and every other pin is an answer to a different one.
  final String? sessionId;

  final VoidCallback? onClearSession;

  @override
  State<MapScreen> createState() => _MapScreenState();
}

final class _MapScreenState extends State<MapScreen> {
  final _controller = MapController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MainScaffold(
    page: MotivaPage.map,
    onNavigate: widget.onNavigate,
    body: RemoteData<List<MeasuredStretch>>(
      // One request for the pins. Each stretch is a point at its centre; the full track of a
      // session is the dashboard's job, and two hundred polylines on a phone is not a map.
      key: ValueKey(widget.sessionId ?? 'todas'),
      load: () async => widget.sessionId == null
          ? (await widget.operations.stretches(limit: 200)).items
          : await widget.operations.sessionStretches(widget.sessionId!),
      emptyWhen: (rows) => rows.where((s) => s.located).isEmpty,
      emptyMessage: widget.sessionId == null
          ? 'Nenhum trecho medido com posição ainda.'
          : 'Esta sessão não tem trecho com posição.',
      builder: (context, rows, _) {
        final located = rows.where((s) => s.located).toList();
        final points = [
          for (final s in located) LatLng(s.centreLat!, s.centreLon!),
        ];
        return Stack(
          children: [
            Positioned.fill(
              child: FlutterMap(
                mapController: _controller,
                options: MapOptions(
                  initialCenter: points.first,
                  initialZoom: widget.sessionId == null ? 14 : 16,
                  onMapReady: () => _controller.fitCamera(
                    CameraFit.coordinates(
                      coordinates: points,
                      padding: const EdgeInsets.fromLTRB(40, 90, 40, 140),
                      maxZoom: 17,
                    ),
                  ),
                ),
                children: [
                  TileLayer(
                    urlTemplate:
                        'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                    userAgentPackageName: 'br.com.greenv.capture',
                  ),
                  MarkerLayer(
                    markers: [
                      for (final stretch in located)
                        Marker(
                          point: LatLng(stretch.centreLat!, stretch.centreLon!),
                          width: 30,
                          height: 30,
                          child: GestureDetector(
                            onTap: () => showStretchSheet(
                              context,
                              stretch,
                              widget.operations,
                            ),
                            child: Container(
                              decoration: BoxDecoration(
                                color: levelColour(stretch.vegetationLevel),
                                shape: BoxShape.circle,
                                border: Border.all(
                                  color: Colors.white,
                                  width: 2.5,
                                ),
                                boxShadow: const [
                                  BoxShadow(
                                    color: Color(0x33000000),
                                    blurRadius: 6,
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                  const RichAttributionWidget(
                    attributions: [TextSourceAttribution('© OpenStreetMap')],
                  ),
                ],
              ),
            ),
            Positioned(
              left: 18,
              right: 18,
              top: 14,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 10,
                ),
                decoration: cardDecoration(radius: 14),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        widget.sessionId == null
                            ? '${located.length} trecho${located.length == 1 ? '' : 's'} no mapa · toque num ponto'
                            : '${located.length} trecho${located.length == 1 ? '' : 's'} desta sessão',
                        style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                    // Without a way back, a map framed on one session is a map with no exit.
                    if (widget.sessionId != null)
                      TextButton(
                        key: const Key('clear-session-focus'),
                        onPressed: widget.onClearSession,
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          minimumSize: const Size(0, 32),
                        ),
                        child: const Text('ver todos'),
                      ),
                  ],
                ),
              ),
            ),
            Positioned(
              left: 18,
              right: 18,
              bottom: 18,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 10,
                ),
                decoration: cardDecoration(radius: 14),
                child: Wrap(
                  spacing: 14,
                  runSpacing: 6,
                  children: [
                    for (final level in const [
                      VegetationLevel.high,
                      VegetationLevel.medium,
                      VegetationLevel.low,
                      VegetationLevel.unknown,
                    ])
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            width: 10,
                            height: 10,
                            decoration: BoxDecoration(
                              color: levelColour(level),
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 5),
                          Text(
                            level.description,
                            style: const TextStyle(fontSize: 11),
                          ),
                        ],
                      ),
                  ],
                ),
              ),
            ),
          ],
        );
      },
    ),
  );
}

// ---------------------------------------------------------------------------------------------
// Ordens e equipes
// ---------------------------------------------------------------------------------------------

final class OrdersScreen extends StatelessWidget {
  const OrdersScreen({
    required this.operations,
    required this.onNavigate,
    super.key,
  });

  final OperationsGateway operations;
  final ValueChanged<MotivaPage> onNavigate;

  Future<(PageOf<ServiceOrder>, List<Team>)> _load() async {
    final orders = operations.orders();
    final teams = operations.teams();
    return (await orders, await teams);
  }

  @override
  Widget build(BuildContext context) => MainScaffold(
    page: MotivaPage.orders,
    onNavigate: onNavigate,
    body: SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SectionTitle(
            title: 'Ordens de serviço',
            action: 'Equipes',
            onTap: () => onNavigate(MotivaPage.teams),
          ),
          const SizedBox(height: 6),
          const Text(
            'Roçada aberta contra trechos que foram medidos.',
            style: TextStyle(fontSize: 13, height: 1.4, color: motivaMuted),
          ),
          const SizedBox(height: 16),
          RemoteData<(PageOf<ServiceOrder>, List<Team>)>(
            load: _load,
            emptyWhen: (data) => data.$1.items.isEmpty,
            emptyMessage:
                'Nenhuma ordem aberta ainda. Abra uma a partir de um trecho.',
            builder: (context, data, _) {
              final (page, teams) = data;
              final teamName = {for (final t in teams) t.teamId: t.shortName};
              return Column(
                children: [
                  for (final order in page.items) ...[
                    _OrderTile(order: order, teamName: teamName[order.teamId]),
                    const SizedBox(height: 8),
                  ],
                ],
              );
            },
          ),
        ],
      ),
    ),
  );
}

final class _OrderTile extends StatelessWidget {
  const _OrderTile({required this.order, this.teamName});

  final ServiceOrder order;
  final String? teamName;

  Color get _statusColour => switch (order.status) {
    OrderStatus.pending => greenvAmber,
    OrderStatus.inProgress => motivaPurpleDark,
    OrderStatus.completed => motivaGreen,
    OrderStatus.cancelled => motivaMuted,
  };

  @override
  Widget build(BuildContext context) {
    final level = VegetationLevel.of(order.vegetationLevel);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: cardDecoration(radius: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  order.reference,
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: _statusColour.withValues(alpha: 0.14),
                  borderRadius: BorderRadius.circular(99),
                ),
                child: Text(
                  order.status.label,
                  style: TextStyle(
                    fontSize: 10,
                    fontWeight: FontWeight.w800,
                    color: _statusColour,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            [
              formatDate(order.createdAt),
              order.priority.label,
              ?teamName,
              ?order.equipment,
            ].join(' · '),
            style: const TextStyle(fontSize: 11.5, color: motivaMuted),
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Text(
                squareMetres(order.areaSquareMetres),
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                level.label,
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                  color: levelColour(level),
                ),
              ),
              const Spacer(),
              Text(
                '${order.targetCount} trecho${order.targetCount == 1 ? '' : 's'}',
                style: const TextStyle(fontSize: 11, color: motivaMuted),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

final class TeamsScreen extends StatelessWidget {
  const TeamsScreen({
    required this.operations,
    required this.onNavigate,
    super.key,
  });

  final OperationsGateway operations;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => MainScaffold(
    page: MotivaPage.teams,
    onNavigate: onNavigate,
    body: SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SectionTitle(
            title: 'Equipes',
            action: 'Ordens',
            onTap: () => onNavigate(MotivaPage.orders),
          ),
          const SizedBox(height: 6),
          const Text(
            'Times de campo e as ordens que cada um carrega.',
            style: TextStyle(fontSize: 13, height: 1.4, color: motivaMuted),
          ),
          const SizedBox(height: 16),
          RemoteData<List<Team>>(
            load: operations.teams,
            emptyWhen: (teams) => teams.isEmpty,
            emptyMessage: 'Nenhuma equipe cadastrada.',
            builder: (context, teams, _) => Column(
              children: [
                for (final team in teams) ...[
                  _TeamTile(team: team),
                  const SizedBox(height: 8),
                ],
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

final class _TeamTile extends StatelessWidget {
  const _TeamTile({required this.team});

  final Team team;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
    decoration: cardDecoration(radius: 16),
    child: Row(
      children: [
        CircleAvatar(
          radius: 20,
          backgroundColor: greenvForest,
          child: Text(
            team.initials,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 12,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                team.name,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w800,
                ),
              ),
              Text(
                [?team.region, team.status.label].join(' · '),
                style: const TextStyle(fontSize: 11, color: motivaMuted),
              ),
              const SizedBox(height: 4),
              Text(
                '${team.pendingOrders} pendentes · ${team.inProgressOrders} em curso · '
                '${team.completedOrders} concluídas',
                style: const TextStyle(fontSize: 10.5, color: motivaMuted),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}
