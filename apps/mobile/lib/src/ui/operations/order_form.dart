import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/http_operations_gateway.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/ui/operations/facts_table.dart';
import 'package:greenv_capture/src/ui/operations/formatting.dart';
import 'package:greenv_capture/src/ui/operations/remote_data.dart';

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
  builder: (sheetContext) => OrderForm(targets: targets, operations: operations),
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

  VegetationLevel get _worst =>
      widget.targets.map((t) => t.vegetationLevel).reduce((a, b) => a.number >= b.number ? a : b);

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
      ScaffoldMessenger.maybeOf(context)
          ?.showSnackBar(SnackBar(content: Text('Ordem ${order.reference} aberta.')));
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
      padding: EdgeInsets.fromLTRB(20, 14, 20, 20 + MediaQuery.viewInsetsOf(context).bottom),
      child: SingleChildScrollView(
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              widget.targets.length > 1 ? 'Nova OS combinada' : 'Nova ordem de serviço',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 12),
            FactsTable(
              rows: [
                ('Trechos', '${widget.targets.length}'),
                ('Nível', _worst.label),
                ('Maior altura p95', centimetres(tallest)),
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
                  const DropdownMenuItem<String?>(child: Text('Sem equipe atribuída')),
                  for (final team in teams)
                    DropdownMenuItem<String?>(value: team.teamId, child: Text(team.name)),
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
              label: Text(_scheduledFor == null ? 'Escolher data' : formatDate(_scheduledFor!)),
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
                style: const TextStyle(color: Color(0xFFC72F2F), fontSize: 12.5),
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
    child: Text(text, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800)),
  );
}
