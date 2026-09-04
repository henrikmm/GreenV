import 'dart:async';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';

const motivaPurple = Color(0xFF6546D7);
const motivaPurpleDark = Color(0xFF4E34B5);
const motivaLilac = Color(0xFFF0ECFF);
const motivaGreen = Color(0xFF0C6B4F);
const greenvForest = Color(0xFF123E31);
const greenvLeaf = Color(0xFF2D8A62);
const greenvMint = Color(0xFFE7F3EC);
const greenvAmber = Color(0xFFE59A33);
const motivaInk = Color(0xFF17231E);
const motivaMuted = Color(0xFF66736C);
const motivaCanvas = Color(0xFFF3F6F2);
const motivaLine = Color(0xFFDCE5DE);

enum MotivaPage {
  splash,
  login,
  forgotEmail,
  forgotCode,
  home,
  upload,
  network,
  map,
}

final class CaptureApp extends StatelessWidget {
  const CaptureApp({required this.dependencies, this.initialPage, super.key});

  final AppDependencies dependencies;
  final MotivaPage? initialPage;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Motiva GreenV',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: greenvForest,
        primary: greenvForest,
        secondary: motivaPurple,
        surface: Colors.white,
      ),
      scaffoldBackgroundColor: const Color(0xFF18231E),
      textTheme: const TextTheme(
        headlineSmall: TextStyle(
          color: motivaInk,
          fontSize: 24,
          height: 1.15,
          fontWeight: FontWeight.w800,
          letterSpacing: -0.5,
        ),
        titleLarge: TextStyle(
          color: motivaInk,
          fontSize: 18,
          fontWeight: FontWeight.w800,
          letterSpacing: -0.2,
        ),
        titleMedium: TextStyle(color: motivaInk, fontWeight: FontWeight.w700),
        bodyLarge: TextStyle(color: motivaInk, fontSize: 16, height: 1.45),
        bodyMedium: TextStyle(color: motivaInk, fontSize: 14, height: 1.4),
        bodySmall: TextStyle(color: motivaMuted, fontSize: 12, height: 1.35),
        labelLarge: TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          backgroundColor: greenvForest,
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          foregroundColor: greenvForest,
          side: const BorderSide(color: motivaLine),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: motivaPurpleDark,
          minimumSize: const Size(44, 44),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: Colors.white,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 16,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: motivaLine),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: greenvForest, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: Color(0xFFC43E4D)),
        ),
        hintStyle: const TextStyle(color: motivaMuted, fontSize: 14),
      ),
    ),
    home: _MotivaFlow(
      controller: dependencies.capture,
      authenticator: dependencies.authenticator,
      // A session restored from the last run skips the login screen; without one there is nothing
      // to show, because every screen behind it needs a token.
      initialPage: initialPage ??
          (dependencies.authenticator.signedIn.value ? MotivaPage.home : _pageFromUri()),
    ),
  );

  static MotivaPage _pageFromUri() {
    final requested = Uri.base.queryParameters['screen'];
    return MotivaPage.values.firstWhere(
      (page) => page.name == requested,
      orElse: () => MotivaPage.login,
    );
  }
}

final class _MotivaFlow extends StatefulWidget {
  const _MotivaFlow({
    required this.controller,
    required this.authenticator,
    required this.initialPage,
  });

  final CaptureCoordinator controller;
  final SessionAuthenticator authenticator;
  final MotivaPage initialPage;

  @override
  State<_MotivaFlow> createState() => _MotivaFlowState();
}

final class _MotivaFlowState extends State<_MotivaFlow>
    with WidgetsBindingObserver {
  late MotivaPage _page = widget.initialPage;
  Timer? _syncTick;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _syncTick = Timer.periodic(
      const Duration(seconds: 5),
      (_) => widget.controller.syncBacklog(),
    );
    unawaited(widget.controller.syncBacklog());
    widget.authenticator.signedIn.addListener(_onSessionChanged);
  }

  /// A refresh that fails - an expired session, or one revoked because its refresh token was
  /// replayed - lands here, so the person is asked to sign in again instead of being left on a
  /// screen whose uploads all return 401.
  void _onSessionChanged() {
    if (!widget.authenticator.signedIn.value && mounted && _page != MotivaPage.login) {
      _go(MotivaPage.login);
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      unawaited(widget.controller.stopForBackground());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.authenticator.signedIn.removeListener(_onSessionChanged);
    _syncTick?.cancel();
    super.dispose();
  }

  void _go(MotivaPage page) => setState(() => _page = page);

  @override
  Widget build(BuildContext context) {
    final screen = switch (_page) {
      MotivaPage.splash => const _SplashScreen(),
      MotivaPage.login => _LoginScreen(
        onNavigate: _go,
        authenticator: widget.authenticator,
      ),
      MotivaPage.forgotEmail => _ForgotEmailScreen(onNavigate: _go),
      MotivaPage.forgotCode => _ForgotCodeScreen(onNavigate: _go),
      MotivaPage.home => _HomeScreen(
        onNavigate: _go,
        onSignOut: () async {
          await widget.authenticator.signOut();
          if (mounted) {
            _go(MotivaPage.login);
          }
        },
      ),
      MotivaPage.upload => CaptureScreen(
        controller: widget.controller,
        onNavigate: _go,
      ),
      MotivaPage.network => _NetworkScreen(onNavigate: _go),
      MotivaPage.map => _MapScreen(onNavigate: _go),
    };
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 180),
      child: KeyedSubtree(key: ValueKey(_page), child: screen),
    );
  }
}

final class _PhoneFrame extends StatelessWidget {
  const _PhoneFrame({required this.child, this.background = motivaCanvas});

  final Widget child;
  final Color background;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 430),
        child: Semantics(
          label: 'mobile-app-frame',
          container: true,
          explicitChildNodes: true,
          child: ColoredBox(
            color: background,
            child: SafeArea(bottom: false, child: child),
          ),
        ),
      ),
    ),
  );
}

final class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) => _PhoneFrame(
    background: greenvForest,
    child: Stack(
      fit: StackFit.expand,
      children: [
        Positioned(
          top: -110,
          right: -95,
          child: Container(
            width: 280,
            height: 280,
            decoration: const BoxDecoration(
              color: Color(0x286546D7),
              shape: BoxShape.circle,
            ),
          ),
        ),
        Positioned(
          bottom: -150,
          left: -100,
          child: Container(
            width: 330,
            height: 330,
            decoration: const BoxDecoration(
              color: Color(0x1F58B584),
              shape: BoxShape.circle,
            ),
          ),
        ),
        const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _MotivaLogo(color: Colors.white, large: true),
              SizedBox(height: 22),
              SizedBox(width: 44, child: Divider(color: Color(0x66FFFFFF))),
              SizedBox(height: 16),
              _GreenVLogo(fontSize: 28, light: true),
              SizedBox(height: 12),
              Text(
                'Tecnologia para cuidar de cada trecho',
                style: TextStyle(
                  color: Color(0xBFFFFFFF),
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
        const Positioned(
          left: 0,
          right: 0,
          bottom: 28,
          child: Text(
            'OPERAÇÃO DE CAMPO',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: Color(0x99FFFFFF),
              fontSize: 10,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.8,
            ),
          ),
        ),
      ],
    ),
  );
}

