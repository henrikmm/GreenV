import 'dart:async';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/ui/auth/login_screen.dart';
import 'package:greenv_capture/src/ui/auth/splash_screen.dart';
import 'package:greenv_capture/src/ui/capture/capture_screen.dart';
import 'package:greenv_capture/src/ui/operations_screens.dart';
import 'package:greenv_capture/src/ui/shell.dart';
import 'package:greenv_capture/src/ui/theme.dart';

export 'package:greenv_capture/src/ui/shell.dart' show MotivaPage;

final class CaptureApp extends StatelessWidget {
  const CaptureApp({required this.dependencies, this.initialPage, super.key});

  final AppDependencies dependencies;
  final MotivaPage? initialPage;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Motiva GreenV',
    debugShowCheckedModeBanner: false,
    theme: motivaTheme(),
    // A tap anywhere that is not a field closes the keyboard. Flutter only does this on desktop
    // and web; on a phone the login screen kept the keyboard up over the "Entrar" button, and
    // tapping the background did nothing, so the only way out was the system back gesture.
    // Translucent, so buttons and fields underneath still win their own taps.
    builder: (context, child) => GestureDetector(
      behavior: HitTestBehavior.translucent,
      onTap: () => FocusManager.instance.primaryFocus?.unfocus(),
      child: child,
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
          (dependencies.authenticator.signedIn.value ? MotivaPage.home : _pageFromUri()),
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

final class _MotivaFlowState extends State<_MotivaFlow> with WidgetsBindingObserver {
  late MotivaPage _page = widget.initialPage;
  Timer? _syncTick;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _syncTick = Timer.periodic(const Duration(seconds: 5), (_) => widget.controller.syncBacklog());
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

  /// The session the map is framed on, or null for every measured stretch.
  String? _focusedSession;

  void _go(MotivaPage page) => setState(() => _page = page);

  void _openSessionOnMap(String sessionId) => setState(() {
    _focusedSession = sessionId;
    _page = MotivaPage.map;
  });

  /// The screens anyone may open. Everything else needs a token, because everything else either
  /// uploads or shows what was uploaded.
  static const Set<MotivaPage> _publicPages = {MotivaPage.splash, MotivaPage.login};

  /// Whether [page] may be shown right now.
  ///
  /// A guard rather than a starting point. Choosing the first screen is not enough: `?screen=upload`
  /// would otherwise open the capture screen with no session at all, and a session that dies mid-use
  /// would leave whoever is holding the phone on a screen that can no longer do anything.
  bool _mayShow(MotivaPage page) =>
      widget.mockedPreview || _publicPages.contains(page) || widget.authenticator.signedIn.value;

  @override
  Widget build(BuildContext context) {
    final visible = _mayShow(_page) ? _page : MotivaPage.login;
    final screen = switch (visible) {
      MotivaPage.splash => const SplashScreen(),
      MotivaPage.login => LoginScreen(onNavigate: _go, authenticator: widget.authenticator),
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
      MotivaPage.upload => CaptureScreen(controller: widget.controller, onNavigate: _go),
      MotivaPage.stretches => StretchesScreen(operations: widget.operations, onNavigate: _go),
      MotivaPage.map => MapScreen(
        operations: widget.operations,
        onNavigate: _go,
        sessionId: _focusedSession,
        onClearSession: () => setState(() => _focusedSession = null),
      ),
      MotivaPage.orders => OrdersScreen(operations: widget.operations, onNavigate: _go),
      MotivaPage.teams => TeamsScreen(operations: widget.operations, onNavigate: _go),
    };
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 180),
      child: KeyedSubtree(key: ValueKey(visible), child: screen),
    );
  }
}
