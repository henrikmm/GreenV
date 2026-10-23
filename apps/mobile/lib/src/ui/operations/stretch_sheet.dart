import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/operations/facts_table.dart';
import 'package:greenv_capture/src/ui/operations/formatting.dart';
import 'package:greenv_capture/src/ui/operations/order_form.dart';
import 'package:greenv_capture/src/ui/shell.dart';

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
  builder: (sheetContext) => StretchSheet(stretch: stretch, operations: operations),
);

/// One stretch: its photographs, what each of them measured, the facts, and the button that
/// turns it into work.
///
/// The photographs are the point. The dashboard opens a stretch into a strip of frames and a
/// panel saying what the chosen one contributed, and a field worker standing at the verge has
/// more use for that than anyone at a desk: the picture is how they know the row is the stretch
/// in front of them. This is that, laid down a phone instead of across a screen.
final class StretchSheet extends StatefulWidget {
  const StretchSheet({required this.stretch, required this.operations, super.key});

  final MeasuredStretch stretch;
  final OperationsGateway operations;

  @override
  State<StretchSheet> createState() => _StretchSheetState();
}

/// The photographs of this stretch, separated from the rest of the segment's.
///
/// The route answers with the uploaded segment's frames, and each says which window it belongs
/// to: a window's packet carries the positions of only the frames its reconstruction used, and
/// the API passes that through as `windowIndex`. If none falls in this window — a segment
/// measured before the field existed, say — the segment's own come back, because an empty strip
/// would hide photographs that do exist.
List<SampledFrame> _framesOfWindow(List<SampledFrame> frames, int? windowIndex) {
  if (windowIndex == null) return frames;
  final mine = frames.where((frame) => frame.windowIndex == windowIndex).toList();
  return mine.isNotEmpty ? mine : frames;
}

final class _StretchSheetState extends State<StretchSheet> {
  late final Future<List<SampledFrame>> _frames = widget.operations
      .frames(widget.stretch.sessionId, widget.stretch.segmentIndex)
      .then((frames) => _framesOfWindow(frames, widget.stretch.windowIndex));
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
                  padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
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
            Text(place.detail, style: const TextStyle(fontSize: 12.5, color: motivaMuted)),
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
                      child: CircularProgressIndicator(strokeWidth: 2.2, color: greenvForest),
                    ),
                  ),
                );
              }
              // A segment with no manifest answers 409, which is an answer and not a failure:
              // the rest of the sheet is still worth reading.
              final frames = snapshot.hasError ? const <SampledFrame>[] : snapshot.data!;
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
          FactsTable(
            rows: [
              ('Capturado em', formatDateTime(stretch.capturedAt)),
              if (stretch.measuredAt != null) ('Medido em', formatDateTime(stretch.measuredAt!)),
              ('Altura máxima', centimetres(stretch.extent95MaxM)),
              (
                'Células',
                stretch.cellsMeasured == null
                    ? '-'
                    : '${stretch.cellsMeasured} medidas · ${stretch.cellsAbstained ?? 0} sem evidência',
              ),
              if (stretch.coverage != null) ('Cobertura', '${(stretch.coverage! * 100).round()}%'),
              ('GPS', stretch.locationQuality ?? 'sem posição'),
              (
                'Trecho',
                stretch.windowIndex == null
                    ? 'segmento ${stretch.segmentIndex} inteiro'
                    : '${stretch.range ?? 'trecho ${stretch.windowIndex}'}'
                          ' do segmento ${stretch.segmentIndex}',
              ),
              ('Sessão', shortId(stretch.sessionId)),
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
  late final Future<Uint8List> _bytes = widget.operations.frameImage(widget.frame.imageUrl);

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
                  child: CircularProgressIndicator(strokeWidth: 2.2, color: greenvForest),
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
  late final Future<Uint8List> _bytes = widget.operations.frameImage(widget.frame.imageUrl);

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: widget.onTap,
    child: Container(
      width: 52,
      height: 58,
      decoration: BoxDecoration(
        color: motivaCanvas,
        borderRadius: BorderRadius.circular(9),
        border: Border.all(color: widget.active ? greenvForest : Colors.transparent, width: 2.2),
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
          FactsTable(
            rows: [
              ('Células votadas', '${readings.cellsVoted}'),
              ('Altura mediana', centimetres(readings.extent95MedianM)),
              ('Maior altura', centimetres(readings.extent95MaxM)),
              if (readings.largestDisagreementM != null)
                ('Maior discordância', centimetres(readings.largestDisagreementM)),
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
              style: TextStyle(fontSize: 10.5, color: motivaMuted, height: 1.45),
            ),
          ),
        ],
      );
    },
  );
}