final class _AuthPattern extends StatelessWidget {
  const _AuthPattern({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => _PhoneFrame(
    background: motivaCanvas,
    child: Stack(
      fit: StackFit.expand,
      children: [
        CustomPaint(painter: _PatternPainter()),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 32, 20, 24),
          child: child,
        ),
      ],
    ),
  );
}

final class _AuthCard extends StatelessWidget {
  const _AuthCard({required this.children, this.footer});

  final List<Widget> children;
  final Widget? footer;

  @override
  Widget build(BuildContext context) => Container(
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(28),
      border: Border.all(color: const Color(0xAFFFFFFF)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x180B2E23),
          blurRadius: 32,
          offset: Offset(0, 14),
        ),
      ],
    ),
    padding: const EdgeInsets.fromLTRB(26, 30, 26, 18),
    child: Column(
      children: [
        const Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _MotivaLogo(color: motivaPurple),
            SizedBox(height: 34, child: VerticalDivider(width: 28)),
            _GreenVLogo(fontSize: 22),
          ],
        ),
        // Centred while it fits, scrollable when it does not. A fixed-height column here overflows
        // as soon as anything is added - an error message, or a short screen on a small phone.
        Expanded(
          child: Center(
            child: SingleChildScrollView(
              child: Column(mainAxisSize: MainAxisSize.min, children: children),
            ),
          ),
        ),
        footer ?? const SizedBox.shrink(),
      ],
    ),
  );
}

final class _LoginScreen extends StatefulWidget {
  const _LoginScreen({required this.onNavigate, required this.authenticator});

  final ValueChanged<MotivaPage> onNavigate;
  final SessionAuthenticator authenticator;

  @override
  State<_LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<_LoginScreen> {
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
  Widget build(BuildContext context) => _AuthPattern(
    child: _AuthCard(
      footer: TextButton.icon(
        onPressed: () {},
        icon: const Icon(Icons.newspaper_outlined, size: 17),
        label: const Text('Notícias da operação'),
      ),
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
        Align(
          alignment: Alignment.centerRight,
          child: TextButton(
            onPressed: _busy ? null : () => widget.onNavigate(MotivaPage.forgotEmail),
            child: const Text(
              'Esqueceu a senha?',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
            ),
          ),
        ),
        const SizedBox(height: 18),
        FilledButton(
          key: const Key('login-button'),
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

final class _ForgotEmailScreen extends StatelessWidget {
  const _ForgotEmailScreen({required this.onNavigate});

  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => _AuthPattern(
    child: _AuthCard(
      children: [
        Text(
          'Recuperar acesso',
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 8),
        const Text(
          'Informe seu e-mail corporativo para receber o código de acesso.',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 13, height: 1.4, color: motivaMuted),
        ),
        const SizedBox(height: 28),
        const TextField(
          keyboardType: TextInputType.emailAddress,
          decoration: InputDecoration(
            labelText: 'E-mail',
            hintText: 'nome@empresa.com.br',
            prefixIcon: Icon(Icons.mail_outline_rounded, size: 20),
          ),
        ),
        const SizedBox(height: 32),
        FilledButton(
          onPressed: () => onNavigate(MotivaPage.forgotCode),
          child: const Text('Enviar código'),
        ),
        const SizedBox(height: 10),
        OutlinedButton(
          onPressed: () => onNavigate(MotivaPage.login),
          child: const Text('Voltar'),
        ),
      ],
    ),
  );
}

final class _ForgotCodeScreen extends StatelessWidget {
  const _ForgotCodeScreen({required this.onNavigate});

  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => _AuthPattern(
    child: _AuthCard(
      children: [
        Text(
          'Digite o código',
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 8),
        const Text(
          'Enviamos seis dígitos para o seu e-mail corporativo.',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 13, height: 1.4, color: motivaMuted),
        ),
        const SizedBox(height: 28),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: List.generate(
            6,
            (index) => Container(
              width: 40,
              height: 54,
              margin: const EdgeInsets.symmetric(horizontal: 2),
              decoration: BoxDecoration(
                color: Colors.white,
                border: Border.all(color: motivaLine, width: 1.5),
                borderRadius: BorderRadius.circular(12),
              ),
            ),
          ),
        ),
        const SizedBox(height: 38),
        FilledButton(
          onPressed: () => onNavigate(MotivaPage.home),
          child: const Text('Confirmar e entrar'),
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: () => onNavigate(MotivaPage.forgotEmail),
          child: const Text('Reenviar código'),
        ),
      ],
    ),
  );
}

final class _AppHeader extends StatelessWidget {
  const _AppHeader();

  @override
  Widget build(BuildContext context) => Container(
    height: 68,
    padding: const EdgeInsets.fromLTRB(20, 10, 14, 8),
    decoration: const BoxDecoration(
      color: motivaCanvas,
      border: Border(bottom: BorderSide(color: motivaLine)),
    ),
    child: Row(
      children: [
        _GreenVLogo(fontSize: 26),
        SizedBox(width: 10),
        _EnvironmentBadge(),
        Spacer(),
        _HeaderAction(
          icon: Icons.notifications_none_rounded,
          semanticsLabel: 'Notificações',
        ),
        SizedBox(width: 8),
        CircleAvatar(
          radius: 18,
          backgroundColor: greenvForest,
          child: Text(
            'OP',
            style: TextStyle(
              color: Colors.white,
              fontSize: 11,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
      ],
    ),
  );
}

final class _EnvironmentBadge extends StatelessWidget {
  const _EnvironmentBadge();

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
    decoration: BoxDecoration(
      color: greenvMint,
      borderRadius: BorderRadius.circular(99),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.eco_outlined, size: 13, color: motivaGreen),
        SizedBox(width: 4),
        Text(
          'CAMPO',
          style: TextStyle(
            color: motivaGreen,
            fontSize: 9,
            fontWeight: FontWeight.w900,
            letterSpacing: 0.8,
          ),
        ),
      ],
    ),
  );
}

final class _HeaderAction extends StatelessWidget {
  const _HeaderAction({required this.icon, required this.semanticsLabel});

  final IconData icon;
  final String semanticsLabel;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    label: semanticsLabel,
    child: Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: Colors.white,
        shape: BoxShape.circle,
        border: Border.all(color: motivaLine),
      ),
      child: Icon(icon, size: 20, color: motivaInk),
    ),
  );
}

final class _MainScaffold extends StatelessWidget {
  const _MainScaffold({
    required this.page,
    required this.body,
    required this.onNavigate,
  });

