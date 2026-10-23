import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/operations/formatting.dart';
import 'package:greenv_capture/src/ui/operations/remote_data.dart';
import 'package:greenv_capture/src/ui/operations/stretch_sheet.dart';
import 'package:greenv_capture/src/ui/shell.dart';
import 'package:latlong2/latlong.dart';

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
        final points = [for (final s in located) LatLng(s.centreLat!, s.centreLon!)];
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
                    urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
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
                            onTap: () => showStretchSheet(context, stretch, widget.operations),
                            child: Container(
                              decoration: BoxDecoration(
                                color: levelColour(stretch.vegetationLevel),
                                shape: BoxShape.circle,
                                border: Border.all(color: Colors.white, width: 2.5),
                                boxShadow: const [
                                  BoxShadow(color: Color(0x33000000), blurRadius: 6),
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
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                decoration: cardDecoration(radius: 14),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        widget.sessionId == null
                            ? '${located.length} trecho${located.length == 1 ? '' : 's'} no mapa · toque num ponto'
                            : '${located.length} trecho${located.length == 1 ? '' : 's'} desta sessão',
                        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800),
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
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
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
                          Text(level.description, style: const TextStyle(fontSize: 11)),
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
