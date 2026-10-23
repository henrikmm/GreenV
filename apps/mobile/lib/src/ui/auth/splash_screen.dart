import 'package:flutter/material.dart';
import 'package:greenv_capture/src/ui/auth/auth_layout.dart';
import 'package:greenv_capture/src/ui/shell.dart';

final class SplashScreen extends StatelessWidget {
  const SplashScreen({super.key});

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
            decoration: const BoxDecoration(color: Color(0x286546D7), shape: BoxShape.circle),
          ),
        ),
        Positioned(
          bottom: -150,
          left: -100,
          child: Container(
            width: 330,
            height: 330,
            decoration: const BoxDecoration(color: Color(0x1F58B584), shape: BoxShape.circle),
          ),
        ),
        const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              MotivaLogo(color: Colors.white, large: true),
              SizedBox(height: 22),
              SizedBox(width: 44, child: Divider(color: Color(0x66FFFFFF))),
              SizedBox(height: 16),
              GreenVLogo(height: 30, light: true),
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