  final MotivaPage page;
  final Widget body;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => _PhoneFrame(
    child: Column(
      children: [
        const _AppHeader(),
        Expanded(child: body),
        _BottomNavigation(selected: page, onNavigate: onNavigate),
      ],
    ),
  );
}

final class _HomeScreen extends StatelessWidget {
  const _HomeScreen({required this.onNavigate, required this.onSignOut});

  final ValueChanged<MotivaPage> onNavigate;
  final Future<void> Function() onSignOut;

  @override
  Widget build(BuildContext context) => _MainScaffold(
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
            'Acompanhe a malha e registre um novo trecho.',
            style: TextStyle(fontSize: 13, height: 1.4, color: motivaMuted),
          ),
          const SizedBox(height: 20),
          _HomeCaptureCard(onTap: () => onNavigate(MotivaPage.upload)),
          const SizedBox(height: 24),
          const _SectionTitle(title: 'Resumo da operação'),
          const SizedBox(height: 12),
          const _KilometerCard(),
          const SizedBox(height: 12),
          const Row(
            children: [
              Expanded(
                child: _MetricCard(
                  label: 'ALERTAS',
                  value: '12',
                  detail: 'Críticos',
                  danger: true,
                ),
              ),
              SizedBox(width: 10),
              Expanded(
                child: _MetricCard(
                  label: 'EQUIPES',
                  value: '05',
                  detail: 'Em campo',
                  green: true,
                ),
              ),
            ],
          ),
          const SizedBox(height: 26),
          _SectionTitle(
            title: 'Malha monitorada',
            action: 'Ver mapa',
            onTap: () => onNavigate(MotivaPage.network),
          ),
          const SizedBox(height: 12),
          GestureDetector(
            key: const Key('network-preview'),
            onTap: () => onNavigate(MotivaPage.network),
            child: Stack(
              children: [
                const _RouteMap(height: 200, compact: true),
                Positioned(
                  left: 12,
                  top: 12,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(99),
                      boxShadow: const [
                        BoxShadow(color: Color(0x14000000), blurRadius: 8),
                      ],
                    ),
                    child: const Row(
                      children: [
                        Icon(
                          Icons.route_rounded,
                          size: 15,
                          color: greenvForest,
                        ),
                        SizedBox(width: 6),
                        Text(
                          '291 trechos acompanhados',
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 26),
          const _SectionTitle(title: 'Atividade recente'),
          const SizedBox(height: 12),
          const _RecentUpload(
            name: 'BR-101 · sentido norte',
            status: 'Concluído',
            complete: true,
          ),
          const SizedBox(height: 10),
          const _RecentUpload(name: 'BR-116 · km 214', status: 'Processando'),
        ],
      ),
    ),
  );
}

final class _HomeCaptureCard extends StatelessWidget {
  const _HomeCaptureCard({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Container(
    decoration: BoxDecoration(
      gradient: const LinearGradient(
        colors: [greenvForest, Color(0xFF1D604A)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ),
      borderRadius: BorderRadius.circular(24),
      boxShadow: const [
        BoxShadow(
          color: Color(0x26123E31),
          blurRadius: 24,
          offset: Offset(0, 12),
        ),
      ],
    ),
    child: Stack(
      children: [
        const Positioned.fill(
          child: ClipRRect(
            borderRadius: BorderRadius.all(Radius.circular(24)),
            child: CustomPaint(painter: _FieldRoutePainter()),
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
                decoration: BoxDecoration(
                  color: const Color(0x24FFFFFF),
                  borderRadius: BorderRadius.circular(99),
                ),
                child: const Text(
                  'PRONTO PARA COLETAR',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 9,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 1,
                  ),
                ),
              ),
              const SizedBox(height: 32),
              const Text(
                'Registre um novo\ntrecho da rodovia',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 23,
                  height: 1.12,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.4,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'Vídeo, localização e sensores em uma única coleta.',
                style: TextStyle(
                  color: Color(0xCFFFFFFF),
                  fontSize: 12,
                  height: 1.4,
                ),
              ),
              const SizedBox(height: 20),
              SizedBox(
                width: 178,
                child: FilledButton.icon(
                  onPressed: onTap,
                  style: FilledButton.styleFrom(
                    backgroundColor: Colors.white,
                    foregroundColor: greenvForest,
                  ),
                  icon: const Icon(Icons.videocam_rounded, size: 19),
                  label: const Text('Iniciar coleta'),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

final class _KilometerCard extends StatelessWidget {
  const _KilometerCard();

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: _cardDecoration(radius: 18),
    child: Row(
      children: [
        Container(
          width: 46,
          height: 46,
          decoration: BoxDecoration(
            color: greenvMint,
            borderRadius: BorderRadius.all(Radius.circular(14)),
          ),
          child: Icon(Icons.route_rounded, size: 23, color: motivaGreen),
        ),
        const SizedBox(width: 14),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'QUILÔMETROS MONITORADOS',
                style: TextStyle(
                  fontSize: 10,
                  letterSpacing: 0.7,
                  fontWeight: FontWeight.w800,
                  color: motivaMuted,
                ),
              ),
              SizedBox(height: 3),
              Text.rich(
                TextSpan(
                  children: [
                    TextSpan(
                      text: '1.240',
                      style: TextStyle(
                        fontSize: 25,
                        fontWeight: FontWeight.w900,
                        color: greenvForest,
                      ),
                    ),
                    TextSpan(
                      text: ' km',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: motivaMuted,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const Icon(Icons.arrow_upward_rounded, size: 18, color: greenvLeaf),
        const SizedBox(width: 3),
        const Text(
          '12%',
          style: TextStyle(
            color: greenvLeaf,
            fontSize: 12,
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    ),
  );
}

final class _MetricCard extends StatelessWidget {
  const _MetricCard({
    required this.label,
    required this.value,
    required this.detail,
    this.danger = false,
    this.green = false,
  });

  final String label;
  final String value;
  final String detail;
  final bool danger;
  final bool green;

  @override
  Widget build(BuildContext context) {
    final background = danger
        ? const Color(0xFFFFECE9)
        : green
        ? greenvMint
        : Colors.white;
    final foreground = danger
        ? const Color(0xFFC72F2F)
        : green
        ? motivaGreen
        : motivaInk;
    return Container(
      height: 108,
      padding: const EdgeInsets.all(15),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(
          color: danger
              ? const Color(0xFFFFD5CF)
              : green
              ? const Color(0xFFCDE7D8)
              : motivaLine,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                label,
                style: TextStyle(
                  fontSize: 10,
                  letterSpacing: 0.6,
                  fontWeight: FontWeight.w800,
                  color: foreground,
                ),
              ),
              const Spacer(),
              Icon(
                danger ? Icons.warning_rounded : Icons.groups_outlined,
                color: foreground,
                size: 20,
              ),
            ],
          ),
          const Spacer(),
          Text(
            value,
            style: TextStyle(
              fontSize: 23,
              fontWeight: FontWeight.w900,
              color: foreground,
            ),
          ),
          Text(detail, style: TextStyle(fontSize: 11, color: foreground)),
        ],
      ),
    );
  }
}

final class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.title, this.action, this.onTap});

  final String title;
  final String? action;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: Row(
      children: [
        Expanded(
          child: Text(
            title,
            style: const TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w800,
              letterSpacing: -0.2,
            ),
          ),
        ),
        if (action != null) ...[
          Text(
            action!,
            style: const TextStyle(
              color: motivaPurpleDark,
              fontSize: 12,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(width: 2),
          const Icon(
            Icons.chevron_right_rounded,
            size: 18,
            color: motivaPurpleDark,
          ),
        ],
      ],
    ),
  );
}

final class _RecentUpload extends StatelessWidget {
  const _RecentUpload({
    required this.name,
    required this.status,
    this.complete = false,
  });

  final String name;
  final String status;
  final bool complete;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
    decoration: _cardDecoration(radius: 16),
    child: Row(
      children: [
        Container(
          width: 42,
          height: 42,
          decoration: BoxDecoration(
            color: complete ? greenvMint : motivaLilac,
            borderRadius: BorderRadius.circular(13),
          ),
          child: Icon(
            complete ? Icons.check_circle_outline_rounded : Icons.sync_rounded,
            color: complete ? motivaGreen : motivaPurple,
            size: 20,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            name,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800),
          ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          decoration: BoxDecoration(
            color: complete ? const Color(0xFFD7F0DC) : const Color(0xFFF0EDF1),
            borderRadius: BorderRadius.circular(99),
          ),
          child: Text(
            status,
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w800,
              color: complete ? motivaGreen : motivaPurpleDark,
            ),
          ),
        ),
      ],
    ),
  );
}

final class CaptureScreen extends StatefulWidget {
  const CaptureScreen({
    required this.controller,
    required this.onNavigate,
    super.key,
  });

  final CaptureCoordinator controller;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

final class _CaptureScreenState extends State<CaptureScreen> {
  Timer? _secondTick;

  @override
  void initState() {
    super.initState();
    _secondTick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted && widget.controller.isRecording) setState(() {});
    });
  }

  @override
  void dispose() {
    _secondTick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) => _MainScaffold(
      page: MotivaPage.upload,
      onNavigate: widget.onNavigate,
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.controller.isRecording) ...[
              _CaptureHeading(controller: widget.controller),
              const SizedBox(height: 12),
              _CameraCard(controller: widget.controller),
              const SizedBox(height: 10),
              _TelemetryRow(controller: widget.controller),
              const SizedBox(height: 10),
            ] else
              _UploadHero(controller: widget.controller),
            _QueueCard(controller: widget.controller),
            if (widget.controller.errorMessage != null) ...[
              const SizedBox(height: 10),
              _ErrorCard(message: widget.controller.errorMessage!),
            ],
            if (widget.controller.isRecording) ...[
              const SizedBox(height: 12),
              _RecordControl(controller: widget.controller),
            ],
            const SizedBox(height: 26),
            const _SectionTitle(title: 'Análises recentes'),
            const SizedBox(height: 12),
            const _AnalysisItem(
              id: 'IV-8839',
              title: 'BR-101 · sentido norte',
              detail: 'km 82 · capturado hoje, 09:42',
              status: 'BAIXO',
              tone: Color(0xFF47A45B),
            ),
            const SizedBox(height: 10),
            const _AnalysisItem(
              id: 'IV-8830',
              title: 'BR-116 · sentido sul',
              detail: 'km 214 · processando imagens',
              status: 'MÉDIO',
              tone: Color(0xFFD7A21C),
            ),
            const SizedBox(height: 10),
            const _AnalysisItem(
              id: 'IV-8831',
              title: 'BR-040 · sentido norte',
              detail: 'km 37 · requer nova coleta',
              status: 'REVISAR',
              tone: Color(0xFFD74D52),
            ),
          ],
        ),
      ),
    ),
  );
}

