import 'package:flutter/material.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/operations/remote_data.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class TeamsScreen extends StatelessWidget {
  const TeamsScreen({required this.operations, required this.onNavigate, super.key});

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
                for (final team in teams) ...[_TeamTile(team: team), const SizedBox(height: 8)],
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
            style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w800),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(team.name, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
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
