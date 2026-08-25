import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';

const motivaPurple = Color(0xFF6422F4);
const motivaPurpleDark = Color(0xFF4B16C9);
const motivaLilac = Color(0xFFF0E9FF);
const motivaGreen = Color(0xFF086B3C);
const motivaInk = Color(0xFF242027);
const motivaCanvas = Color(0xFFF9F7FA);
const motivaLine = Color(0xFFE5DEE8);

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
  const CaptureApp({
    required this.dependencies,
    this.initialPage,
    super.key,
  });

  final AppDependencies dependencies;
  final MotivaPage? initialPage;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Motiva GreenV',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: motivaPurple,
        primary: motivaPurple,
        surface: Colors.white,
      ),
      scaffoldBackgroundColor: const Color(0xFF201F20),
      fontFamily: 'Arial',
      textTheme: const TextTheme(
        bodyMedium: TextStyle(color: motivaInk),
        titleMedium: TextStyle(
          color: motivaInk,
          fontWeight: FontWeight.w700,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(44),
          backgroundColor: motivaPurple,
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(11),
          ),
          textStyle: const TextStyle(
            fontSize: 12,
            letterSpacing: 0.2,
            fontWeight: FontWeight.w800,
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        isDense: true,
        filled: true,
        fillColor: const Color(0xFFF9F9FA),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 12,
          vertical: 12,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(7),
          borderSide: const BorderSide(color: Color(0xFF333036)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(7),
          borderSide: const BorderSide(color: motivaPurple, width: 1.5),
        ),
      ),
    ),
    home: _MotivaFlow(
      controller: dependencies.capture,
      initialPage: initialPage ?? _pageFromUri(),
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
  const _MotivaFlow({required this.controller, required this.initialPage});

  final CaptureCoordinator controller;
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
    _syncTick?.cancel();
    super.dispose();
  }

  void _go(MotivaPage page) => setState(() => _page = page);

  @override
  Widget build(BuildContext context) {
    final screen = switch (_page) {
      MotivaPage.splash => const _SplashScreen(),
      MotivaPage.login => _LoginScreen(onNavigate: _go),
      MotivaPage.forgotEmail => _ForgotEmailScreen(onNavigate: _go),
      MotivaPage.forgotCode => _ForgotCodeScreen(onNavigate: _go),
      MotivaPage.home => _HomeScreen(onNavigate: _go),
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
        constraints: const BoxConstraints(maxWidth: 390),
        child: Semantics(
          label: 'mobile-app-frame',
          container: true,
          explicitChildNodes: true,
          child: ColoredBox(
            color: background,
            child: SafeArea(top: false, child: child),
          ),
        ),
      ),
    ),
  );
}

final class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) => const _PhoneFrame(
    background: Color(0xFF5D1FF5),
    child: Center(child: _MotivaLogo(color: Colors.white, large: true)),
  );
}