final class _UploadHero extends StatelessWidget {
  const _UploadHero({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      const Text(
        'Nova coleta',
        style: TextStyle(
          color: motivaInk,
          fontSize: 24,
          fontWeight: FontWeight.w900,
          letterSpacing: -0.5,
        ),
      ),
      const SizedBox(height: 6),
      const Text(
        'Posicione o telefone com segurança antes de começar.',
        style: TextStyle(color: motivaMuted, fontSize: 13, height: 1.4),
      ),
      const SizedBox(height: 18),
      Container(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: motivaLine),
          boxShadow: const [
            BoxShadow(
              color: Color(0x10123E31),
              blurRadius: 18,
              offset: Offset(0, 8),
            ),
          ],
        ),
        child: Column(
          children: [
            Container(
              height: 172,
              margin: const EdgeInsets.all(10),
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [Color(0xFFE8F2EC), Color(0xFFDDEBE3)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                borderRadius: BorderRadius.circular(18),
              ),
              child: const Stack(
                fit: StackFit.expand,
                children: [
                  CustomPaint(painter: _FieldPreviewPainter()),
                  Center(
                    child: CircleAvatar(
                      radius: 31,
                      backgroundColor: greenvForest,
                      child: Icon(
                        Icons.videocam_rounded,
                        color: Colors.white,
                        size: 29,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 8, 18, 18),
              child: Column(
                children: [
                  const Text(
                    'Grave o trecho da rodovia',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -0.2,
                    ),
                  ),
                  const SizedBox(height: 7),
                  const Text(
                    'A câmera, o GPS e os sensores serão registrados juntos. A coleta continua mesmo sem internet.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 12,
                      height: 1.45,
                      color: motivaMuted,
                    ),
                  ),
                  const SizedBox(height: 16),
                  const Row(
                    children: [
                      Expanded(
                        child: _ReadyIndicator(
                          icon: Icons.camera_alt_outlined,
                          label: 'Câmera',
                        ),
                      ),
                      SizedBox(width: 8),
                      Expanded(
                        child: _ReadyIndicator(
                          icon: Icons.location_on_outlined,
                          label: 'Localização',
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  _RecordControl(controller: controller),
                ],
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 14),
    ],
  );
}

final class _ReadyIndicator extends StatelessWidget {
  const _ReadyIndicator({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
    decoration: BoxDecoration(
      color: greenvMint,
      borderRadius: BorderRadius.circular(12),
    ),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(icon, size: 16, color: motivaGreen),
        const SizedBox(width: 6),
        Flexible(
          child: Text(
            label,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: motivaGreen,
              fontSize: 11,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
      ],
    ),
  );
}

final class _CaptureHeading extends StatelessWidget {
  const _CaptureHeading({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Rota em andamento',
              key: Key('capture-state'),
              style: TextStyle(
                fontSize: 23,
                fontWeight: FontWeight.w900,
                letterSpacing: -0.4,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              'Segmento ${controller.segmentIndex + 1} sendo gravado',
              style: const TextStyle(fontSize: 12, color: motivaMuted),
            ),
          ],
        ),
      ),
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
        decoration: BoxDecoration(
          color: const Color(0xFFFFE8E8),
          borderRadius: BorderRadius.circular(99),
          border: Border.all(color: const Color(0xFFFFD0D4)),
        ),
        child: const Row(
          children: [
            Icon(Icons.fiber_manual_record, size: 10, color: Color(0xFFD8334A)),
            SizedBox(width: 4),
            Text(
              'GRAVANDO',
              style: TextStyle(
                color: Color(0xFFB52D42),
                fontSize: 9,
                fontWeight: FontWeight.w900,
                letterSpacing: 0.6,
              ),
            ),
          ],
        ),
      ),
    ],
  );
}

final class _CameraCard extends StatelessWidget {
  const _CameraCard({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(22),
    child: AspectRatio(
      aspectRatio: 16 / 10,
      child: Stack(
        fit: StackFit.expand,
        children: [
          controller.recorder.buildPreview(),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [Color(0x11000000), Color(0x88000000)],
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
              ),
            ),
          ),
          Positioned(
            left: 12,
            bottom: 11,
            child: _OverlayPill(
              icon: Icons.schedule_rounded,
              text: controller.recordingStartedAt == null
                  ? '00:00:00'
                  : _elapsed(controller.recordingStartedAt!),
            ),
          ),
          const Positioned(
            right: 12,
            bottom: 11,
            child: _OverlayPill(icon: Icons.hd_outlined, text: 'HD'),
          ),
        ],
      ),
    ),
  );

  static String _elapsed(DateTime started) {
    final value = DateTime.now().toUtc().difference(started);
    final hours = value.inHours.toString().padLeft(2, '0');
    final minutes = (value.inMinutes % 60).toString().padLeft(2, '0');
    final seconds = (value.inSeconds % 60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }
}

final class _OverlayPill extends StatelessWidget {
  const _OverlayPill({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
    decoration: BoxDecoration(
      color: const Color(0xB017151A),
      borderRadius: BorderRadius.circular(99),
    ),
    child: Row(
      children: [
        Icon(icon, color: Colors.white, size: 13),
        const SizedBox(width: 4),
        Text(
          text,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 10,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    ),
  );
}

final class _TelemetryRow extends StatelessWidget {
  const _TelemetryRow({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) {
    final accuracy = controller.telemetry.latestHorizontalAccuracyMeters;
    final speed = controller.telemetry.latestSpeedMetersPerSecond;
    final quality = accuracy == null
        ? 'Aguardando'
        : accuracy <= 10
        ? 'Sinal bom'
        : accuracy <= 25
        ? 'Sinal médio'
        : 'Sinal fraco';
    return Row(
      children: [
        Expanded(
          child: _StatusTile(
            icon: Icons.gps_fixed_rounded,
            label: 'GPS',
            value: quality,
            detail: accuracy == null
                ? '—'
                : '± ${accuracy.toStringAsFixed(1)} m',
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: _StatusTile(
            icon: Icons.speed_rounded,
            label: 'VELOCIDADE',
            value: speed == null
                ? '— km/h'
                : '${(speed * 3.6).toStringAsFixed(0)} km/h',
            detail: 'sensor do telefone',
          ),
        ),
      ],
    );
  }
}

final class _StatusTile extends StatelessWidget {
  const _StatusTile({
    required this.icon,
    required this.label,
    required this.value,
    required this.detail,
  });

  final IconData icon;
  final String label;
  final String value;
  final String detail;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(13),
    decoration: _cardDecoration(radius: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 17, color: motivaGreen),
            const SizedBox(width: 6),
            Text(
              label,
              style: const TextStyle(
                fontSize: 9,
                letterSpacing: 0.7,
                fontWeight: FontWeight.w800,
                color: motivaMuted,
              ),
            ),
          ],
        ),
        const SizedBox(height: 9),
        Text(
          value,
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w900),
        ),
        Text(detail, style: const TextStyle(fontSize: 10, color: motivaMuted)),
      ],
    ),
  );
}

final class _QueueCard extends StatelessWidget {
  const _QueueCard({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<int>(
    valueListenable: controller.backlog,
    builder: (context, backlog, _) => Container(
      key: const Key('queue-card'),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: backlog == 0 ? greenvMint : motivaLilac,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: backlog == 0
              ? const Color(0xFFCDE7D8)
              : const Color(0xFFDED5FF),
        ),
      ),
      child: Row(
        children: [
          const CircleAvatar(
            radius: 16,
            backgroundColor: Color(0xCCFFFFFF),
            child: Icon(
              Icons.cloud_done_outlined,
              size: 17,
              color: motivaGreen,
            ),
          ),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  backlog == 0
                      ? 'Tudo enviado'
                      : '$backlog segmento${backlog == 1 ? '' : 's'} na fila',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                    color: backlog == 0 ? greenvForest : motivaPurpleDark,
                  ),
                ),
                const Text(
                  'Os arquivos ficam protegidos até a confirmação do envio.',
                  style: TextStyle(fontSize: 10, color: motivaMuted),
                ),
              ],
            ),
          ),
          Icon(
            backlog == 0 ? Icons.check_circle_rounded : Icons.sync_rounded,
            color: motivaGreen,
            size: 20,
          ),
        ],
      ),
    ),
  );
}

