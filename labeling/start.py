#!/usr/bin/env python3
"""
MOTIVA — Ferramenta de rotulagem de vegetação rodoviária.

Comandos (rodar em terminais separados ou sequencialmente):

    # Terminal 1: inicia o servidor
    python labeling/start.py serve

    # Terminal 2 (após criar conta no browser): importa todas as imagens
    python labeling/start.py import --token <SEU_TOKEN>

    # Depois de rotular: exporta
    python labeling/start.py export --token <SEU_TOKEN> --format csv

Como pegar o token:
    1. Abra http://localhost:8080
    2. Crie conta / faça login
    3. Vá em Account & Settings (canto superior direito)
    4. Copie o "Access Token"
"""

import argparse
import json
import subprocess
import sys
import os
import time
import webbrowser
from pathlib import Path

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

LABEL_CONFIG = """\
<View>
  <Header value="Classifique a altura da vegetação lateral à rodovia"/>
  <Header value="[1] Rasteira (&lt;10cm)  [2] Média (10-30cm)  [3] Alta/Poda (&gt;30cm)  [4] N/A" style="color: #666; font-size: 0.9em"/>
  <Image name="image" value="$image" zoom="true" zoomControl="true"/>
  <Choices name="height_class" toName="image" choice="single" showInline="true">
    <Choice value="1_rasteira" alias="1" hotkey="1" hint="h &lt; 10cm — grama rente ao chão, sem volume" style="background: #4CAF50"/>
    <Choice value="2_media" alias="2" hotkey="2" hint="10cm ≤ h ≤ 30cm — acima do chão, abaixo do guard-rail" style="background: #FF9800"/>
    <Choice value="3_alta_poda" alias="3" hotkey="3" hint="h &gt; 30cm — invade faixa, cobre sinalização, precisa poda" style="background: #F44336"/>
    <Choice value="null_NA" alias="null" hotkey="4" hint="Sem vegetação, obstrução, blur, ou imagem inutilizável" style="background: #9E9E9E"/>
  </Choices>
</View>
"""

PROJECT_TITLE = "MOTIVA Vegetação"
PROJECT_DESCRIPTION = (
    "Classificação de altura de vegetação em beira de rodovia. "
    "Classes: 1 (< 10cm), 2 (10-30cm), 3 (> 30cm), null (N/A)."
)

DEFAULT_PORT = 8080
DATA_DIR = Path(__file__).resolve().parent.parent / "dataset"
EXPORT_DIR = Path(__file__).resolve().parent / "exports"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def resolve_access_token(port, token):
    """Troca refresh token por access token (Label Studio 1.23+)."""
    import urllib.request
    import urllib.error

    # Se não parece JWT, assume legacy token
    if not token.startswith("eyJ"):
        return token

    url = f"http://localhost:{port}/api/token/refresh"
    body = json.dumps({"refresh": token}).encode()
    headers = {"Content-Type": "application/json"}
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")

    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
            print("[ok] Access token obtido via refresh")
            return data["access"]
    except urllib.error.HTTPError:
        # Pode já ser um access token, retorna como está
        return token


def api_request(port, token, method, endpoint, data=None):
    """Faz requisição à API do Label Studio."""
    import urllib.request
    import urllib.error

    url = f"http://localhost:{port}/api/{endpoint}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    body = json.dumps(data).encode() if data else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read()) if resp.status != 204 else {}
    except urllib.error.HTTPError as e:
        error_body = e.read().decode()
        print(f"[!] API error {e.code}: {error_body}")
        raise


def find_or_create_project(port, token):
    """Encontra o projeto MOTIVA ou cria um novo."""
    projects = api_request(port, token, "GET", "projects")
    results = projects.get("results", [])

    for p in results:
        if p["title"] == PROJECT_TITLE:
            print(f"[ok] Projeto existente encontrado: id={p['id']}")
            return p["id"]

    # Cria novo
    data = {
        "title": PROJECT_TITLE,
        "description": PROJECT_DESCRIPTION,
        "label_config": LABEL_CONFIG,
    }
    result = api_request(port, token, "POST", "projects", data)
    print(f"[ok] Projeto criado: id={result['id']}")
    return result["id"]


# ---------------------------------------------------------------------------
# Comandos
# ---------------------------------------------------------------------------

