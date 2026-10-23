import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/operations/formatting.dart';
import 'package:greenv_capture/src/ui/operations/remote_data.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class OrdersScreen extends StatelessWidget {
  const OrdersScreen({required this.operations, required this.onNavigate, super.key});

  final OperationsGateway operations;
  final ValueChanged<MotivaPage> onNavigate;

  Future<(PageOf<ServiceOrder>, List<Team>)> _load() =>
      both(operations.orders(), operations.teams());

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
            emptyMessage: 'Nenhuma ordem aberta ainda. Abra uma a partir de um trecho.',
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
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w900),
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
                  style: TextStyle(fontSize: 10, fontWeight: FontWeight.w800, color: _statusColour),
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
                style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w800),
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