final class _RecordControl extends StatelessWidget {
  const _RecordControl({required this.controller});

  final CaptureCoordinator controller;

  @override
  Widget build(BuildContext context) {
    final busy =
        controller.phase == CapturePhase.preparing ||
        controller.phase == CapturePhase.stopping;
    final recording = controller.isRecording;
    return FilledButton.icon(
      key: const Key('record-button'),
      onPressed: busy
          ? null
          : recording
          ? controller.stop
          : controller.start,
      style: FilledButton.styleFrom(
        backgroundColor: recording ? const Color(0xFFB52D42) : greenvForest,
      ),
      icon: busy
          ? const SizedBox.square(
              dimension: 16,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Colors.white,
              ),
            )
          : Icon(
              recording ? Icons.stop_rounded : Icons.videocam_rounded,
              size: 17,
            ),
      label: Text(
        controller.phase == CapturePhase.stopping
            ? 'Finalizando…'
            : controller.phase == CapturePhase.preparing
            ? 'Preparando…'
            : recording
            ? 'Encerrar coleta'
            : 'Iniciar coleta',
      ),
    );
  }
}

final class _ErrorCard extends StatelessWidget {
  const _ErrorCard({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFFFFECE9),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFFFD5CF)),
    ),
    child: Row(
      children: [
        const Icon(
          Icons.warning_amber_rounded,
          color: Color(0xFFB5283D),
          size: 20,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            message,
            maxLines: 3,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, height: 1.35),
          ),
        ),
      ],
    ),
  );
}

