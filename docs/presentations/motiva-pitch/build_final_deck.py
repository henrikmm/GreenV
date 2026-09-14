"""Build the final Motiva pitch deck from the evidence deck of 14 September 2026.

The evidence deck stays untouched: this reads it, applies the review of 14 September and
writes a separate file beside it. Every number placed on a slide is either recomputed here
from its source (the tape-graded trials) or carries its source in the speaker notes.

    .venv/bin/python docs/presentations/motiva-pitch/build_final_deck.py

Inputs outside the repository, all local:
  output/motiva-pitch/GreenV-Motiva-Pitch-Evidencias.pptx   the deck being revised
  ~/Downloads/drive-download-20260911T203554Z-1-001/projecao_2d3d.gif
  ~/Desktop/carro_fiscalizacao = exemplo de setup de camera.jpg
  ~/verge-runs/*/measurements/*.json                          roadside trials
  ~/dev/verge-studio/.inspect/evidence/SUMMARY.md             the 26 replayed trials

Icons are Lucide (ISC licence), rendered to PNG in icons/.
"""

from __future__ import annotations

import copy
import glob
import json
import os
import statistics
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

from lxml import etree as E

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
HOME = Path.home()

SOURCE = REPO / "output/motiva-pitch/GreenV-Motiva-Pitch-Evidencias.pptx"
TARGET = REPO / "output/motiva-pitch/GreenV-Motiva-Pitch-Final.pptx"
FRUSTUM_GIF = Path(os.environ.get(
    "FRUSTUM_GIF", HOME / "Downloads/drive-download-20260911T203554Z-1-001/projecao_2d3d.gif"))
CAR_PHOTO = Path(os.environ.get(
    "CAR_PHOTO", HOME / "Desktop/carro_fiscalizacao = exemplo de setup de camera.jpg"))
VERGE_RUNS = Path(os.environ.get("VERGE_RUNS", HOME / "verge-runs"))
EVIDENCE_SUMMARY = Path(os.environ.get(
    "EVIDENCE_SUMMARY", HOME / "dev/verge-studio/.inspect/evidence/SUMMARY.md"))
# Written by render_automatic_measurement.py from the packet assess-grass.mjs produced.
AUTOMATIC_VIDEO = REPO / "output/motiva-pitch/Medicao-Automatica.mp4"
AUTOMATIC_POSTER = REPO / "output/motiva-pitch/Medicao-Automatica-capa.png"
AUTOMATIC_PACKET = REPO / "output/motiva-pitch/medicao-automatica-pacote/assessment.json"

A = "http://schemas.openxmlformats.org/drawingml/2006/main"
P = "http://schemas.openxmlformats.org/presentationml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
CT = "http://schemas.openxmlformats.org/package/2006/content-types"
NS = {"a": A, "p": P, "r": R, "c": C}
REL_IMAGE = R + "/image"
REL_SLIDE = R + "/slide"
REL_LAYOUT = R + "/slideLayout"
REL_NOTES = R + "/notesSlide"
REL_NOTES_MASTER = R + "/notesMaster"

# The deck is 1600 x 900 units wide; one unit is 9525 EMU, and a font size in units is 0.75 pt.
EMU = 9525

GREEN, GRAY, BG, LIME, LIGHT = "123F32", "53685E", "F4F7F3", "74AE35", "9EC46B"
LINE, TINT, WHITE = "DDE5DF", "E6EFE4", "FFFFFF"
# Sampled from the GIF's own background, so the animation has no visible edge on its slide.
DARK, DARK_TEXT, DARK_MUTED = "0D0C12", "F4F7F3", "8FA89A"


# ---------------------------------------------------------------------------- evidence


def graded_trials() -> list[dict]:
    """Every tape-graded trial on record, once each.

    The 26 replayed trials come from Verge Studio's evidence summary. The roadside trials
    of 14 September exist only as saved packets. RoomNewFixture also has saved packets, but
    they are the same nine trials the summary already replays, so they are skipped.
    """
    environment = {
        "door-leaf": "interior", "table-top": "interior", "pc-tower": "interior",
        "pc-tower-i7b4": "interior", "table-wb5o": "interior", "monitor-57db": "interior",
        "grass-nvfl": "jardim", "plant-o3wb": "jardim",
        "grass-exe1-wjhp": "jardim", "garden-light-a07f": "jardim",
    }
    clip = {
        "door-leaf": "door", "table-top": "door", "pc-tower": "door",
        "pc-tower-i7b4": "RoomNewFixture", "table-wb5o": "RoomNewFixture",
        "monitor-57db": "RoomNewFixture", "grass-nvfl": "Test_Grass", "plant-o3wb": "Test_Grass",
        "grass-exe1-wjhp": "Test_Grass2", "garden-light-a07f": "Test_Grass2",
    }
    table = EVIDENCE_SUMMARY.read_text().split("## Every trial")[1].split("## Images")[0]
    trials = []
    for line in table.splitlines():
        if not line.startswith("| `"):
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        key = cells[0].strip("`").split("#")[0]
        trials.append({
            "environment": environment[key], "clip": clip[key], "target": cells[1],
            "readingM": float(cells[3].split()[0]), "truthM": float(cells[4].split()[0]),
        })
    replayed_room = sorted(round(t["readingM"], 4) for t in trials if t["clip"] == "RoomNewFixture")
    for packet in sorted(glob.glob(str(VERGE_RUNS / "*/measurements/*.json"))):
        data = json.loads(Path(packet).read_text())
        clip_name = data["run"]["clipName"]
        reading = data["observation"]["rawM"]
        if clip_name == "RoomNewFixture.mp4":
            assert round(reading, 4) in replayed_room, f"unreplayed room trial {packet}"
            continue
        assert clip_name.startswith("rodovia_"), f"unexpected saved trial {packet}"
        trials.append({
            "environment": "rodovia", "clip": clip_name, "target": data["target"]["name"],
            "readingM": reading, "truthM": data["target"]["truthM"],
        })
    return trials


def accuracy() -> dict:
    trials = graded_trials()

    def summary(rows):
        errors = [abs(r["readingM"] - r["truthM"]) * 100 for r in rows]
        return {
            "trials": len(rows),
            "targets": len({(r["clip"], r["target"]) for r in rows}),
            "clips": len({r["clip"] for r in rows}),
            "maeCm": statistics.mean(errors),
            "maxCm": max(errors),
        }

    result = {"all": summary(trials)}
    for env in ("rodovia", "jardim", "interior"):
        result[env] = summary([t for t in trials if t["environment"] == env])
    result["withoutDoor"] = summary([t for t in trials if t["clip"] != "door"])
    # The slides print these rounded; fail loudly if the evidence ever stops matching them.
    expected = {"all": (32, 12, 6, "2,11"), "rodovia": (6, 2, 2, "0,91"),
                "jardim": (8, 4, 2, "1,55"), "interior": (18, 6, 2, "2,76")}
    for key, (trials_n, targets_n, clips_n, mae) in expected.items():
        got = result[key]
        assert (got["trials"], got["targets"], got["clips"]) == (trials_n, targets_n, clips_n), (key, got)
        assert cm(got["maeCm"]) == mae, (key, got)
    return result


