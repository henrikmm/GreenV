import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/operations/formatting.dart';
import 'package:greenv_capture/src/ui/operations/remote_data.dart';
import 'package:greenv_capture/src/ui/operations/stretch_sheet.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class StretchesScreen extends StatefulWidget {
  const StretchesScreen({required this.operations, required this.onNavigate, super.key});

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
          Text('Trechos medidos', style: Theme.of(context).textTheme.headlineSmall),
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
                    onTap: () => showStretchSheet(context, stretch, widget.operations),
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
                          style: const TextStyle(fontSize: 11.5, color: motivaMuted),
                        ),
                        const Spacer(),
                        TextButton(
                          onPressed: _offset == 0 ? null : () => _page(_offset - pageSize),
                          child: const Text('Anterior'),
                        ),
                        TextButton(
                          onPressed: page.hasMore ? () => _page(_offset + pageSize) : null,
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
  const StretchTile({required this.stretch, required this.onTap, this.rank, super.key});

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
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
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
                    // The stretch first, when there is one. Nine windows of a segment sit on the
                    // same street and carry the same date, so without it the rows read as nine
                    // copies of one place at nine different heights.
                    [
                      stretch.stretchLabel,
                      formatDate(stretch.capturedAt),
                      stretch.locationQuality ?? 'sem GPS',
                    ].whereType<String>().join(' · '),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
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
                  padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
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