final class _AnalysisItem extends StatelessWidget {
  const _AnalysisItem({
    required this.id,
    required this.title,
    required this.detail,
    required this.status,
    required this.tone,
  });

  final String id;
  final String title;
  final String detail;
  final String status;
  final Color tone;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(13),
    decoration: _cardDecoration(radius: 17),
    child: Row(
      children: [
        Container(
          width: 48,
          height: 48,
          decoration: BoxDecoration(
            color: tone.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Center(
            child: Icon(
              status == 'REVISAR'
                  ? Icons.error_outline_rounded
                  : status == 'MÉDIO'
                  ? Icons.hourglass_top_rounded
                  : Icons.eco_outlined,
              color: tone,
              size: 23,
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                detail,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 11,
                  height: 1.3,
                  color: motivaMuted,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                'ID $id',
                style: const TextStyle(fontSize: 9, color: motivaMuted),
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
          decoration: BoxDecoration(
            color: tone.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(99),
          ),
          child: Text(
            status,
            style: TextStyle(
              fontSize: 8,
              color: tone,
              fontWeight: FontWeight.w900,
              letterSpacing: 0.4,
            ),
          ),
        ),
      ],
    ),
  );
}

final class _NetworkScreen extends StatelessWidget {
  const _NetworkScreen({required this.onNavigate});

  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => _MainScaffold(
    page: MotivaPage.map,
    onNavigate: onNavigate,
    body: Stack(
      children: [
        const Positioned.fill(child: _RouteMap(height: double.infinity)),
        Positioned(
          left: 18,
          right: 18,
          top: 16,
          child: _SearchBar(onMap: () => onNavigate(MotivaPage.map)),
        ),
        Positioned(
          left: 18,
          right: 18,
          bottom: 18,
          child: _NetworkSheet(onMap: () => onNavigate(MotivaPage.map)),
        ),
      ],
    ),
  );
}

final class _SearchBar extends StatelessWidget {
  const _SearchBar({required this.onMap});

  final VoidCallback onMap;

  @override
  Widget build(BuildContext context) => Container(
    height: 52,
    padding: const EdgeInsets.only(left: 15, right: 6),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFD5DED7)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x1A123E31),
          blurRadius: 16,
          offset: Offset(0, 6),
        ),
      ],
    ),
    child: Row(
      children: [
        const Icon(Icons.search_rounded, size: 20, color: motivaMuted),
        const SizedBox(width: 9),
        const Expanded(
          child: Text(
            'Buscar rodovia, trecho ou km',
            style: TextStyle(fontSize: 13, color: motivaMuted),
          ),
        ),
        IconButton(
          onPressed: onMap,
          icon: const Icon(
            Icons.mic_none_rounded,
            color: motivaPurpleDark,
            size: 20,
          ),
        ),
      ],
    ),
  );
}

final class _NetworkSheet extends StatelessWidget {
  const _NetworkSheet({required this.onMap});

  final VoidCallback onMap;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.fromLTRB(16, 11, 16, 18),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(24),
      boxShadow: const [
        BoxShadow(
          color: Color(0x29123E31),
          blurRadius: 28,
          offset: Offset(0, 12),
        ),
      ],
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Center(
          child: Container(
            width: 48,
            height: 4,
            decoration: BoxDecoration(
              color: const Color(0xFFCAD5CD),
              borderRadius: BorderRadius.circular(99),
            ),
          ),
        ),
        const SizedBox(height: 14),
        GestureDetector(
          onTap: onMap,
          child: Container(
            height: 72,
            padding: const EdgeInsets.symmetric(horizontal: 15),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [greenvForest, Color(0xFF1D604A)],
              ),
              borderRadius: BorderRadius.circular(17),
            ),
            child: const Row(
              children: [
                CircleAvatar(
                  radius: 20,
                  backgroundColor: Color(0x2FFFFFFF),
                  child: Icon(Icons.route, color: Colors.white, size: 20),
                ),
                SizedBox(width: 10),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Visão geral da malha',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      Text(
                        'Toque para explorar o mapa',
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: Colors.white70, fontSize: 10),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 16),
        const Text(
          'RESUMO DA MALHA',
          style: TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w800,
            letterSpacing: 0.8,
            color: motivaMuted,
          ),
        ),
        const SizedBox(height: 10),
        const Row(
          children: [
            Expanded(
              child: _SummaryValue(
                value: '291',
                label: 'Trechos',
                color: Color(0xFF337842),
              ),
            ),
            Expanded(
              child: _SummaryValue(
                value: '220',
                label: 'Atenção',
                color: Color(0xFFE18122),
              ),
            ),
            Expanded(
              child: _SummaryValue(
                value: '131',
                label: 'Críticos',
                color: Color(0xFFC9383E),
              ),
            ),
          ],
        ),
        const Divider(height: 26),
        const Text(
          'NÍVEL DE VEGETAÇÃO (MÉDIO)',
          style: TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w800,
            letterSpacing: 0.6,
            color: motivaMuted,
          ),
        ),
        const SizedBox(height: 4),
        const Text.rich(
          TextSpan(
            children: [
              TextSpan(
                text: '2,6 m',
                style: TextStyle(
                  fontSize: 25,
                  fontWeight: FontWeight.w900,
                  color: Color(0xFF35723F),
                ),
              ),
              TextSpan(
                text: '  Médio',
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                  color: Color(0xFFD9871E),
                ),
              ),
            ],
          ),
        ),
        const Divider(height: 26),
        const Row(
          children: [
            Expanded(
              child: _SummaryValue(
                value: '131',
                label: '45% da malha',
                color: Color(0xFFC9383E),
              ),
            ),
            Expanded(
              child: _SummaryValue(
                value: 'Hoje, 09:30',
                label: 'Última atualização',
                color: motivaInk,
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        const Row(
          children: [
            _LegendDot(color: Color(0xFF46A65A), label: 'Baixo'),
            SizedBox(width: 18),
            _LegendDot(color: Color(0xFFE3A622), label: 'Médio'),
            SizedBox(width: 18),
            _LegendDot(color: Color(0xFFC9383E), label: 'Alto'),
          ],
        ),
      ],
    ),
  );
}

final class _SummaryValue extends StatelessWidget {
  const _SummaryValue({
    required this.value,
    required this.label,
    required this.color,
  });

  final String value;
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.only(left: 9),
    decoration: BoxDecoration(
      border: Border(left: BorderSide(color: color, width: 3)),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          value,
          style: TextStyle(
            color: color,
            fontSize: 16,
            fontWeight: FontWeight.w900,
          ),
        ),
        Text(label, style: const TextStyle(fontSize: 10, color: motivaMuted)),
      ],
    ),
  );
}