final class _AuthPattern extends StatelessWidget {
  const _AuthPattern({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => _PhoneFrame(
    background: const Color(0xFFF8F7F9),
    child: Stack(
      fit: StackFit.expand,
      children: [
        CustomPaint(painter: _PatternPainter()),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 64, 20, 28),
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
      color: const Color(0xFFFAFAFB),
      borderRadius: BorderRadius.circular(23),
      boxShadow: const [
        BoxShadow(
          color: Color(0x14000000),
          blurRadius: 16,
          offset: Offset(0, 6),
        ),
      ],
    ),
    padding: const EdgeInsets.fromLTRB(30, 38, 30, 22),
    child: Column(
      children: [
        const _MotivaLogo(color: motivaPurple),
        const SizedBox(height: 14),
        const _GreenVLogo(),
        const Spacer(),
        ...children,
        const Spacer(),
        footer ?? const SizedBox.shrink(),
      ],
    ),
  );
}

final class _LoginScreen extends StatelessWidget {
  const _LoginScreen({required this.onNavigate});

  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => _AuthPattern(
    child: _AuthCard(
      footer: TextButton.icon(
        onPressed: () {},
        icon: const Icon(Icons.corporate_fare_outlined, size: 16),
        label: const Text('Ver últimas notícias'),
      ),
      children: [
        const TextField(
          key: Key('email-field'),
          decoration: InputDecoration(hintText: 'Email'),
        ),
        const SizedBox(height: 10),
        const TextField(
          obscureText: true,
          decoration: InputDecoration(hintText: 'Senha'),
        ),
        Align(
          alignment: Alignment.centerRight,
          child: TextButton(
            onPressed: () => onNavigate(MotivaPage.forgotEmail),
            child: const Text(
              'Esqueceu a senha?',
              style: TextStyle(fontSize: 10),
            ),
          ),
        ),
        const SizedBox(height: 24),
        SizedBox(
          width: 190,
          child: FilledButton(
            key: const Key('login-button'),
            onPressed: () => onNavigate(MotivaPage.home),
            child: const Text('ENTRAR'),
          ),
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
        const Text(
          'Para recuperar a senha é necessário confirmar seu email para o envio do código',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 12,
            height: 1.35,
            color: Color(0xFF625B65),
          ),
        ),
        const SizedBox(height: 24),
        const TextField(decoration: InputDecoration(hintText: 'Email')),
        const SizedBox(height: 48),
        SizedBox(
          width: 190,
          child: FilledButton(
            onPressed: () => onNavigate(MotivaPage.forgotCode),
            child: const Text('ENVIAR EMAIL'),
          ),
        ),
        const SizedBox(height: 8),
        SizedBox(
          width: 190,
          child: OutlinedButton(
            onPressed: () => onNavigate(MotivaPage.login),
            child: const Text('VOLTAR'),
          ),
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
        const Text(
          'Digite o código enviado por email para acessar sua conta',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 12,
            height: 1.35,
            color: Color(0xFF625B65),
          ),
        ),
        const SizedBox(height: 28),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: List.generate(
            6,
            (index) => Container(
              width: 38,
              height: 52,
              margin: const EdgeInsets.symmetric(horizontal: 3),
              decoration: BoxDecoration(
                color: const Color(0xFFF9F9FA),
                border: Border.all(color: const Color(0xFF302C32)),
                borderRadius: BorderRadius.circular(8),
              ),
            ),
          ),
        ),
        const SizedBox(height: 54),
        SizedBox(
          width: 190,
          child: FilledButton(
            onPressed: () => onNavigate(MotivaPage.home),
            child: const Text('ENTRAR'),
          ),
        ),
      ],
    ),
  );
}

final class _AppHeader extends StatelessWidget {
  const _AppHeader();

  @override
  Widget build(BuildContext context) => Container(
    height: 62,
    padding: const EdgeInsets.fromLTRB(18, 15, 14, 9),
    decoration: const BoxDecoration(
      color: Color(0xFFFCF7FC),
      border: Border(bottom: BorderSide(color: Color(0xFFF1EAF1))),
    ),
    child: Row(
      children: const [
        _GreenVLogo(fontSize: 25),
        Spacer(),
      ],
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
  const _HomeScreen({required this.onNavigate});

  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => _MainScaffold(
    page: MotivaPage.home,
    onNavigate: onNavigate,
    body: SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'Olá, Nome',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 4),
          const Text(
            'Sua eficiência hoje está 12% acima da média semanal',
            style: TextStyle(
              fontSize: 11,
              height: 1.3,
              color: Color(0xFF6E6870),
            ),
          ),
          const SizedBox(height: 14),
          const _KilometerCard(),
          const SizedBox(height: 10),
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
          const SizedBox(height: 17),
          _SectionTitle(
            title: 'Visão Geral da Malha',
            icon: Icons.map_outlined,
            onTap: () => onNavigate(MotivaPage.network),
          ),
          const SizedBox(height: 8),
          GestureDetector(
            key: const Key('network-preview'),
            onTap: () => onNavigate(MotivaPage.network),
            child: const _RouteMap(height: 205, compact: true),
          ),
          const SizedBox(height: 17),
          const _SectionTitle(title: 'Últimos Uploads'),
          const SizedBox(height: 8),
          const _RecentUpload(
            name: 'Rodovia BR-101',
            status: 'Concluído',
            complete: true,
          ),
          const SizedBox(height: 8),
          const _RecentUpload(
            name: 'Av. das Américas',
            status: 'Processando',
          ),
          const SizedBox(height: 10),
          FilledButton.icon(
            onPressed: () => onNavigate(MotivaPage.upload),
            icon: const Icon(Icons.add_circle_outline, size: 18),
            label: const Text('INICIAR NOVA ANÁLISE'),
          ),
        ],
      ),
    ),
  );
}

