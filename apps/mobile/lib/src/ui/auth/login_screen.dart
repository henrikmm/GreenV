import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/ui/auth/auth_layout.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class LoginScreen extends StatefulWidget {
  const LoginScreen({required this.onNavigate, required this.authenticator, super.key});

  final ValueChanged<MotivaPage> onNavigate;
  final SessionAuthenticator authenticator;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();

  bool _busy = false;
  bool _obscured = true;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      await widget.authenticator.signIn(_email.text, _password.text);
      if (mounted) {
        widget.onNavigate(MotivaPage.home);
      }
    } on AuthenticationFailure catch (failure) {
      if (!mounted) {
        return;
      }
      setState(() {
        _busy = false;
        // The API never says which half was wrong, so neither does this.
        _error = switch (failure.reason) {
          AuthenticationFailureReason.invalidCredentials => 'E-mail ou senha inválidos.',
          AuthenticationFailureReason.providerUnavailable =>
            'O serviço de autenticação está indisponível. Tente novamente em instantes.',
          AuthenticationFailureReason.unreachable =>
            'Sem conexão com o servidor. Verifique a rede e tente novamente.',
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) => AuthPattern(
    child: AuthCard(
      children: [
        Text('Bem-vindo', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 7),
        const Text(
          'Entre para iniciar e acompanhar as coletas em campo.',
          textAlign: TextAlign.center,
          style: TextStyle(color: motivaMuted, fontSize: 13, height: 1.4),
        ),
        const SizedBox(height: 28),
        if (_error != null) ...[
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
            decoration: BoxDecoration(
              color: const Color(0xFFFDEAEA),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFFF0C4C4)),
            ),
            child: Text(
              _error!,
              key: const Key('login-error'),
              style: const TextStyle(color: Color(0xFF8C2020), fontSize: 12.5, height: 1.35),
            ),
          ),
          const SizedBox(height: 14),
        ],
        TextField(
          key: const Key('email-field'),
          controller: _email,
          enabled: !_busy,
          keyboardType: TextInputType.emailAddress,
          autocorrect: false,
          textInputAction: TextInputAction.next,
          decoration: const InputDecoration(
            labelText: 'E-mail',
            hintText: 'nome@empresa.com.br',
            prefixIcon: Icon(Icons.mail_outline_rounded, size: 20),
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          key: const Key('password-field'),
          controller: _password,
          enabled: !_busy,
          obscureText: _obscured,
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _submit(),
          decoration: InputDecoration(
            labelText: 'Senha',
            prefixIcon: const Icon(Icons.lock_outline_rounded, size: 20),
            suffixIcon: IconButton(
              icon: Icon(
                _obscured ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                size: 20,
              ),
              onPressed: () => setState(() => _obscured = !_obscured),
            ),
          ),
        ),
        const SizedBox(height: 24),
        FilledButton(
          key: const Key('login-button'),
          // Roxo, como no aplicativo de campo. O verde do tema continua valendo em todo o resto;
          // trocá-lo no tema repintaria telas que não têm nada a ver com esta.
          style: FilledButton.styleFrom(backgroundColor: motivaPurple),
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                )
              : const Text('Entrar'),
        ),
      ],
    ),
  );
}
