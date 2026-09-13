import 'dart:async';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/ui/operations_screens.dart';
import 'package:greenv_capture/src/ui/shell.dart';

export 'package:greenv_capture/src/ui/shell.dart' show MotivaPage;

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
      operations: dependencies.operations,
      mockedPreview: dependencies.mockedPreview,
      // A session restored from the last run opens straight into the app. Everything else starts
      // at the login screen - and the guard in _MotivaFlowState keeps it there.
      initialPage:
          initialPage ??
          (dependencies.authenticator.signedIn.value
              ? MotivaPage.home
              : _pageFromUri()),
    ),
  );

  /// `?screen=` is a design-preview convenience for opening one screen directly. It only chooses a
  /// starting point; it cannot get past the session guard in a real build.
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
    required this.operations,
    required this.mockedPreview,
    required this.initialPage,
  });

  final CaptureCoordinator controller;
  final SessionAuthenticator authenticator;
  final OperationsGateway operations;
  final bool mockedPreview;
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
    if (!widget.authenticator.signedIn.value &&
        mounted &&
        _page != MotivaPage.login) {
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

  /// The session the map is framed on, or null for every measured stretch.
  String? _focusedSession;

  void _go(MotivaPage page) => setState(() => _page = page);

  void _openSessionOnMap(String sessionId) => setState(() {
    _focusedSession = sessionId;
    _page = MotivaPage.map;
  });

  /// The screens anyone may open. Everything else needs a token, because everything else either
  /// uploads or shows what was uploaded.
  static const Set<MotivaPage> _publicPages = {
    MotivaPage.splash,
    MotivaPage.login,
    MotivaPage.forgotEmail,
    MotivaPage.forgotCode,
  };

  /// Whether [page] may be shown right now.
  ///
  /// A guard rather than a starting point. Choosing the first screen is not enough: `?screen=upload`
  /// would otherwise open the capture screen with no session at all, and a session that dies mid-use
  /// would leave whoever is holding the phone on a screen that can no longer do anything.
  bool _mayShow(MotivaPage page) =>
      widget.mockedPreview ||
      _publicPages.contains(page) ||
      widget.authenticator.signedIn.value;

  @override
  Widget build(BuildContext context) {
    final visible = _mayShow(_page) ? _page : MotivaPage.login;
    final screen = switch (visible) {
      MotivaPage.splash => const _SplashScreen(),
      MotivaPage.login => _LoginScreen(
        onNavigate: _go,
        authenticator: widget.authenticator,
      ),
      MotivaPage.forgotEmail => _ForgotEmailScreen(onNavigate: _go),
      MotivaPage.forgotCode => _ForgotCodeScreen(onNavigate: _go),
      MotivaPage.home => HomeScreen(
        operations: widget.operations,
        capture: widget.controller,
        onNavigate: _go,
        onOpenSession: _openSessionOnMap,
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
      MotivaPage.stretches => StretchesScreen(
        operations: widget.operations,
        onNavigate: _go,
      ),
      MotivaPage.map => MapScreen(
        operations: widget.operations,
        onNavigate: _go,
        sessionId: _focusedSession,
        onClearSession: () => setState(() => _focusedSession = null),
      ),
      MotivaPage.orders => OrdersScreen(
        operations: widget.operations,
        onNavigate: _go,
      ),
      MotivaPage.teams => TeamsScreen(
        operations: widget.operations,
        onNavigate: _go,
      ),
    };
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 180),
      child: KeyedSubtree(key: ValueKey(visible), child: screen),
    );
  }
}

final class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) => PhoneFrame(
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
              GreenVLogo(fontSize: 28, light: true),
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
  Widget build(BuildContext context) => PhoneFrame(
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
            GreenVLogo(fontSize: 22),
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
          AuthenticationFailureReason.invalidCredentials =>
            'E-mail ou senha inválidos.',
          AuthenticationFailureReason.providerUnavailable => 'O serviço de autenticação está indisponível. Tente novamente em instantes.',
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
              style: const TextStyle(
                color: Color(0xFF8C2020),
                fontSize: 12.5,
                height: 1.35,
              ),
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
                _obscured
                    ? Icons.visibility_outlined
                    : Icons.visibility_off_outlined,
                size: 20,
              ),
              onPressed: () => setState(() => _obscured = !_obscured),
            ),
          ),
        ),
        Align(
          alignment: Alignment.centerRight,
          child: TextButton(
            onPressed: _busy
                ? null
                : () => widget.onNavigate(MotivaPage.forgotEmail),
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
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Colors.white,
                  ),
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
          // Recovery is not implemented: nothing sends a code and nothing verifies one, so this
          // returns to the login screen rather than granting access. It used to open the app
          // directly, which was the same hole the login screen had.
          onPressed: () => onNavigate(MotivaPage.login),
          child: const Text('Voltar para entrar'),
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
    builder: (context, _) => MainScaffold(
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
              if (_tooVagueToMeasure(
                widget.controller.telemetry.latestHorizontalAccuracyMeters,
              )) ...[
                const SizedBox(height: 10),
                _GnssWarningCard(
                  accuracyMeters: widget
                      .controller
                      .telemetry
                      .latestHorizontalAccuracyMeters,
                ),
              ],
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

/// The accuracy past which the worker stops trusting a fix: `TelemetryAssociator` marks anything
/// vaguer than this `unavailable`, and a segment whose frames are all unavailable yields no
/// measurable trecho. The browser reported 50000 m from an IP-derived position and the app said
/// nothing, so 1.99 MB was recorded, hashed and uploaded for a segment that could never be used.
const _usableAccuracyMeters = 25.0;

bool _tooVagueToMeasure(double? accuracyMeters) =>
    accuracyMeters == null || accuracyMeters > _usableAccuracyMeters;

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
        : accuracy <= _usableAccuracyMeters
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
    decoration: cardDecoration(radius: 16),
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

/// Says out loud what the GPS tile only implies: at this accuracy the capture is not worth its
/// bytes. It is deliberately louder than the tile, because the tile reads as a reading and this is
/// a decision the person can still act on — stepping outside, or not driving the route yet.
final class _GnssWarningCard extends StatelessWidget {
  const _GnssWarningCard({required this.accuracyMeters});

  final double? accuracyMeters;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('gnss-warning'),
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFFFFF4E3),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFF6DCB6)),
    ),
    child: Row(
      children: [
        const Icon(Icons.gps_off_rounded, color: greenvAmber, size: 20),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            accuracyMeters == null
                ? 'Sem posição do GPS. Sem ela esta coleta não gera um trecho '
                      'medível: confira a permissão de localização.'
                : 'GPS com ± ${accuracyMeters!.toStringAsFixed(1)} m de erro, '
                      'acima do limite de '
                      '${_usableAccuracyMeters.toStringAsFixed(0)} m. Nesta '
                      'precisão a coleta não gera um trecho medível.',
            style: const TextStyle(fontSize: 12, height: 1.35),
          ),
        ),
      ],
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