final class _KilometerCard extends StatelessWidget {
  const _KilometerCard();

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.fromLTRB(14, 10, 14, 13),
    decoration: _cardDecoration(),
    child: const Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(
              'KM MONITORADOS',
              style: TextStyle(
                fontSize: 9,
                fontWeight: FontWeight.w700,
                color: Color(0xFF68616B),
              ),
            ),
            Spacer(),
            Icon(Icons.route_outlined, size: 18, color: motivaPurple),
          ],
        ),
        SizedBox(height: 2),
        Text.rich(
          TextSpan(
            children: [
              TextSpan(
                text: '1.240',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  color: motivaPurple,
                ),
              ),
              TextSpan(
                text: ' km',
                style: TextStyle(fontSize: 13, color: motivaPurple),
              ),
            ],
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
        ? const Color(0xFFFFDAD5)
        : green
        ? const Color(0xFF3F7850)
        : Colors.white;
    final foreground = green
        ? Colors.white
        : danger
        ? const Color(0xFFC72F2F)
        : motivaInk;
    return Container(
      height: 94,
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                label,
                style: TextStyle(
                  fontSize: 9,
                  fontWeight: FontWeight.w800,
                  color: foreground,
                ),
              ),
              const Spacer(),
              Icon(
                danger ? Icons.warning_rounded : Icons.groups_outlined,
                color: foreground,
                size: 19,
              ),
            ],
          ),
          const Spacer(),
          Text(
            value,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w800,
              color: foreground,
            ),
          ),
          Text(detail, style: TextStyle(fontSize: 10, color: foreground)),
        ],
      ),
    );
  }
}

