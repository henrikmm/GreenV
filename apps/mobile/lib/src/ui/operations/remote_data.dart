import 'dart:async';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/http_operations_gateway.dart';
import 'package:greenv_capture/src/ui/shell.dart';

/// Two requests at once, failing with the first one's own error.
///
/// Awaiting two futures in turn leaves the second unlistened while the first throws. Offline both
/// fail, so every load of the home and orders screens raised an unhandled error behind the card
/// that explained it. `Future.wait` listens to both and rethrows the first error as it was, which
/// keeps an [OperationsFailure]'s words for the screen.
Future<(A, B)> both<A, B>(Future<A> first, Future<B> second) async {
  final results = await Future.wait<Object?>([first, second]);
  return (results[0] as A, results[1] as B);
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
  final Widget Function(BuildContext context, T data, VoidCallback reload) builder;
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
          child: CircularProgressIndicator(strokeWidth: 2.5, color: greenvForest),
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
      OperationsFailure(unauthorized: true) => 'Sua sessão terminou. Entre de novo.',
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
            style: TextStyle(fontWeight: FontWeight.w800, color: Color(0xFFC72F2F)),
          ),
          const SizedBox(height: 4),
          Text(detail, style: const TextStyle(fontSize: 12, color: motivaInk)),
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: retry,
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(40)),
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