final class _LegendDot extends StatelessWidget {
  const _LegendDot({required this.color, required this.label});

  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Container(
        width: 9,
        height: 9,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
      const SizedBox(width: 4),
      Text(
        label,
        style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700),
      ),
    ],
  );
}

final class _MapScreen extends StatelessWidget {
  const _MapScreen({required this.onNavigate});

  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => _MainScaffold(
    page: MotivaPage.map,
    onNavigate: onNavigate,
    body: Stack(
      children: [
        const Positioned.fill(
          child: _RouteMap(height: double.infinity, detailed: true),
        ),
        const Positioned(
          left: 18,
          right: 18,
          top: 16,
          child: _SearchBar(onMap: _noop),
        ),
        Positioned(
          right: 18,
          top: 78,
          child: Column(
            children: [
              _MapTool(icon: Icons.add, onTap: () {}),
              const SizedBox(height: 4),
              _MapTool(icon: Icons.remove, onTap: () {}),
              const SizedBox(height: 4),
              _MapTool(
                icon: Icons.my_location_rounded,
                onTap: () {},
                purple: true,
              ),
            ],
          ),
        ),
        const Positioned(
          left: 18,
          right: 18,
          bottom: 18,
          child: _MapLegendCard(),
        ),
      ],
    ),
  );

  static void _noop() {}
}

final class _MapLegendCard extends StatelessWidget {
  const _MapLegendCard();

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.fromLTRB(15, 13, 15, 14),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      border: Border.all(color: motivaLine),
      boxShadow: const [
        BoxShadow(
          color: Color(0x24123E31),
          blurRadius: 20,
          offset: Offset(0, 8),
        ),
      ],
    ),
    child: const Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                'Nível de vegetação',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w900),
              ),
            ),
            Icon(Icons.tune_rounded, size: 18, color: motivaPurpleDark),
          ],
        ),
        SizedBox(height: 10),
        Row(
          children: [
            _LegendDot(color: Color(0xFF46A65A), label: 'Baixo'),
            SizedBox(width: 18),
            _LegendDot(color: Color(0xFFE3A622), label: 'Médio'),
            SizedBox(width: 18),
            _LegendDot(color: Color(0xFFC9383E), label: 'Alto'),
          ],
        ),
      ],
    ),
  );
}

final class _MapTool extends StatelessWidget {
  const _MapTool({
    required this.icon,
    required this.onTap,
    this.purple = false,
  });

  final IconData icon;
  final VoidCallback onTap;
  final bool purple;

  @override
  Widget build(BuildContext context) => Material(
    color: Colors.white,
    borderRadius: BorderRadius.circular(5),
    child: InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(5),
      child: SizedBox(
        width: 42,
        height: 42,
        child: Icon(
          icon,
          size: 20,
          color: purple ? motivaPurple : const Color(0xFF777078),
        ),
      ),
    ),
  );
}

final class _RouteMap extends StatelessWidget {
  const _RouteMap({
    required this.height,
    this.compact = false,
    this.detailed = false,
  });

  final double height;
  final bool compact;
  final bool detailed;

  @override
  Widget build(BuildContext context) => Container(
    height: height,
    clipBehavior: Clip.antiAlias,
    decoration: BoxDecoration(
      color: const Color(0xFFE7EFE7),
      borderRadius: compact ? BorderRadius.circular(13) : BorderRadius.zero,
      border: compact ? Border.all(color: motivaLine) : null,
    ),
    child: CustomPaint(painter: _RouteMapPainter(detailed: detailed)),
  );
}

final class _RouteMapPainter extends CustomPainter {
  const _RouteMapPainter({required this.detailed});

  final bool detailed;

  @override
  void paint(Canvas canvas, Size size) {
    final land = Paint()..color = const Color(0xFFD7E8D3);
    final water = Paint()
      ..color = const Color(0xFFCDE6E3)
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * .08;
    final road = Paint()
      ..color = Colors.white.withValues(alpha: 0.90)
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke;
    canvas.drawRect(
      Offset.zero & size,
      Paint()..color = const Color(0xFFE6EEE4),
    );
    canvas.drawOval(
      Rect.fromLTWH(
        -size.width * .15,
        size.height * .08,
        size.width * .62,
        size.height * .48,
      ),
      land,
    );
    canvas.drawOval(
      Rect.fromLTWH(
        size.width * .58,
        size.height * .48,
        size.width * .58,
        size.height * .44,
      ),
      land,
    );
    canvas.drawPath(
      Path()
        ..moveTo(size.width * .76, 0)
        ..quadraticBezierTo(
          size.width * .58,
          size.height * .36,
          size.width * .91,
          size.height,
        ),
      water,
    );
    for (var index = -1; index < 8; index++) {
      final x = index * size.width / 5;
      canvas.drawLine(
        Offset(x, 0),
        Offset(x + size.width * .75, size.height),
        road,
      );
      final y = index * size.height / 6;
      canvas.drawLine(
        Offset(0, y),
        Offset(size.width, y + size.height * .22),
        road,
      );
    }
    final route = Path()
      ..moveTo(size.width * .40, size.height * 1.03)
      ..cubicTo(
        size.width * .43,
        size.height * .78,
        size.width * .57,
        size.height * .75,
        size.width * .59,
        size.height * .55,
      )
      ..cubicTo(
        size.width * .61,
        size.height * .39,
        size.width * .73,
        size.height * .37,
        size.width * .72,
        size.height * .21,
      )
      ..cubicTo(
        size.width * .71,
        size.height * .11,
        size.width * .61,
        size.height * .09,
        size.width * .64,
        -size.height * .04,
      );
    canvas.drawPath(
      route,
      Paint()
        ..color = const Color(0xFFF3B719)
        ..style = PaintingStyle.stroke
        ..strokeWidth = detailed ? 7 : 5
        ..strokeCap = StrokeCap.round,
    );
    final points = [
      Offset(size.width * .43, size.height * .85),
      Offset(size.width * .54, size.height * .69),
      Offset(size.width * .60, size.height * .53),
      Offset(size.width * .69, size.height * .38),
      Offset(size.width * .70, size.height * .25),
    ];
    for (final point in points) {
      canvas.drawCircle(
        point,
        detailed ? 8 : 6,
        Paint()..color = const Color(0xFF25A06A),
      );
      canvas.drawCircle(
        point,
        detailed ? 3 : 2.5,
        Paint()..color = Colors.white,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _RouteMapPainter oldDelegate) =>
      oldDelegate.detailed != detailed;
}

final class _BottomNavigation extends StatelessWidget {
  const _BottomNavigation({required this.selected, required this.onNavigate});

  final MotivaPage selected;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => Container(
    decoration: const BoxDecoration(
      color: Colors.white,
      border: Border(top: BorderSide(color: motivaLine)),
      boxShadow: [
        BoxShadow(
          color: Color(0x0F123E31),
          blurRadius: 16,
          offset: Offset(0, -5),
        ),
      ],
    ),
    child: SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 9, 20, 10),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceAround,
          children: [
            _NavigationItem(
              keyName: 'nav-home',
              icon: Icons.home_rounded,
              label: 'Início',
              selected: selected == MotivaPage.home,
              onTap: () => onNavigate(MotivaPage.home),
            ),
            _NavigationItem(
              keyName: 'nav-upload',
              icon: Icons.videocam_rounded,
              label: 'Coleta',
              selected: selected == MotivaPage.upload,
              onTap: () => onNavigate(MotivaPage.upload),
            ),
            _NavigationItem(
              keyName: 'nav-mapa',
              icon: Icons.map_rounded,
              label: 'Mapa',
              selected:
                  selected == MotivaPage.map || selected == MotivaPage.network,
              onTap: () => onNavigate(MotivaPage.map),
            ),
          ],
        ),
      ),
    ),
  );
}

