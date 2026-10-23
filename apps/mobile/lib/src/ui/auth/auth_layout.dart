import 'package:flutter/material.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class AuthPattern extends StatelessWidget {
  const AuthPattern({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) => PhoneFrame(
    background: motivaCanvas,
    child: Stack(
      fit: StackFit.expand,
      children: [
        // O mesmo padrão de ondas que o aplicativo de campo põe atrás do login, alinhado ao topo
        // e cortado embaixo: o desenho vai do roxo ao verde de cima para baixo, e centralizá-lo
        // deixaria de fora justamente essa passagem.
        Image.asset(
          'assets/brand/auth-backdrop.png',
          fit: BoxFit.cover,
          alignment: Alignment.topCenter,
        ),
        const ColoredBox(color: Color(0x8CF3F6F2)),
        // O cartão flutua no meio do padrão em vez de ocupar a tela, que é como o aplicativo de
        // campo o desenha. A rolagem é daqui e não de dentro do cartão: assim ele encolhe até o
        // tamanho do conteúdo e, quando o teclado sobe e o que sobra não cabe, a tela rola.
        LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            // Dragging the screen also puts the keyboard away.
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            padding: const EdgeInsets.fromLTRB(20, 32, 20, 24),
            // O mínimo é a altura da moldura, e não um número: é ele que faz o `Center` centrar
            // de verdade em vez de empurrar o cartão para o topo de uma caixa baixa demais.
            child: ConstrainedBox(
              constraints: BoxConstraints(minHeight: constraints.maxHeight - 56),
              child: Center(child: child),
            ),
          ),
        ),
      ],
    ),
  );
}

final class AuthCard extends StatelessWidget {
  const AuthCard({required this.children, super.key});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Container(
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(28),
      border: Border.all(color: const Color(0xAFFFFFFF)),
      boxShadow: const [BoxShadow(color: Color(0x180B2E23), blurRadius: 32, offset: Offset(0, 14))],
    ),
    padding: const EdgeInsets.fromLTRB(26, 30, 26, 26),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            MotivaLogo(color: motivaPurple),
            SizedBox(height: 8),
            GreenVLogo(height: 30),
          ],
        ),
        const SizedBox(height: 28),
        ...children,
      ],
    ),
  );
}

/// A marca da Motiva, o arquivo oficial do `sprint-cross-plataform`.
///
/// Havia aqui um pintor que desenhava quatro barras inclinadas e escrevia "motiva" ao lado numa
/// fonte qualquer. O arquivo roxo é o único horizontal, então a versão branca sai dele por
/// mistura de cor, e a empilhada — que só a abertura usa — é um arquivo à parte porque tem outra
/// proporção.
final class MotivaLogo extends StatelessWidget {
  const MotivaLogo({required this.color, this.large = false, super.key});

  final Color color;
  final bool large;

  @override
  Widget build(BuildContext context) {
    final branca = color == Colors.white;
    return Semantics(
      label: 'motiva',
      child: ExcludeSemantics(
        child: large && branca
            ? Image.asset('assets/brand/motiva-mark-white.png', width: 132, fit: BoxFit.contain)
            : Image.asset(
                'assets/brand/motiva-wordmark.png',
                height: large ? 46 : 26,
                fit: BoxFit.contain,
                color: branca ? Colors.white : null,
                colorBlendMode: branca ? BlendMode.srcIn : null,
              ),
      ),
    );
  }
}