def cmd_serve(args):
    """Inicia o Label Studio + servidor estático de imagens."""
    # Verifica instalação
    try:
        import label_studio  # noqa: F401
    except ImportError:
        print("[!] label-studio não está instalado.")
        print("    Rode: pip install -r labeling/requirements.txt")
        sys.exit(1)

    images_path = Path(args.images).resolve()
    if not images_path.exists():
        print(f"[!] Pasta de imagens não encontrada: {images_path}")
        print(f"    Rode data-acquisition/ antes, ou passe --images <pasta>")
        sys.exit(1)
    images_dir = str(images_path)
    img_port = args.port + 1  # imagens numa porta ao lado (ex: 8081)

    env = os.environ.copy()
    env["LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED"] = "true"
    env["LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT"] = images_dir

    print(f"""
{'='*60}
  Label Studio:     http://localhost:{args.port}
  Servidor imagens: http://localhost:{img_port}  ({images_dir})
{'='*60}

  1. Crie uma conta no browser (local, nada é enviado)
  2. Copie seu token em: Account & Settings > Access Token
  3. Em OUTRO terminal, rode:

     python labeling/start.py import --token <TOKEN>

  Ctrl+C para parar ambos os servidores.
{'='*60}
""")

    import threading
    from http.server import SimpleHTTPRequestHandler
    from functools import partial
    import socketserver

    # Servidor HTTP estático para as imagens (com CORS aberto)
    class CORSHandler(SimpleHTTPRequestHandler):
        def end_headers(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            super().end_headers()

        def log_message(self, format, *a):
            pass  # silencioso

    handler = partial(CORSHandler, directory=images_dir)
    socketserver.TCPServer.allow_reuse_address = True
    httpd = socketserver.TCPServer(("", img_port), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print(f"[ok] Servidor de imagens rodando em :{img_port}")

    # Abre browser
    threading.Thread(
        target=lambda: (time.sleep(4), webbrowser.open(f"http://localhost:{args.port}")),
        daemon=True,
    ).start()

    try:
        subprocess.run(
            ["label-studio", "start", "--port", str(args.port), "--no-browser"],
            env=env,
        )
    except KeyboardInterrupt:
        httpd.shutdown()
        print("\n[ok] Encerrado.")


def cmd_import(args):
    """Importa imagens do dataset no projeto via API."""
    args.token = resolve_access_token(args.port, args.token)

    images_dir = Path(args.images).resolve()

    if not images_dir.exists():
        print(f"[!] Pasta não encontrada: {images_dir}")
        sys.exit(1)

    extensions = {".jpg", ".jpeg", ".png", ".webp"}
    image_files = sorted(
        f for f in images_dir.rglob("*") if f.suffix.lower() in extensions
    )

    if not image_files:
        print(f"[!] Nenhuma imagem em {images_dir}")
        sys.exit(1)

    print(f"[...] Encontradas {len(image_files)} imagens")

    # Encontra ou cria projeto
    project_id = find_or_create_project(args.port, args.token)

    # Evita duplicar se o projeto já tem tasks
    project_info = api_request(args.port, args.token, "GET", f"projects/{project_id}")
    existing = project_info.get("task_number", 0)
    if existing > 0 and not args.force:
        print(f"[!] Projeto já tem {existing} tasks. Import duplicaria tudo.")
        print(f"    Use --force para importar mesmo assim.")
        sys.exit(1)

    # Monta tasks com URLs apontando para o servidor estático de imagens (porta+1)
    img_port = args.port + 1
    tasks = [
        {"image": f"http://localhost:{img_port}/{img.relative_to(images_dir)}"}
        for img in image_files
    ]

    # Importa em batches de 500 
    batch_size = 500
    total_imported = 0

    for i in range(0, len(tasks), batch_size):
        batch = tasks[i : i + batch_size]
        result = api_request(
            args.port, args.token, "POST",
            f"projects/{project_id}/import", batch
        )
        count = result.get("task_count", len(batch))
        total_imported += count
        print(f"  [...] {total_imported}/{len(tasks)} importadas")

    print(f"\n[ok] {total_imported} imagens importadas no projeto '{PROJECT_TITLE}'")
    print(f"     Abra http://localhost:{args.port}/projects/{project_id} e comece a rotular!")


def cmd_export(args):
    """Exporta anotações do projeto."""
    args.token = resolve_access_token(args.port, args.token)
    projects = api_request(args.port, args.token, "GET", "projects")
    results = projects.get("results", [])
    project = next((p for p in results if p["title"] == PROJECT_TITLE), None)

    if not project:
        print(f"[!] Projeto '{PROJECT_TITLE}' não encontrado")
        sys.exit(1)

    project_id = project["id"]
    fmt = "CSV" if args.format == "csv" else "JSON"

    # Usa urllib diretamente pois o export retorna arquivo binário
    import urllib.request
    url = f"http://localhost:{args.port}/api/projects/{project_id}/export?exportType={fmt}"
    headers = {"Authorization": f"Bearer {args.token}"}
    req = urllib.request.Request(url, headers=headers)

    EXPORT_DIR.mkdir(exist_ok=True)
    out_file = EXPORT_DIR / f"labels.{args.format}"

    with urllib.request.urlopen(req) as resp:
        out_file.write_bytes(resp.read())

    print(f"[ok] Exportado: {out_file}")
    print(f"     → Use este arquivo no model-training/ para treinar o classificador.")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Rotulagem MOTIVA — Label Studio para classificação de vegetação.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--port", type=int, default=DEFAULT_PORT,
        help=f"Porta do Label Studio (default: {DEFAULT_PORT})",
    )
    parser.add_argument(
        "--images", type=str, default=str(DATA_DIR),
        help=f"Pasta com imagens (default: {DATA_DIR})",
    )

    sub = parser.add_subparsers(dest="command")

    # serve
    serve_p = sub.add_parser("serve", help="Inicia o servidor Label Studio")
    serve_p.set_defaults(func=cmd_serve)

    # import
    import_p = sub.add_parser("import", help="Importa imagens no projeto")
    import_p.add_argument("--token", required=True, help="Access Token do Label Studio")
    import_p.add_argument("--force", action="store_true", help="Importa mesmo se projeto já tem tasks")
    import_p.set_defaults(func=cmd_import)

    # export
    export_p = sub.add_parser("export", help="Exporta anotações")
    export_p.add_argument("--token", required=True, help="Access Token do Label Studio")
    export_p.add_argument("--format", choices=["csv", "json"], default="csv")
    export_p.set_defaults(func=cmd_export)

    args = parser.parse_args()

    if not args.command:
        # Sem subcomando → serve (comportamento anterior)
        args.func = cmd_serve
        cmd_serve(args)
    else:
        args.func(args)


if __name__ == "__main__":
    main()