final class _NavigationItem extends StatelessWidget {
  const _NavigationItem({
    required this.keyName,
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String keyName;
  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    key: Key(keyName),
    onTap: onTap,
    borderRadius: BorderRadius.circular(14),
    child: AnimatedContainer(
      duration: const Duration(milliseconds: 150),
      width: 88,
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: BoxDecoration(
        color: selected ? greenvMint : Colors.transparent,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 21, color: selected ? greenvForest : motivaMuted),
          const SizedBox(height: 3),
          Text(
            label,
            style: TextStyle(
              fontSize: 10,
              fontWeight: selected ? FontWeight.w900 : FontWeight.w600,
              color: selected ? greenvForest : motivaMuted,
            ),
          ),
        ],
      ),
    ),
  );
}

final class _MotivaLogo extends StatelessWidget {
  const _MotivaLogo({required this.color, this.large = false});

  final Color color;
  final bool large;

  @override
  Widget build(BuildContext context) => Semantics(
    label: 'motiva',
    child: ExcludeSemantics(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CustomPaint(
            size: Size(large ? 82 : 48, large ? 46 : 28),
            painter: _MotivaMarkPainter(color),
          ),
          SizedBox(height: large ? 7 : 3),
          Text(
            'motiva',
            style: TextStyle(
              color: color,
              fontSize: large ? 34 : 19,
              height: 1,
              letterSpacing: -0.7,
              fontWeight: FontWeight.w800,
            ),
          ),
        ],
      ),
    ),
  );
}

final class _MotivaMarkPainter extends CustomPainter {
  const _MotivaMarkPainter(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = color;
    final width = size.width / 4.4;
    for (var index = 0; index < 4; index++) {
      final left = index * width * .92;
      final top = index.isEven ? size.height * .22 : size.height * .05;
      final path = Path()
        ..moveTo(left + width * .12, top + size.height * .42)
        ..lineTo(left + width * .50, top)
        ..lineTo(left + width, top)
        ..lineTo(left + width * .62, top + size.height * .58)
        ..lineTo(left + width * .12, top + size.height * .58)
        ..close();
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _MotivaMarkPainter oldDelegate) =>
      oldDelegate.color != color;
}

final class _GreenVLogo extends StatelessWidget {
  const _GreenVLogo({this.fontSize = 21, this.light = false});

  final double fontSize;
  final bool light;

  @override
  Widget build(BuildContext context) => Semantics(
    label: 'GreenV',
    child: ExcludeSemantics(
      child: Text.rich(
        TextSpan(
          children: [
            TextSpan(
              text: 'Green',
              style: TextStyle(
                color: light ? Colors.white : const Color(0xFF075D37),
                fontSize: fontSize,
                fontFamily: 'serif',
                fontWeight: FontWeight.w600,
              ),
            ),
            TextSpan(
              text: 'V',
              style: TextStyle(
                color: light
                    ? const Color(0xFF8ED081)
                    : const Color(0xFF5E9A38),
                fontSize: fontSize + 1,
                fontFamily: 'serif',
                fontStyle: FontStyle.italic,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

final class _PatternPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawCircle(
      Offset(size.width * .86, size.height * .08),
      size.width * .34,
      Paint()..color = const Color(0x176546D7),
    );
    canvas.drawCircle(
      Offset(size.width * .08, size.height * .92),
      size.width * .46,
      Paint()..color = const Color(0x192D8A62),
    );
    final line = Paint()
      ..color = const Color(0x184E34B5)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2;
    for (var index = 0; index < 4; index++) {
      final inset = index * 18.0;
      canvas.drawArc(
        Rect.fromLTWH(
          size.width - 150 + inset,
          -80 + inset,
          220 - inset,
          220 - inset,
        ),
        .7,
        2.1,
        false,
        line,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

final class _FieldRoutePainter extends CustomPainter {
  const _FieldRoutePainter();

  @override
  void paint(Canvas canvas, Size size) {
    final glow = Paint()..color = const Color(0x1458B584);
    canvas.drawCircle(
      Offset(size.width * .94, size.height * .08),
      size.width * .38,
      glow,
    );
    final road = Path()
      ..moveTo(size.width * .80, size.height * 1.08)
      ..cubicTo(
        size.width * .66,
        size.height * .75,
        size.width * .98,
        size.height * .54,
        size.width * .78,
        -size.height * .08,
      );
    canvas.drawPath(
      road,
      Paint()
        ..color = const Color(0x1FFFFFFF)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 54
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawPath(
      road,
      Paint()
        ..color = const Color(0x80FFFFFF)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

final class _FieldPreviewPainter extends CustomPainter {
  const _FieldPreviewPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final hill = Paint()..color = const Color(0xFFBFD9C7);
    canvas.drawOval(
      Rect.fromLTWH(
        -45,
        size.height * .46,
        size.width * .72,
        size.height * .65,
      ),
      hill,
    );
    canvas.drawOval(
      Rect.fromLTWH(
        size.width * .48,
        size.height * .38,
        size.width * .70,
        size.height * .72,
      ),
      Paint()..color = const Color(0xFFA8CEB5),
    );
    final road = Path()
      ..moveTo(size.width * .37, size.height * 1.05)
      ..cubicTo(
        size.width * .47,
        size.height * .72,
        size.width * .65,
        size.height * .70,
        size.width * .60,
        size.height * .34,
      );
    canvas.drawPath(
      road,
      Paint()
        ..color = const Color(0xFFD8DCD8)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 38
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawPath(
      road,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

BoxDecoration _cardDecoration({double radius = 15}) => BoxDecoration(
  color: Colors.white,
  borderRadius: BorderRadius.circular(radius),
  border: Border.all(color: motivaLine),
  boxShadow: const [
    BoxShadow(color: Color(0x0D123E31), blurRadius: 14, offset: Offset(0, 6)),
  ],
);