final class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.title, this.icon, this.onTap});

  final String title;
  final IconData? icon;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: Row(
      children: [
        Expanded(
          child: Text(
            title,
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
          ),
        ),
        if (icon != null) Icon(icon, size: 18, color: motivaInk),
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
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
    decoration: _cardDecoration(radius: 12),
    child: Row(
      children: [
        Container(
          width: 32,
          height: 32,
          decoration: BoxDecoration(
            color: motivaLilac,
            borderRadius: BorderRadius.circular(9),
          ),
          child: Icon(
            complete
                ? Icons.inventory_2_outlined
                : Icons.cloud_upload_outlined,
            color: motivaPurple,
            size: 17,
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            name,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
          ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          decoration: BoxDecoration(
            color: complete
                ? const Color(0xFFD7F0DC)
                : const Color(0xFFF0EDF1),
            borderRadius: BorderRadius.circular(99),
          ),
          child: Text(
            status,
            style: TextStyle(
              fontSize: 9,
              fontWeight: FontWeight.w700,
              color: complete
                  ? const Color(0xFF27733B)
                  : const Color(0xFF777078),
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
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 20),
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
            const SizedBox(height: 17),
            const _SectionTitle(title: 'Imagens relacionadas ao vídeo'),
            const SizedBox(height: 8),
            const _AnalysisItem(
              id: 'IV-8839',
              title: 'Reconhecimento concluído',
              detail: 'Objetos identificados com 98% de confiança.',
              status: 'LEVEL 1',
              tone: Color(0xFF47A45B),
            ),
            const SizedBox(height: 10),
            const _AnalysisItem(
              id: 'IV-8830',
              title: 'Processando frames',
              detail: 'O cálculo poderá demorar alguns minutos.',
              status: 'LEVEL 2',
              tone: Color(0xFFD7A21C),
            ),
            const SizedBox(height: 10),
            const _AnalysisItem(
              id: 'IV-8831',
              title: 'Erro de leitura',
              detail: 'Formato de arquivo incompatível ou corrompido.',
              status: 'LEVEL 3',
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
      CustomPaint(
        foregroundPainter: _DashedBorderPainter(),
        child: Container(
          height: 275,
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 25),
          child: Column(
            children: [
              const CircleAvatar(
                radius: 34,
                backgroundColor: motivaLilac,
                child: Icon(
                  Icons.cloud_upload_outlined,
                  color: motivaPurple,
                  size: 34,
                ),
              ),
              const Spacer(),
              const Text(
                'Faça upload para\nreconhecimento',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 18,
                  height: 1.2,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 11),
              const Text(
                'Grave vídeos MP4 ou MOV para análise instantânea de fluxo e movimento.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 11,
                  height: 1.35,
                  color: Color(0xFF6F6872),
                ),
              ),
              const Spacer(),
              SizedBox(
                width: 205,
                child: _RecordControl(controller: controller),
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 12),
    ],
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
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 2),
            Text(
              'Segmento ${controller.segmentIndex + 1} sendo gravado',
              style: const TextStyle(
                fontSize: 11,
                color: Color(0xFF746D77),
              ),
            ),
          ],
        ),
      ),
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: const Color(0xFFFFE5E5),
          borderRadius: BorderRadius.circular(99),
        ),
        child: const Row(
          children: [
            Icon(
              Icons.fiber_manual_record,
              size: 10,
              color: Color(0xFFD8334A),
            ),
            SizedBox(width: 4),
            Text(
              'GRAVANDO',
              style: TextStyle(fontSize: 9, fontWeight: FontWeight.w800),
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
    borderRadius: BorderRadius.circular(16),
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
    padding: const EdgeInsets.all(11),
    decoration: _cardDecoration(radius: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 15, color: motivaPurple),
            const SizedBox(width: 5),
            Text(
              label,
              style: const TextStyle(
                fontSize: 8,
                letterSpacing: 0.7,
                fontWeight: FontWeight.w800,
                color: Color(0xFF817984),
              ),
            ),
          ],
        ),
        const SizedBox(height: 7),
        Text(
          value,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w800),
        ),
        Text(
          detail,
          style: const TextStyle(fontSize: 9, color: Color(0xFF88808A)),
        ),
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
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: motivaLilac,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          const CircleAvatar(
            radius: 16,
            backgroundColor: Colors.white,
            child: Icon(
              Icons.cloud_upload_outlined,
              size: 17,
              color: motivaPurple,
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
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                    color: motivaPurpleDark,
                  ),
                ),
                const Text(
                  'Arquivos locais saem só após a verificação do worker.',
                  style: TextStyle(
                    fontSize: 9,
                    color: Color(0xFF705B7F),
                  ),
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
    final busy = controller.phase == CapturePhase.preparing ||
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
        backgroundColor: recording ? const Color(0xFFB52D42) : motivaPurple,
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
            : 'Iniciar gravação',
      ),
    );
  }
}

final class _ErrorCard extends StatelessWidget {
  const _ErrorCard({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(11),
    decoration: BoxDecoration(
      color: const Color(0xFFFFE7E7),
      borderRadius: BorderRadius.circular(11),
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
            style: const TextStyle(fontSize: 10),
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
    height: 88,
    padding: const EdgeInsets.all(8),
    decoration: _cardDecoration(radius: 11),
    child: Row(
      children: [
        Container(
          width: 78,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: [
                tone.withValues(alpha: 0.30),
                tone.withValues(alpha: 0.85),
              ],
            ),
            borderRadius: BorderRadius.circular(8),
          ),
          child: const Center(
            child: Icon(
              Icons.landscape_outlined,
              color: Colors.white,
              size: 29,
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'ID: $id',
                      style: const TextStyle(
                        fontSize: 8,
                        color: Color(0xFF69626B),
                      ),
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 6,
                      vertical: 3,
                    ),
                    decoration: BoxDecoration(
                      color: tone.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(99),
                    ),
                    child: Text(
                      status,
                      style: TextStyle(
                        fontSize: 7,
                        color: tone,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 3),
              Text(
                title,
                style: const TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                detail,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 8,
                  height: 1.2,
                  color: Color(0xFF777079),
                ),
              ),
            ],
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
        const Positioned.fill(
          child: _RouteMap(height: double.infinity),
        ),
        Positioned(
          left: 16,
          right: 16,
          top: 12,
          child: _SearchBar(onMap: () => onNavigate(MotivaPage.map)),
        ),
        Positioned(
          left: 16,
          right: 16,
          bottom: 14,
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
    height: 42,
    padding: const EdgeInsets.symmetric(horizontal: 12),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(99),
      boxShadow: const [
        BoxShadow(color: Color(0x18000000), blurRadius: 8),
      ],
    ),
    child: Row(
      children: [
        const Icon(Icons.search, size: 18, color: Color(0xFF777078)),
        const SizedBox(width: 7),
        const Expanded(
          child: Text(
            'Search route or area...',
            style: TextStyle(fontSize: 10, color: Color(0xFF8A838C)),
          ),
        ),
        IconButton(
          onPressed: onMap,
          icon: const Icon(
            Icons.mic_none_rounded,
            color: motivaPurple,
            size: 18,
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
    padding: const EdgeInsets.fromLTRB(14, 10, 14, 14),
    decoration: BoxDecoration(
      color: const Color(0xFFFBF8FC),
      borderRadius: BorderRadius.circular(20),
      boxShadow: const [
        BoxShadow(color: Color(0x24000000), blurRadius: 16),
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
              color: const Color(0xFFD1C8D6),
              borderRadius: BorderRadius.circular(99),
            ),
          ),
        ),
        const SizedBox(height: 10),
        GestureDetector(
          onTap: onMap,
          child: Container(
            height: 62,
            padding: const EdgeInsets.symmetric(horizontal: 13),
            decoration: BoxDecoration(
              color: motivaPurple,
              borderRadius: BorderRadius.circular(12),
            ),
            child: const Row(
              children: [
                CircleAvatar(
                  radius: 18,
                  backgroundColor: Color(0xFF7E48F7),
                  child: Icon(Icons.route, color: Colors.white, size: 18),
                ),
                SizedBox(width: 10),
                Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'MOTIVA',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    Text(
                      'GREEN V',
                      style: TextStyle(
                        color: Colors.white70,
                        fontSize: 8,
                        letterSpacing: 1.2,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        const Text(
          'RESUMO DA MALHA',
          style: TextStyle(
            fontSize: 8,
            fontWeight: FontWeight.w700,
            color: Color(0xFF736C75),
          ),
        ),
        const SizedBox(height: 7),
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
        const Divider(height: 22),
        const Text(
          'NÍVEL DE VEGETAÇÃO (MÉDIO)',
          style: TextStyle(fontSize: 8, color: Color(0xFF756E77)),
        ),
        const SizedBox(height: 4),
        const Text.rich(
          TextSpan(
            children: [
              TextSpan(
                text: '2,6 m',
                style: TextStyle(
                  fontSize: 21,
                  fontWeight: FontWeight.w800,
                  color: Color(0xFF35723F),
                ),
              ),
              TextSpan(
                text: '  Médio',
                style: TextStyle(fontSize: 9, color: Color(0xFFD9871E)),
              ),
            ],
          ),
        ),
        const Divider(height: 22),
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
        const SizedBox(height: 12),
        const Row(
          children: [
            _LegendDot(color: Color(0xFF46A65A), label: 'Baixo'),
            SizedBox(width: 16),
            _LegendDot(color: Color(0xFFE3A622), label: 'Médio'),
            SizedBox(width: 16),
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
    padding: const EdgeInsets.only(left: 7),
    decoration: BoxDecoration(
      border: Border(left: BorderSide(color: color, width: 2)),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          value,
          style: TextStyle(
            color: color,
            fontSize: 13,
            fontWeight: FontWeight.w800,
          ),
        ),
        Text(
          label,
          style: const TextStyle(fontSize: 8, color: Color(0xFF69626B)),
        ),
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
      Text(label, style: const TextStyle(fontSize: 8)),
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
          left: 16,
          right: 16,
          top: 12,
          child: _SearchBar(onMap: _noop),
        ),
        Positioned(
          right: 14,
          top: 67,
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
      ],
    ),
  );

  static void _noop() {}
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
        width: 36,
        height: 36,
        child: Icon(
          icon,
          size: 19,
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
  const _BottomNavigation({
    required this.selected,
    required this.onNavigate,
  });

  final MotivaPage selected;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => Container(
    decoration: const BoxDecoration(
      color: Color(0xFFFFFAFF),
      border: Border(top: BorderSide(color: Color(0xFFECE3EE))),
    ),
    padding: const EdgeInsets.fromLTRB(18, 7, 18, 8),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.spaceAround,
      children: [
        _NavigationItem(
          icon: Icons.cloud_upload_outlined,
          label: 'Upload',
          selected: selected == MotivaPage.upload,
          onTap: () => onNavigate(MotivaPage.upload),
        ),
        _NavigationItem(
          icon: Icons.home_outlined,
          label: 'Home',
          selected: selected == MotivaPage.home,
          onTap: () => onNavigate(MotivaPage.home),
        ),
        _NavigationItem(
          icon: Icons.map_outlined,
          label: 'Mapa',
          selected:
              selected == MotivaPage.map || selected == MotivaPage.network,
          onTap: () => onNavigate(MotivaPage.map),
        ),
      ],
    ),
  );
}

final class _NavigationItem extends StatelessWidget {
  const _NavigationItem({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    key: Key('nav-${label.toLowerCase()}'),
    onTap: onTap,
    borderRadius: BorderRadius.circular(14),
    child: AnimatedContainer(
      duration: const Duration(milliseconds: 150),
      width: 64,
      padding: const EdgeInsets.symmetric(vertical: 5),
      decoration: BoxDecoration(
        color: selected ? motivaPurple : Colors.transparent,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            icon,
            size: 18,
            color: selected ? Colors.white : const Color(0xFF7D7580),
          ),
          const SizedBox(height: 1),
          Text(
            label,
            style: TextStyle(
              fontSize: 8,
              fontWeight: selected ? FontWeight.w800 : FontWeight.w500,
              color: selected ? Colors.white : const Color(0xFF716A74),
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
  const _GreenVLogo({this.fontSize = 21});

  final double fontSize;

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
                color: const Color(0xFF075D37),
                fontSize: fontSize,
                fontFamily: 'serif',
                fontWeight: FontWeight.w600,
              ),
            ),
            TextSpan(
              text: 'V',
              style: TextStyle(
                color: const Color(0xFF5E9A38),
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
    final purple = Paint()
      ..color = const Color(0xFF7B47F7)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.1;
    final green = Paint()
      ..color = const Color(0xFF8AB64B)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.1;
    const cell = 57.0;
    for (var y = -cell; y < size.height + cell; y += cell) {
      for (var x = -cell; x < size.width + cell; x += cell) {
        final paint = y < size.height * .52 ? purple : green;
        final path = Path()
          ..moveTo(x, y + cell * .62)
          ..cubicTo(
            x + cell * .18,
            y + cell * .62,
            x + cell * .18,
            y,
            x + cell * .62,
            y,
          )
          ..lineTo(x + cell, y);
        canvas.drawPath(path, paint);
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

final class _DashedBorderPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    const radius = Radius.circular(15);
    final path = Path()
      ..addRRect(RRect.fromRectAndRadius(Offset.zero & size, radius));
    final paint = Paint()
      ..color = const Color(0xFFBDA2F9)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4;
    for (final metric in path.computeMetrics()) {
      var distance = 0.0;
      while (distance < metric.length) {
        canvas.drawPath(
          metric.extractPath(distance, math.min(distance + 6, metric.length)),
          paint,
        );
        distance += 11;
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

BoxDecoration _cardDecoration({double radius = 15}) => BoxDecoration(
  color: Colors.white,
  borderRadius: BorderRadius.circular(radius),
  border: Border.all(color: motivaLine),
  boxShadow: const [
    BoxShadow(
      color: Color(0x09000000),
      blurRadius: 6,
      offset: Offset(0, 2),
    ),
  ],
);