def cm(value: float, digits: int = 2) -> str:
    return f"{value:.{digits}f}".replace(".", ",")


# ---------------------------------------------------------------------------- package


class Package:
    def __init__(self, path: Path):
        with zipfile.ZipFile(path) as archive:
            self.files = {name: archive.read(name) for name in archive.namelist()}

    def xml(self, name: str) -> E._Element:
        return E.fromstring(self.files[name])

    def put(self, name: str, root: E._Element) -> None:
        self.files[name] = E.tostring(root, xml_declaration=True, encoding="UTF-8", standalone=True)

    def rels_name(self, part: str) -> str:
        folder, file = part.rsplit("/", 1)
        return f"{folder}/_rels/{file}.rels"

    def add_rel(self, part: str, rel_type: str, target: str, rel_id: str) -> str:
        name = self.rels_name(part)
        rels = self.xml(name)
        for rel in rels:
            if rel.get("Id") == rel_id:
                rels.remove(rel)
        E.SubElement(rels, f"{{{PKG_REL}}}Relationship", Id=rel_id, Type=rel_type, Target=target)
        self.put(name, rels)
        return rel_id

    def add_media(self, file_name: str, data: bytes) -> str:
        self.files[f"ppt/media/{file_name}"] = data
        return f"/ppt/media/{file_name}"

    def ensure_default(self, extension: str, content_type: str) -> None:
        types = self.xml("[Content_Types].xml")
        if not any(t.get("Extension") == extension for t in types):
            E.SubElement(types, f"{{{CT}}}Default", Extension=extension, ContentType=content_type)
            self.put("[Content_Types].xml", types)

    def ensure_override(self, part: str, content_type: str) -> None:
        types = self.xml("[Content_Types].xml")
        if not any(t.get("PartName") == part for t in types):
            E.SubElement(types, f"{{{CT}}}Override", PartName=part, ContentType=content_type)
            self.put("[Content_Types].xml", types)

    def prune_images(self) -> None:
        """Drop image relationships a part no longer uses, then media nothing points at."""
        for name in [n for n in self.files if n.endswith(".rels") and "/_rels/" in n]:
            folder, rels_file = name.split("/_rels/")
            part = f"{folder}/{rels_file[:-len('.rels')]}"
            if part not in self.files:
                continue
            content = self.files[part].decode("utf-8", "ignore")
            rels = self.xml(name)
            for rel in list(rels):
                if rel.get("Type") == REL_IMAGE and f'"{rel.get("Id")}"' not in content:
                    rels.remove(rel)
            self.put(name, rels)
        targets = set()
        for name in [n for n in self.files if n.endswith(".rels")]:
            for rel in self.xml(name):
                targets.add(rel.get("Target", "").rsplit("/", 1)[-1])
        for name in [n for n in self.files if n.startswith("ppt/media/")]:
            if name.rsplit("/", 1)[-1] not in targets:
                del self.files[name]

    def write(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("[Content_Types].xml", self.files["[Content_Types].xml"])
            for name, data in self.files.items():
                if name == "[Content_Types].xml":
                    continue
                stored = name.endswith((".mp4", ".gif", ".jpeg", ".png"))
                archive.writestr(name, data, zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED)
        tmp.replace(path)


# ---------------------------------------------------------------------------- shapes


def fragment(xml: str) -> E._Element:
    return E.fromstring(f'<root xmlns:a="{A}" xmlns:p="{P}" xmlns:r="{R}">{xml}</root>')[0]


def xfrm(x: float, y: float, w: float, h: float) -> str:
    return (f'<a:xfrm><a:off x="{round(x * EMU)}" y="{round(y * EMU)}"/>'
            f'<a:ext cx="{round(w * EMU)}" cy="{round(h * EMU)}"/></a:xfrm>')


def run(text: str, size: float, color: str, bold: bool = False) -> str:
    # A currency symbol must never end a line apart from its amount.
    text = text.replace("R$ ", "R$ ").replace("US$ ", "US$ ").replace(" mil", " mil")
    return (f'<a:r><a:rPr lang="pt-BR" sz="{round(size * 75)}" b="{1 if bold else 0}" dirty="0">'
            f'<a:solidFill><a:srgbClr val="{color}"/></a:solidFill>'
            '<a:latin typeface="Arial"/><a:ea typeface="Arial"/><a:cs typeface="Arial"/>'
            f'</a:rPr><a:t>{escape(text)}</a:t></a:r>')


class Slide:
    """One slide's XML, with ids allocated above whatever the slide already uses."""

    def __init__(self, root: E._Element):
        self.root = root
        self.tree = root.find(".//p:spTree", NS)
        ids = [int(e.get("id")) for e in root.iter(f"{{{P}}}cNvPr") if e.get("id", "").isdigit()]
        self.next_id = max(ids, default=1) + 1

    def _id(self) -> int:
        self.next_id += 1
        return self.next_id - 1

    def by_id(self, shape_id: int) -> E._Element:
        for c_nv in self.root.iter(f"{{{P}}}cNvPr"):
            if c_nv.get("id") == str(shape_id):
                return c_nv.getparent().getparent()
        raise KeyError(shape_id)

    def remove(self, *shape_ids: int) -> None:
        for shape_id in shape_ids:
            shape = self.by_id(shape_id)
            shape.getparent().remove(shape)

    def move(self, shape_id: int, x: float, y: float, w: float, h: float) -> None:
        shape = self.by_id(shape_id)
        old = shape.find(".//a:xfrm", NS)
        if old is None:  # graphicFrame keeps its transform in the p namespace
            old = shape.find("p:xfrm", NS)
            new = fragment(xfrm(x, y, w, h).replace("<a:xfrm>", "<p:xfrm>").replace("</a:xfrm>", "</p:xfrm>"))
        else:
            new = fragment(xfrm(x, y, w, h))
        old.getparent().replace(old, new)

    def set_text(self, shape_id: int, paragraphs: list[str]) -> None:
        """Replace a text box's words while keeping the formatting of its first run."""
        shape = self.by_id(shape_id)
        body = shape.find("p:txBody", NS)
        first = body.find("a:p", NS)
        template_ppr = first.find("a:pPr", NS)
        template_rpr = first.find(".//a:rPr", NS)
        for para in body.findall("a:p", NS):
            body.remove(para)
        for text in paragraphs:
            para = E.SubElement(body, f"{{{A}}}p")
            if template_ppr is not None:
                para.append(copy.deepcopy(template_ppr))
            r_el = E.SubElement(para, f"{{{A}}}r")
            r_el.append(copy.deepcopy(template_rpr))
            E.SubElement(r_el, f"{{{A}}}t").text = text

    def text(self, x, y, w, h, paragraphs, size, color=GREEN, bold=False, align="l",
             anchor="t", name="Text", spacing=None, inset=True):
        """paragraphs: strings, or lists of (text, size, color, bold) runs for mixed styling."""
        if isinstance(paragraphs, str):
            paragraphs = [paragraphs]
        body = []
        for para in paragraphs:
            runs = [(para, size, color, bold)] if isinstance(para, str) else para
            line = f'<a:lnSpc><a:spcPct val="{spacing}"/></a:lnSpc>' if spacing else ""
            body.append(f'<a:p><a:pPr algn="{align}">{line}</a:pPr>'
                        + "".join(run(*r) for r in runs) + "</a:p>")
        pad = "" if inset else ' lIns="0" tIns="0" rIns="0" bIns="0"'
        self.tree.append(fragment(
            f'<p:sp><p:nvSpPr><p:cNvPr id="{self._id()}" name="{escape(name)}"/>'
            '<p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr>{xfrm(x, y, w, h)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>'
            f'<p:txBody><a:bodyPr wrap="square"{pad} anchor="{anchor}" rtlCol="0"><a:noAutofit/></a:bodyPr>'
            f'<a:lstStyle/>{"".join(body)}</p:txBody></p:sp>'))

    def box(self, x, y, w, h, fill=WHITE, line=LINE, radius=16, geometry="roundRect", name="Card"):
        adjust = ""
        if geometry == "roundRect":
            adjust = f'<a:gd name="adj" fmla="val {round(min(50000, radius / min(w, h) * 100000))}"/>'
        outline = (f'<a:ln w="12700"><a:solidFill><a:srgbClr val="{line}"/></a:solidFill></a:ln>'
                   if line else "<a:ln><a:noFill/></a:ln>")
        self.tree.append(fragment(
            f'<p:sp><p:nvSpPr><p:cNvPr id="{self._id()}" name="{name}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
            f'<p:spPr>{xfrm(x, y, w, h)}<a:prstGeom prst="{geometry}"><a:avLst>{adjust}</a:avLst></a:prstGeom>'
            f'<a:solidFill><a:srgbClr val="{fill}"/></a:solidFill>{outline}</p:spPr>'
            '<p:txBody><a:bodyPr rtlCol="0" anchor="ctr"/><a:lstStyle/><a:p><a:endParaRPr lang="pt-BR"/></a:p>'
            '</p:txBody></p:sp>'))

    def picture(self, rel_id, x, y, w, h, name="Picture", alt="", crop=None):
        src = ""
        if crop:
            left, top, right, bottom = (round(v * 100000) for v in crop)
            src = f'<a:srcRect l="{left}" t="{top}" r="{right}" b="{bottom}"/>'
        self.tree.append(fragment(
            f'<p:pic><p:nvPicPr><p:cNvPr id="{self._id()}" name="{escape(name)}" descr="{escape(alt)}"/>'
            '<p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>'
            f'<p:blipFill><a:blip r:embed="{rel_id}"/>{src}<a:stretch><a:fillRect/></a:stretch></p:blipFill>'
            f'<p:spPr>{xfrm(x, y, w, h)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>'))

    def icon(self, rel_id, x, y, size=56, name="Icon"):
        self.box(x, y, size, size, fill=TINT, line=None, geometry="ellipse", name=f"{name} circle")
        glyph = size * 0.56
        self.picture(rel_id, x + (size - glyph) / 2, y + (size - glyph) / 2, glyph, glyph, name=name)


def kicker_and_title(slide: Slide, kicker: str, title: str, dark: bool = False) -> None:
    slide.text(80, 40, 1420, 45, kicker, 25, DARK_MUTED if dark else GRAY, bold=True, name="Kicker")
    slide.text(80, 105, 1440, 100, title, 60, DARK_TEXT if dark else GREEN, bold=True, name="Title")


def set_notes(pkg: Package, notes_part: str, paragraphs: list[str]) -> None:
    notes = pkg.xml(notes_part)
    body = None
    for shape in notes.iter(f"{{{P}}}sp"):
        ph = shape.find(".//p:ph", NS)
        if ph is not None and ph.get("type") == "body":
            body = shape.find("p:txBody", NS)
    for para in body.findall("a:p", NS):
        body.remove(para)
    for text in paragraphs:
        para = E.SubElement(body, f"{{{A}}}p")
        r_el = E.SubElement(para, f"{{{A}}}r")
        E.SubElement(r_el, f"{{{A}}}t").text = text
    pkg.put(notes_part, notes)


# ---------------------------------------------------------------------------- slides


def icon_rel(pkg: Package, part: str, name: str, color: str = "green") -> str:
    target = pkg.add_media(f"icon-{name}-{color}.png", (HERE / "icons" / f"{name}-{color}.png").read_bytes())
    return pkg.add_rel(part, REL_IMAGE, target, f"rIdIcon{name.replace('-', '')}{color}")


def capture(pkg: Package) -> None:
    part = "ppt/slides/slide2.xml"
    s = Slide(pkg.xml(part))
    # The car photo moves to the cost slide; the subtitle is rebuilt with the new layout.
    s.remove(10, 5, 3)
    s.move(12, 1085, 130, 394, 700)
    s.text(80, 212, 900, 60, "Vídeo, localização e sensores em uma única coleta", 34, name="Subtitle")
    s.text(72, 282, 330, 150, "10 s", 120, bold=True, name="Ten seconds")
    s.text(390, 318, 560, 110, ["por segmento de vídeo,", "com GPS, direto para a nuvem"], 30,
           name="Ten seconds label")
    tiles = [
        ("smartphone", "Android e iOS", "Um único aplicativo, feito em Flutter"),
        ("map-pin", "GPS e sensores", "Posição, velocidade, direção, acelerômetro e giroscópio"),
        ("wifi-off", "Funciona sem sinal", "A coleta fica salva no celular e sobe quando a rede volta"),
        ("cloud-check", "Nada se perde", "O arquivo só sai do celular depois que a nuvem confirma"),
    ]
    for i, (icon, label, desc) in enumerate(tiles):
        x = 80 + (i % 2) * 450
        y = 478 + (i // 2) * 192
        s.box(x, y, 430, 175, name=f"Tile {label}")
        s.icon(icon_rel(pkg, part, icon), x + 22, y + 30, 64, name=f"Icon {label}")
        s.text(x + 100, y + 24, 318, 48, label, 30, bold=True, name=label)
        s.text(x + 100, y + 74, 318, 94, desc, 23, GRAY, name=f"{label} detail")
    pkg.put(part, s.root)
    set_notes(pkg, "ppt/notesSlides/notesSlide2.xml", [
        "[0:45 · Camada 1: captura] A primeira camada é um aplicativo de celular, para Android e iOS. "
        "O operador aponta a câmera para a faixa e aperta um botão. O app grava em segmentos de dez "
        "segundos, cada um com GPS e sensores, e envia para a nuvem. Sem sinal, a coleta fica salva no "
        "celular e sobe depois.",
        "Fonte: apps/mobile/README.md — captura Android e iOS em arquivos MP4 consecutivos de 10 s; GNSS, "
        "gravidade, aceleração linear, velocidade angular e orientação; fila persistente com SHA-256, que "
        "só libera o arquivo depois que a API aceita os bytes. A gravação (Vídeo app motiva.mp4) foi feita "
        "num Android. docs/STATE-OF-THE-SYSTEM.md registra Android e web rodando e diz que o build iOS "
        "precisa do Xcode; não há registro de execução num iPhone. Os números do painel do app (1.240 km, "
        "12 alertas) são dados de apresentação, não medições.",
    ])


def management(pkg: Package) -> None:
    part = "ppt/slides/slide4.xml"
    s = Slide(pkg.xml(part))
    s.move(6, 80, 228, 1080, 632)
    # The address, drawn like a browser bar, with a live badge.
    s.box(905, 116, 615, 80, radius=40, name="Address bar")
    s.box(921, 137, 124, 38, fill=GREEN, line=None, radius=19, name="Live badge")
    s.box(935, 150, 13, 13, fill="8FD14F", line=None, geometry="ellipse", name="Live dot")
    s.text(951, 137, 94, 38, "NO AR", 18, WHITE, bold=True, anchor="ctr", name="Live", inset=False)
    s.text(1056, 126, 460, 60, "greenv.matomomitsu.com", 32, bold=True, anchor="ctr", name="Address")
    s.text(1196, 226, 330, 46, "Rodando na nuvem", 28, bold=True, name="Deployed header")
    services = [
        ("globe", "Painel web", "Cloudflare Pages"),
        ("server", "API e filas", "Azure Container Apps"),
        ("gpu", "GPU sob demanda", "RunPod"),
        ("database", "Dados e arquivos", "Neon PostgreSQL · Cloudflare R2"),
    ]
    for i, (icon, label, provider) in enumerate(services):
        y = 284 + i * 128
        s.box(1200, y, 320, 114, name=f"Service {label}")
        s.icon(icon_rel(pkg, part, icon), 1218, y + 29, 56, name=f"Icon {label}")
        s.box(1262, y + 29, 14, 14, fill=LIME, line=WHITE, geometry="ellipse", name=f"{label} online")
        s.text(1286, y + 14, 230, 42, label, 23, bold=True, name=label)
        s.text(1286, y + 52, 230, 56, provider, 18, GRAY, name=f"{label} provider")
    s.text(1196, 800, 330, 60, "Endereço público conferido em 14/09/2026", 18, GRAY, name="Checked")
    pkg.put(part, s.root)
    set_notes(pkg, "ppt/notesSlides/notesSlide4.xml", [
        "[1:45 · Camada 2: decisão] Essas medições alimentam a plataforma de gestão. O mapa, os trechos "
        "por nível e as sessões de captura estão num endereço público: greenv.matomomitsu.com.",
        "[2:45 · clímax] Nada do que eu mostrei é maquete. Está no ar. Tem endereço. (pausa)",
        "Fonte: GestaoMotiva.mov, gravação da interface publicada, após o login. Tela de login de "
        "https://greenv.matomomitsu.com conferida em 14/09/2026, sem entrar. Infraestrutura segundo "
        "docs/STATE-OF-THE-SYSTEM.md e infrastructure/README.md: painel apps/web-prod no Cloudflare Pages "
        "desde 11/09/2026; API e dois workers no Azure Container Apps, com filas Azure e escala até zero; "
        "profundidade no endpoint RunPod greenv-mvp-depth; banco Neon PostgreSQL; arquivos no Cloudflare "
        "R2. Uma medição aparecer no mapa não valida a altura dela.",
    ])


def frustum(pkg: Package) -> None:
    """A new dark slide for the 2D-to-3D animation, placed before the recap."""
    part = "ppt/slides/slide16.xml"
    notes_part = "ppt/notesSlides/notesSlide16.xml"
    pkg.files[part] = (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="{A}" xmlns:r="{R}" xmlns:p="{P}">'
        f'<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="{DARK}"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>'
        '<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/>'
        '<a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>'
        '<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>'
    ).encode()
    pkg.files[pkg.rels_name(part)] = (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="{PKG_REL}">'
        f'<Relationship Id="rIdLayout" Type="{REL_LAYOUT}" Target="/ppt/slideLayouts/slideLayout1.xml"/>'
        f'<Relationship Id="rIdNotes" Type="{REL_NOTES}" Target="/{notes_part}"/></Relationships>'
    ).encode()
    notes = pkg.xml("ppt/notesSlides/notesSlide2.xml")
    pkg.put(notes_part, notes)
    pkg.files[pkg.rels_name(notes_part)] = (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="{PKG_REL}">'
        f'<Relationship Id="rIdSlide" Type="{REL_SLIDE}" Target="/{part}"/>'
        f'<Relationship Id="rIdMaster" Type="{REL_NOTES_MASTER}" Target="/ppt/notesMasters/notesMaster1.xml"/>'
        '</Relationships>'
    ).encode()
    pkg.ensure_default("gif", "image/gif")
    pkg.ensure_override(f"/{part}", "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")
    pkg.ensure_override(f"/{notes_part}", "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml")

    gif = pkg.add_rel(part, REL_IMAGE, pkg.add_media("frustum-2d-3d.gif", FRUSTUM_GIF.read_bytes()), "rIdFrustumGif")
    s = Slide(pkg.xml(part))
    # The animation's own content sits between 24% and 92% of its width, so its empty left side
    # can pass under the text column without anything drawn crossing a word.
    s.picture(gif, 520, 230, 1080, 608, name="Frustum 2D para 3D",
              alt="Animação: a distância de cada pixel, o frustum da câmera e a nuvem de pontos resultante")
    kicker_and_title(s, "GREENV · RECONSTRUÇÃO 3D", "Do vídeo ao 3D", dark=True)
    steps = [
        "A IA estima a distância de cada pixel",
        "A posição da câmera leva cada pixel para o espaço",
        "Juntando os quadros, o trecho vira 3D, em metros",
    ]
    for i, step in enumerate(steps):
        y = 262 + i * 116
        s.text(72, y - 8, 64, 70, str(i + 1), 46, LIGHT, bold=True, name=f"Step {i + 1}")
        s.text(136, y, 470, 96, step, 28, DARK_TEXT, name=f"Step {i + 1} text")
    s.text(72, 628, 520, 110, "Um vídeo vira um objeto que pode ser medido.", 34, LIGHT, bold=True,
           name="Pitch line")
    s.text(72, 826, 480, 40, "Cena real do jardim · ago/2026", 18, DARK_MUTED, name="Caption")
    pkg.put(part, s.root)
    set_notes(pkg, notes_part, [
        "[3:05 · Camada 3: prova] Uma câmera parada não enxerga profundidade; uma câmera em movimento, sim. "
        "O modelo estima a distância de cada pixel, a posição da câmera leva esses pixels para o espaço, e "
        "os quadros juntos formam o trecho em três dimensões, na escala do mundo real. Em uma frase: um "
        "vídeo vira um objeto que pode ser medido.",
        "Fonte: projecao_2d3d.gif (Downloads, 11/09/2026), 8 s em loop. Mostra um quadro sendo projetado. "
        "A versão MP4 do mesmo material (projecao_2d3d_1730.mp4, 17,5 s) continua até os frustums de todos "
        "os quadros e indica 94 quadros e 13,91 m percorridos.",
    ])

    # Register the slide right after the management slide (sldId 259).
    rel_id = pkg.add_rel("ppt/presentation.xml", REL_SLIDE, f"/{part}", "rIdFrustumSlide")
    presentation = pkg.xml("ppt/presentation.xml")
    slide_list = presentation.find("p:sldIdLst", NS)
    ids = [int(s_id.get("id")) for s_id in slide_list]
    new = E.Element(f"{{{P}}}sldId", id=str(max(ids) + 1))
    new.set(f"{{{R}}}id", rel_id)
    management_entry = next(s_id for s_id in slide_list if s_id.get("id") == "259")
    management_entry.addnext(new)
    pkg.put("ppt/presentation.xml", presentation)


def automatic(pkg: Package) -> None:
    """The old automatic-processing slide, rebuilt around the rendered measurement of a driven clip."""
    part = "ppt/slides/slide3.xml"
    p14 = "http://schemas.microsoft.com/office/powerpoint/2010/main"
    pkg.files[part] = (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="{A}" xmlns:r="{R}" xmlns:p="{P}">'
        f'<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="{DARK}"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>'
        '<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/>'
        '<a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>'
        '<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>'
    ).encode()
    rels = pkg.xml(pkg.rels_name(part))
    for rel in list(rels):
        if rel.get("Type") not in (REL_LAYOUT, REL_NOTES):
            rels.remove(rel)
    pkg.put(pkg.rels_name(part), rels)
    video = pkg.add_media("automatic-measurement.mp4", AUTOMATIC_VIDEO.read_bytes())
    pkg.add_rel(part, R + "/video", video, "rIdAutoVideo")
    pkg.add_rel(part, "http://schemas.microsoft.com/office/2007/relationships/media", video, "rIdAutoMedia")
    pkg.add_rel(part, REL_IMAGE, pkg.add_media("automatic-measurement-poster.png", AUTOMATIC_POSTER.read_bytes()), "rIdAutoPoster")

    s = Slide(pkg.xml(part))
    kicker_and_title(s, "GREENV · MEDIÇÃO AUTOMÁTICA", "Sem ninguém descer do carro", dark=True)
    s.text(80, 186, 1440, 44, "Um modelo acha a vegetação, outro reconhece guard-rail, muro, poste e placa, e a geometria "
           "mede cada célula de 0,5 m", 24, DARK_TEXT, name="How")
    video_id = s._id()
    # 1920 x 860 rendered, drawn at 1360 x 609 so the caption fits beneath it.
    s.tree.append(fragment(
        f'<p:pic><p:nvPicPr><p:cNvPr id="{video_id}" name="Medicao-Automatica.mp4" '
        'descr="Animação: o vídeo do carro com os pixels medidos e a reconstrução 3D com as células de 0,5 m">'
        '<a:hlinkClick action="ppaction://media"/></p:cNvPr><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr>'
        f'<p:nvPr><a:videoFile r:link="rIdAutoVideo"/><p:extLst><p:ext uri="{{DAA4B4D4-6D71-4841-9C94-3DE7FCFB9230}}">'
        f'<p14:media xmlns:p14="{p14}" r:embed="rIdAutoMedia"/></p:ext></p:extLst></p:nvPr></p:nvPicPr>'
        '<p:blipFill><a:blip r:embed="rIdAutoPoster"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>'
        f'<p:spPr>{xfrm(120, 238, 1360, 609)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>'))
    s.text(80, 854, 1440, 36, "carro_em_movimento2 · 97 quadros · 12 s na chuva · parâmetros do deploy · escala do "
           "próprio modelo (vídeo sem GPS) · ainda sem comparação com trena", 17, DARK_MUTED, name="Caption")
    s.root.append(fragment(
        '<p:timing><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst>'
        '<p:video><p:cMediaNode vol="0"><p:cTn id="2" repeatCount="indefinite" fill="hold" display="0"><p:stCondLst>'
        f'<p:cond delay="0"/></p:stCondLst></p:cTn><p:tgtEl><p:spTgt spid="{video_id}"/></p:tgtEl></p:cMediaNode>'
        '</p:video></p:childTnLst></p:cTn></p:par></p:tnLst></p:timing>'))
    pkg.put(part, s.root)

    # After the second roadside scene (sldId 262), instead of third.
    presentation = pkg.xml("ppt/presentation.xml")
    slide_list = presentation.find("p:sldIdLst", NS)
    entry = next(s_id for s_id in slide_list if s_id.get("id") == "258")
    slide_list.remove(entry)
    next(s_id for s_id in slide_list if s_id.get("id") == "262").addnext(entry)
    pkg.put("ppt/presentation.xml", presentation)

    totals = json.loads(AUTOMATIC_PACKET.read_text())
    quality = totals["quality"]
    set_notes(pkg, "ppt/notesSlides/notesSlide3.xml", [
        "[3:05 · Camada 3: prova] Agora sem ninguém pintar máscara. Esse vídeo tem doze segundos, gravado do carro "
        "em movimento e na chuva. Um modelo encontra a vegetação; um segundo modelo reconhece o que não é grama — "
        "guard-rail, muro, poste, placa. A geometria divide a faixa de cinco metros e meio ao lado da pista em células de "
        f"meio metro e mede a altura de cada uma contra o próprio chão: {quality['measuredCells']} células medidas em "
        f"{totals['corridor']['lengthM']:.0f} metros, {quality['structureCells']} recusadas porque havia estrutura e "
        f"{quality['slopeCells']} porque eram talude.",
        f"Fonte: run {totals['runId']} (carro_em_movimento2.mp4, 97 quadros a 8 fps; profundidade DA3 no Cloud Run L4 "
        "em 14/09/2026). Medida na CPU local em 14/09/2026 com measurement/scripts/assess-grass.mjs e os parâmetros do "
        "deploy (docs/presentations/motiva-pitch/automatic-request.json, conforme infrastructure/variables.tf): classes "
        "terrain,vegetation; segundo modelo ade20k-b4 com piso 0,4; faixa automática de 5,5 m; chão por quadro; "
        "talude a 0,1 m por célula; estrutura vista em 3 quadros; nada além das pontas da trilha. 274 s. O pacote "
        "passa em check-grass-quality.mjs (checksums, digests das máscaras, relatório igual ao JSON).",
        "A animação é docs/presentations/motiva-pitch/render_automatic_measurement.py: redesenha as células, os "
        "estados e os pixels exatos que cada célula usou, lidos do pacote; o fundo é a nuvem de pontos do GLB do "
        "run. Mediana 14,5 cm e p90 21,1 cm nas 360 células medidas.",
        "Limites: o vídeo não tem telemetria, então a escala é a do próprio modelo. Na reconstrução a câmera fica a "
        "0,73 m do chão, baixo para um celular na janela, e as alturas podem estar subestimadas; no app, o GPS do "
        f"trajeto ancora a escala. {quality['abstainedCells']} células vistas ficaram sem suporte suficiente. Nenhuma "
        "leitura automática foi comparada com trena; operationalStatus not-ready.",
    ])


def recap(pkg: Package) -> None:
    part = "ppt/slides/slide5.xml"
    s = Slide(pkg.xml(part))
    s.set_text(4, ["DA ÚLTIMA VEZ QUE CONVERSAMOS"])
    s.set_text(2, ["GreenV"])
    s.move(6, 80, 228, 1100, 619)
    s.box(1251, 300, 3, 380, fill="C9D6CC", line=None, geometry="rect", name="Timeline")
    stages = [
        ("Quarto", "Objetos medidos com trena · ago/2026", GREEN),
        ("Jardim", "Plantas medidas com trena · ago/2026", GREEN),
        ("Rodovia", "Agora: os resultados novos · set/2026", LIME),
    ]
    for i, (label, detail, dot) in enumerate(stages):
        y = 282 + i * 190
        s.box(1240, y, 26, 26, fill=dot, line=BG, geometry="ellipse", name=f"{label} dot")
        s.text(1282, y - 12, 250, 48, label, 32, bold=True, name=label)
        s.text(1282, y + 36, 240, 80, detail, 20, GRAY, name=f"{label} detail")
    pkg.put(part, s.root)
    set_notes(pkg, "ppt/notesSlides/notesSlide5.xml", [
        "[3:05 · Camada 3: prova] Da última vez que conversamos, eu tinha testado essa ideia no meu quarto. "
        "Depois no jardim do meu condomínio. Esse vídeo resume o que já tínhamos mostrado. Nessa semana, "
        "levamos a mesma medição para a rodovia.",
        "Fonte: recapVerge-Studio.mp4, fornecido; gravações de inspeção no Verge Studio. Quarto: "
        "RoomNewFixture, 11/08/2026. Jardim: capturas de 14/08/2026. Material histórico, não uma nova rodada "
        "de validação.",
    ])


def accuracy_slide(pkg: Package, numbers: dict) -> None:
    part = "ppt/slides/slide8.xml"
    road, everything = numbers["rodovia"], numbers["all"]
    s = Slide(pkg.xml(part))
    s.set_text(2, ["Erro medido contra a trena"])
    s.remove(3, 4, 5, 6, 8, 9)
    s.text(72, 206, 540, 140, cm(road["maeCm"]) + " cm", 104, bold=True, name="Road MAE")
    s.text(80, 348, 540, 100, ["erro absoluto médio", "na rodovia"], 36, name="Road MAE label")
    s.text(80, 460, 560, 44, f"{road['targets']} alvos · {road['trials']} ensaios · maior erro "
           f"{cm(road['maxCm'])} cm", 22, GRAY, bold=True, name="Road counts")
    s.move(16, 640, 200, 880, 350)
    s.text(716, 546, 360, 40, "Erro médio: 1,50 cm", 21, bold=True, align="ctr", name="Target 1 error")
    s.text(1104, 546, 360, 40, "Erro médio: 0,32 cm", 21, bold=True, align="ctr", name="Target 2 error")

    s.box(80, 604, 1440, 222, name="Everything measured")
    s.text(106, 622, 700, 38, "TUDO O QUE JÁ MEDIMOS COM TRENA", 20, GRAY, bold=True, name="Panel header")
    for i, (value, label) in enumerate([(everything["targets"], "alvos"), (everything["trials"], "ensaios"),
                                        (everything["clips"], "vídeos")]):
        x = 106 + i * 240
        s.text(x, 660, 220, 96, str(value), 76, bold=True, name=f"Total {label}")
        s.text(x, 756, 220, 44, label, 26, name=f"Total {label} label")
    s.box(848, 636, 2, 160, fill=LINE, line=None, geometry="rect", name="Divider")
    s.text(880, 622, 620, 38, "ERRO ABSOLUTO MÉDIO POR AMBIENTE", 20, GRAY, bold=True, name="By environment")
    rows = [("Rodovia", numbers["rodovia"]), ("Jardim", numbers["jardim"]),
            ("Interior", numbers["interior"]), ("Geral", everything)]
    for i, (label, row) in enumerate(rows):
        y = 660 + i * 38
        strong = label == "Geral"
        s.text(880, y, 420, 38, [[(label, 22, GREEN, True), (f"  ·  {row['trials']} ensaios", 20, GRAY, False)]],
               22, name=f"{label} row")
        s.text(1300, y, 196, 38, cm(row["maeCm"]) + " cm", 24 if strong else 22, bold=True, align="r",
               name=f"{label} value")
    s.text(80, 838, 1440, 40, "Seleção manual · método Extent · referências de 10 cm a 2,10 m · "
           "ago–set/2026", 18, GRAY, name="Footer")
    pkg.put(part, s.root)

    chart = pkg.xml("ppt/slides/charts/chart1.xml")
    title = chart.find("c:chart/c:title", NS)
    title.getparent().replace(title, E.Element(f"{{{C}}}autoTitleDeleted", val="1"))
    for label_props in chart.iter(f"{{{A}}}defRPr"):
        if label_props.get("sz") in ("1875", "1725"):
            label_props.set("sz", "1650")
    pkg.put("ppt/slides/charts/chart1.xml", chart)

    set_notes(pkg, "ppt/notesSlides/notesSlide8.xml", [
        "[3:05 · Camada 3: prova] Naquele dia eu levei uma trena e medi dois pontos da rodovia na mão, três vezes "
        f"cada. O erro absoluto médio foi de {cm(road['maeCm'])} centímetro. (pausa) Somando tudo o que já "
        f"comparamos com trena — quarto, jardim e rodovia — são {everything['targets']} alvos e "
        f"{everything['trials']} ensaios em {everything['clips']} vídeos, com erro absoluto médio de "
        f"{cm(everything['maeCm'], 1)} centímetros.",
        "Fontes: rodovia — ~/verge-runs/20260914-144411-5032ee e 20260914-143905-ce30bc, "
        "measurements/*.json, dois alvos de 10 cm. Demais — measurement/MEASUREMENTS.md e o resumo de "
        "evidências do Verge Studio, 26 ensaios reexecutados a partir das máscaras gravadas. Por ambiente: "
        f"rodovia {cm(numbers['rodovia']['maeCm'], 3)} cm em 6 ensaios; jardim {cm(numbers['jardim']['maeCm'], 3)} cm "
        f"em 8, dois deles com máscara automática; interior {cm(numbers['interior']['maeCm'], 3)} cm em 18. A "
        "primeira captura interna, a da porta em 04/08, leu 3,9–7,0% abaixo e tem o maior erro, "
        f"{cm(numbers['all']['maxCm'])} cm numa porta de 2,10 m; sem ela, "
        f"{cm(numbers['withoutDoor']['maeCm'])} cm em {numbers['withoutDoor']['trials']} ensaios.",
        "Limites: máscaras pintadas à mão, exceto dois ensaios do jardim; três repetições por alvo na mesma "
        "sessão. Não é a acurácia do processo automático, que ainda não foi comparado com trena. "
        "rodovia_medida2 e rodovia_medida3 têm trena no vídeo e já estão reconstruídos, mas ainda não têm "
        "ensaios salvos.",
    ])


def cost(pkg: Package) -> None:
    part = "ppt/slides/slide9.xml"
    s = Slide(pkg.xml(part))
    s.set_text(7, ["CUSTO"])
    s.set_text(2, ["Quanto custa medir"])
    s.remove(3, 4, 5, 6)

    s.text(80, 226, 560, 36, "PROCESSAMENTO NA NUVEM", 20, GRAY, bold=True, name="Compute header")
    s.text(72, 258, 600, 150, "US$ 0,62", 112, bold=True, name="Per km")
    s.text(80, 404, 560, 56, "por km processado", 38, name="Per km label")
    s.text(80, 462, 560, 46, "≈ R$ 3,20 por km", 28, bold=True, name="Per km in reais")
    s.box(80, 548, 560, 132, fill=TINT, line=None, name="Example")
    s.text(104, 566, 520, 40, "Rodoanel Oeste · km 0–29,3, um sentido", 24, GRAY, bold=True, name="Example label")
    s.text(104, 608, 520, 56, "≈ US$ 18 por passagem", 34, bold=True, name="Example value")
    s.text(80, 772, 560, 90, ["Estimativa de 10/09/2026 com GPU NVIDIA L4", "Câmbio de 14/09/2026: R$ 5,17",
                              "Premissas nos slides de apoio"], 18, GRAY, name="Compute footnote")

    s.text(700, 226, 820, 36, "CAPTURA · DOIS CAMINHOS", 20, GRAY, bold=True, name="Capture header")
    # Path 1: the phone the pilot already has.
    s.box(700, 270, 330, 540, name="Pilot card")
    s.icon(icon_rel(pkg, part, "smartphone"), 724, 294, 64, name="Icon phone")
    s.text(724, 376, 290, 34, "1 · PILOTO", 18, GRAY, bold=True, name="Pilot tag")
    s.text(724, 406, 296, 52, "Celular comum", 32, bold=True, name="Pilot title")
    s.text(724, 458, 290, 100, "Qualquer smartphone Android ou iOS com câmera e GPS", 21, name="Pilot detail")
    s.text(724, 560, 290, 70, "Mais um suporte veicular de R$ 20 a R$ 110", 19, GRAY, name="Pilot mount")
    s.text(716, 684, 300, 84, "R$ 0", 64, bold=True, name="Pilot cost")
    s.text(724, 762, 290, 40, "em câmeras novas", 22, name="Pilot cost label")
    # Path 2: a fixed roof installation, the user's reference photo.
    s.box(1050, 270, 470, 540, name="Precision card")
    photo = pkg.add_rel(part, REL_IMAGE, pkg.add_media("camera-setup-reference.jpeg", CAR_PHOTO.read_bytes()),
                        "rIdCameraSetup")
    # 442 x 236 keeps 1.873:1 of a 1200 x 900 photo: keep rows 18..658, the roof rack included.
    s.picture(photo, 1064, 284, 442, 236, name="Camera setup reference",
              alt="Carro com câmeras fixas no teto, imagem de referência", crop=(0, 0.02, 0, 0.2689))
    s.text(1074, 532, 430, 34, "2 · MAIS PRECISÃO", 18, GRAY, bold=True, name="Precision tag")
    s.text(1074, 562, 436, 52, "Câmeras fixas no teto", 32, bold=True, name="Precision title")
    s.text(1074, 612, 430, 70, "2 câmeras de ação 5,3K com GPS, uma para cada lado da pista", 21,
           name="Precision detail")
    s.text(1066, 684, 440, 84, "≈ R$ 8 mil", 64, bold=True, name="Precision cost")
    s.text(1074, 762, 430, 40, "por veículo · câmeras e suportes", 22, name="Precision cost label")
    s.text(700, 818, 820, 64, ["Referências de 14/09/2026: GoPro HERO13 Black R$ 3.399 (loja GoPro Brasil) · "
                               "ventosa tripla R$ 660–702 · foto: exemplo de instalação"], 17, GRAY,
           name="Price sources")
    pkg.put(part, s.root)
    set_notes(pkg, "ppt/notesSlides/notesSlide9.xml", [
        "[4:05 · custo] Sobre custo: a maior parte é computacional, uma GPU servindo o modelo. Na estimativa "
        "de planejamento, dá 62 centavos de dólar por quilômetro processado — perto de 18 dólares para passar "
        "pelo Rodoanel Oeste inteiro, num sentido. Para capturar, há dois caminhos. No piloto, serve qualquer celular com câmera e "
        "GPS, preso num suporte de carro. Se vocês quiserem mais precisão, a referência é uma instalação fixa "
        "no teto, como a da foto: duas câmeras de ação com GPS saem por volta de oito mil reais por veículo.",
        "Fontes do processamento: modelo de custos de 10/09/2026 (Google Cloud Run, NVIDIA L4, US$ 1,42 por "
        "hora; 2 quadros por metro, lotes de 100 quadros em 60 s, margem de 30%), slides de apoio. Não é o "
        "custo medido do sistema atual, que roda no RunPod. Rodoanel Oeste (SP-021): km 0–29,3 por sentido, "
        "como no mapa do GreenV; 29,3 × US$ 0,6157 = US$ 18,04. Câmbio: R$ 5,16–5,18 na manhã de 14/09/2026 (InfoMoney).",
        "Fontes da captura, consultadas em 14/09/2026: GoPro HERO13 Black R$ 3.399 na loja GoPro Brasil "
        "(promoções a partir de R$ 3.099); ventosa tripla Telesin R$ 659,90 (FunPro) a R$ 701,90 (KaBuM!); "
        "suportes de celular R$ 17–110 (Amazon.com.br). Kit: 2 × 3.399 + 2 × ~680 ≈ R$ 8.160.",
        "Ressalvas: o ganho de precisão de uma câmera fixa ainda não foi medido. Hoje a telemetria vem do "
        "app; usar a trilha de GPS gravada pela câmera exige um importador que o GreenV ainda não tem. A foto "
        "é uma referência de montagem, não um veículo do GreenV.",
    ])


def prediction(pkg: Package) -> None:
    part = "ppt/slides/slide11.xml"
    s = Slide(pkg.xml(part))
    s.remove(8)
    s.move(3, 80, 222, 600, 70)
    s.move(4, 80, 298, 600, 132)
    s.move(5, 80, 430, 600, 64)
    s.move(15, 700, 212, 820, 332)
    s.move(7, 760, 548, 750, 56)
    cards = [
        ("trees", "Modelo", "Random Forest (scikit-learn), 300 árvores. No teste simulado, erro 39% menor "
                            "que o melhor método de referência."),
        ("flask-conical", "Dados simulados", "118 trechos de 500 m do Rodoanel Oeste, com clima real de 2023 "
                                             "a 2026, para montar e testar o modelo."),
        ("history", "Com histórico real", "Cada passagem vira dado. Com semanas de medições, retreinamos e "
                                          "somamos clima, roçadas e tipo de vegetação."),
    ]
    for i, (icon, label, body) in enumerate(cards):
        x = 80 + i * 487
        s.box(x, 628, 466, 196, name=f"Card {label}")
        s.icon(icon_rel(pkg, part, icon), x + 22, 652, 56, name=f"Icon {label}")
        s.text(x + 92, 644, 364, 42, label, 24, bold=True, name=label)
        s.text(x + 92, 684, 364, 136, body, 20, GRAY, name=f"{label} detail")
    s.set_text(9, ["Exemplo simulado · SP-021 · sul · km 1,5 · base: 30/08/2026 · cenário sem nova roçada"])
    s.move(9, 80, 838, 1450, 40)
    pkg.put(part, s.root)
    set_notes(pkg, "ppt/notesSlides/notesSlide11.xml", [
        "[4:20 · fecho: previsão] E tem uma coisa que só acontece depois. Cada medição fica guardada. O modelo de "
        "previsão — um Random Forest — já foi montado e testado com dados simulados: 118 trechos do Rodoanel "
        "Oeste, com o clima real dos últimos anos. Neste exemplo, um trecho com 27 centímetros deve chegar a 30 em "
        "cerca de dez dias, com uma janela de incerteza larga. Com semanas de medições reais, o modelo é "
        "retreinado nesse histórico e combina clima, roçadas e tipo de vegetação. Prever significa ir menos "
        "vezes a campo.",
        "Fonte: branch origin/feat/vegetation-prediction, commit 209c8ef, de Ryan Amorim de Castro Santana — "
        "services/greenv-vegetation-prediction. Modelo: RandomForestRegressor com 300 árvores, "
        "min_samples_leaf=20 e 14 variáveis (altura atual e anteriores, crescimento semanal, dias desde a "
        "roçada, graus-dia, temperatura mínima, chuva e déficit hídrico de 30 e 90 dias, estação seca, tipo "
        "de vegetação, estado operacional). Vegetação 100% sintética; clima real do Open-Meteo, conferido "
        "com o NASA POWER.",
        "Teste com dados simulados (reports/model-card.md): 11,17 dias de erro médio nos dias até 30 cm, "
        "contra 18,45 dias do melhor método de referência (−39,4%); 10,82 cm de erro na altura a 7 dias. "
        "Intervalo de 90% por conformal, com largura média de ~55 dias. Previsão salva em "
        "data/forecast/ranking_current.json, SP-021:sul:001500: 27,04 cm, 9,7 dias, intervalo 0–41,1 dias. "
        "A medição automática ainda não alimenta a previsão.",
    ])


def supporting_accuracy(pkg: Package, numbers: dict) -> None:
    part = "ppt/slides/slide13.xml"
    everything = numbers["all"]
    s = Slide(pkg.xml(part))
    s.set_text(8, ["APOIO · ACURÁCIA"])
    s.set_text(2, ["Todos os ensaios com trena"])
    s.set_text(3, [cm(everything["maeCm"]) + " cm"])
    s.set_text(4, ["erro absoluto médio", f"{everything['trials']} ensaios · {everything['targets']} alvos · "
                   f"{everything['clips']} vídeos"])
    s.set_text(5, [f"Rodovia: {cm(numbers['rodovia']['maeCm'])} cm (6)",
                   f"Jardim: {cm(numbers['jardim']['maeCm'])} cm (8)",
                   f"Interior: {cm(numbers['interior']['maeCm'])} cm (18)"])
    s.move(5, 880, 270, 650, 200)
    s.set_text(6, ["Máscaras pintadas à mão, exceto 2 ensaios.",
                   "A 1ª captura interna (porta) leu 4–7% abaixo;",
                   f"sem ela: {cm(numbers['withoutDoor']['maeCm'])} cm em {numbers['withoutDoor']['trials']} ensaios.",
                   "Não mede o processo automático."])
    s.set_text(7, ["Fontes: MEASUREMENTS.md e evidências reexecutadas do Verge Studio · ensaios salvos da rodovia, "
                   "14/09/2026"])
    pkg.put(part, s.root)
    set_notes(pkg, "ppt/notesSlides/notesSlide13.xml", [
        "Apoio para perguntas; oculto na apresentação. Detalhe dos números do slide de acurácia.",
        f"Todos: {cm(everything['maeCm'], 4)} cm em {everything['trials']} ensaios. Rodovia "
        f"{cm(numbers['rodovia']['maeCm'], 4)}; jardim {cm(numbers['jardim']['maeCm'], 4)}; interior "
        f"{cm(numbers['interior']['maeCm'], 4)}; sem a porta {cm(numbers['withoutDoor']['maeCm'], 4)} cm. Maior erro "
        f"{cm(everything['maxCm'])} cm (porta, 2,10 m).",
    ])


def retime(pkg: Package, notes_part: str, label: str) -> None:
    """Swap an untouched slide's old timestamp for the pitch section it now belongs to."""
    notes = pkg.xml(notes_part)
    for shape in notes.iter(f"{{{P}}}sp"):
        ph = shape.find(".//p:ph", NS)
        if ph is not None and ph.get("type") == "body":
            first = shape.find(".//a:t", NS)
            text = first.text
            if text.startswith("["):
                text = text[text.index("]") + 1:].lstrip()
            first.text = f"{label} {text}"
    pkg.put(notes_part, notes)


def main() -> None:
    numbers = accuracy()
    pkg = Package(SOURCE)
    pkg.ensure_default("jpeg", "image/jpeg")
    pkg.ensure_default("png", "image/png")
    capture(pkg)
    management(pkg)
    frustum(pkg)
    automatic(pkg)
    recap(pkg)
    accuracy_slide(pkg, numbers)
    cost(pkg)
    prediction(pkg)
    supporting_accuracy(pkg, numbers)
    # Sections of ~/Desktop/pitch-greenv-final.md; the old minute ranges no longer fit the order.
    for notes_part, label in [
        ("ppt/notesSlides/notesSlide1.xml", "[0:00 · Abertura e as três camadas]"),
        ("ppt/notesSlides/notesSlide6.xml", "[3:05 · Camada 3: prova]"),
        ("ppt/notesSlides/notesSlide7.xml", "[3:05 · Camada 3: prova]"),
        ("ppt/notesSlides/notesSlide10.xml", "[4:20 · fecho: modularidade]"),
        ("ppt/notesSlides/notesSlide12.xml", "[4:20 · fecho: expansão]"),
    ]:
        retime(pkg, notes_part, label)
    pkg.prune_images()
    pkg.write(TARGET)
    print(f"wrote {TARGET.relative_to(REPO)}")
    for key in ("rodovia", "jardim", "interior", "all", "withoutDoor"):
        row = numbers[key]
        print(f"  {key:12s} {row['trials']:2d} ensaios · {row['targets']:2d} alvos · MAE {row['maeCm']:.3f} cm")


if __name__ == "__main__":
    main()
