import 'package:flutter/material.dart';

/// The frame every screen sits in: colours, the phone-width scaffold, header, navigation, and the
/// cards more than one screen draws. Public so the capture screens and the operations screens
/// share one look without one file holding both.

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
  stretches,
  map,
  orders,
  teams,
}

BoxDecoration cardDecoration({double radius = 15}) => BoxDecoration(
  color: Colors.white,
  borderRadius: BorderRadius.circular(radius),
  border: Border.all(color: motivaLine),
  boxShadow: const [
    BoxShadow(color: Color(0x0D123E31), blurRadius: 14, offset: Offset(0, 6)),
  ],
);

final class PhoneFrame extends StatelessWidget {
  const PhoneFrame({
    required this.child,
    this.background = motivaCanvas,
    super.key,
  });

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

final class AppHeader extends StatelessWidget {
  const AppHeader({super.key});

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
        GreenVLogo(fontSize: 26),
        SizedBox(width: 10),
        EnvironmentBadge(),
        Spacer(),
        HeaderAction(
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

final class EnvironmentBadge extends StatelessWidget {
  const EnvironmentBadge({super.key});

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

final class HeaderAction extends StatelessWidget {
  const HeaderAction({
    required this.icon,
    required this.semanticsLabel,
    super.key,
  });

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

final class MainScaffold extends StatelessWidget {
  const MainScaffold({
    required this.page,
    required this.body,
    required this.onNavigate,
    super.key,
  });

  final MotivaPage page;
  final Widget body;
  final ValueChanged<MotivaPage> onNavigate;

  @override
  Widget build(BuildContext context) => PhoneFrame(
    child: Column(
      children: [
        const AppHeader(),
        Expanded(child: body),
        BottomNavigation(selected: page, onNavigate: onNavigate),
      ],
    ),
  );
}

final class HomeCaptureCard extends StatelessWidget {
  const HomeCaptureCard({required this.onTap, super.key});

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
            child: CustomPaint(painter: FieldRoutePainter()),
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

final class MetricCard extends StatelessWidget {
  const MetricCard({
    required this.label,
    required this.value,
    required this.detail,
    this.danger = false,
    this.green = false,
    super.key,
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
      height: 112,
      padding: const EdgeInsets.all(14),
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
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 10,
                    letterSpacing: 0.6,
                    fontWeight: FontWeight.w800,
                    color: foreground,
                  ),
                ),
              ),
              const SizedBox(width: 6),
              Icon(
                danger ? Icons.warning_rounded : Icons.groups_outlined,
                color: foreground,
                size: 20,
              ),
            ],
          ),
          const Spacer(),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(
              value,
              style: TextStyle(
                fontSize: 23,
                fontWeight: FontWeight.w900,
                color: foreground,
              ),
            ),
          ),
          Text(
            detail,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 11, color: foreground),
          ),
        ],
      ),
    );
  }
}

final class SectionTitle extends StatelessWidget {
  const SectionTitle({required this.title, this.action, this.onTap, super.key});

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

final class RecentUpload extends StatelessWidget {
  const RecentUpload({
    required this.name,
    required this.status,
    this.complete = false,
    super.key,
  });

  final String name;
  final String status;
  final bool complete;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
    decoration: cardDecoration(radius: 16),
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

final class NavigationItem extends StatelessWidget {
  const NavigationItem({
    required this.keyName,
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
    super.key,
  });

  final String keyName;
  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Expanded(
    child: InkWell(
      key: Key(keyName),
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
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
    ),
  );
}

final class GreenVLogo extends StatelessWidget {
  const GreenVLogo({this.fontSize = 21, this.light = false, super.key});

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

final class FieldRoutePainter extends CustomPainter {
  const FieldRoutePainter();

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

/// The five places a signed-in person can be. Teams sit behind the orders screen rather than in
/// the bar: five tabs is the most a phone this wide fits without the labels wrapping.
final class BottomNavigation extends StatelessWidget {
  const BottomNavigation({
    required this.selected,
    required this.onNavigate,
    super.key,
  });

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
        padding: const EdgeInsets.fromLTRB(8, 9, 8, 10),
        child: Row(
          children: [
            NavigationItem(
              keyName: 'nav-home',
              icon: Icons.home_rounded,
              label: 'Início',
              selected: selected == MotivaPage.home,
              onTap: () => onNavigate(MotivaPage.home),
            ),
            NavigationItem(
              keyName: 'nav-upload',
              icon: Icons.videocam_rounded,
              label: 'Coleta',
              selected: selected == MotivaPage.upload,
              onTap: () => onNavigate(MotivaPage.upload),
            ),
            NavigationItem(
              keyName: 'nav-trechos',
              icon: Icons.straighten_rounded,
              label: 'Trechos',
              selected: selected == MotivaPage.stretches,
              onTap: () => onNavigate(MotivaPage.stretches),
            ),
            NavigationItem(
              keyName: 'nav-mapa',
              icon: Icons.map_rounded,
              label: 'Mapa',
              selected: selected == MotivaPage.map,
              onTap: () => onNavigate(MotivaPage.map),
            ),
            NavigationItem(
              keyName: 'nav-ordens',
              icon: Icons.assignment_rounded,
              label: 'Ordens',
              selected:
                  selected == MotivaPage.orders || selected == MotivaPage.teams,
              onTap: () => onNavigate(MotivaPage.orders),
            ),
          ],
        ),
      ),
    ),
  );
}
