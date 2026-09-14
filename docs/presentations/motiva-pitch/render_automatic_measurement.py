"""Render one automatic grass measurement as an animation, from its own packet.

Everything drawn is read from what the pipeline produced, never re-derived:

  - the packet `assessment.json` written by `measurement/scripts/assess-grass.mjs --out`,
    for the cells, their status, heights, voting frames and the exact depth pixels each
    cell used, plus the ground plane and the road edge;
  - the saved run it measured (`~/verge-runs/<runId>`): the source frames, the depth
    arrays and cameras, and the GLB point cloud used as backdrop.

The measurement subtree is called as a process by whoever makes the packet; nothing here
imports from `measurement/`.

    python render_automatic_measurement.py --packet <dir>/assessment.json --out <dir>
    python render_automatic_measurement.py ... --stills 0,120,300      # a few frames only
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

HOME = Path.home()
FONT_DIR = Path("/System/Library/Fonts/Supplemental")

CANVAS_W, CANVAS_H = 1920, 860
FPS = 24
# The result first, so the still Keynote shows and the loop seam are both the finished stretch.
OPEN_S, FLY_S, DRIVE_S, PULL_S, HOLD_S = 1.4, 1.2, 10.0, 2.2, 2.8
SS = 2  # supersampling of the 3D panel

BG = (13, 12, 18)
TEXT = (244, 247, 243)
MUTED = (143, 168, 154)
# The dashboard's own level colours (apps/web-core/src/utils/classification.js).
LEVEL = {1: (66, 187, 111), 2: (202, 138, 4), 3: (220, 38, 38)}
STRUCTURE = (236, 72, 153)
SLOPE = (96, 165, 250)

FRAME_BOX = (40, 58, 422, 750)          # x, y, w, h of the video panel
VIEW_BOX = (502, 58, 1378, 750)         # x, y, w, h of the 3D panel


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_DIR / ("Arial Bold.ttf" if bold else "Arial.ttf")), size)


def level_of(extent_m: float) -> int:
    return 1 if extent_m < 0.10 else (2 if extent_m <= 0.30 else 3)


def cm(value: float, digits: int = 0) -> str:
    return f"{value * 100:.{digits}f}".replace(".", ",")


# ---------------------------------------------------------------------------------- inputs


def read_glb(path: Path):
    data = path.read_bytes()
    json_length = struct.unpack("<I", data[12:16])[0]
    gltf = json.loads(data[20:20 + json_length])
    offset = 20 + json_length + 8

    def accessor(index):
        acc = gltf["accessors"][index]
        view = gltf["bufferViews"][acc["bufferView"]]
        start = offset + view.get("byteOffset", 0) + acc.get("byteOffset", 0)
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[acc["type"]]
        dtype = {5126: np.float32, 5121: np.uint8, 5123: np.uint16}[acc["componentType"]]
        return np.frombuffer(data, dtype=dtype, count=acc["count"] * width, offset=start).reshape(acc["count"], width)

    points = colours = None
    for mesh in gltf["meshes"]:
        for primitive in mesh["primitives"]:
            if primitive.get("mode") == 0:
                points = accessor(primitive["attributes"]["POSITION"]).astype(np.float64)
                colours = accessor(primitive["attributes"]["COLOR_0"])[:, :3].astype(np.float32)
    alignment = np.array(gltf["scenes"][gltf.get("scene", 0)]["extras"]["hf_alignment"], dtype=np.float64)
    return points, colours, alignment


def decode_runs(runs, size):
    mask = np.zeros(size, np.uint8)
    for start, length in zip(runs[0::2], runs[1::2]):
        mask[start:start + length] = 1
    return mask


class Measurement:
    """The packet and the run, joined in the packet's own world frame."""

    def __init__(self, packet_path: Path, runs_root: Path):
        self.packet = json.loads(packet_path.read_text())
        self.run_dir = runs_root / self.packet["runId"]
        self.points, self.colours, self.alignment = read_glb(self.run_dir / "scene.glb")
        arrays = np.load(self.run_dir / "verge-result.npz")
        self.extrinsics = arrays["extrinsics"].astype(np.float64)
        self.intrinsics = arrays["intrinsics"].astype(np.float64)
        self.depth_h, self.depth_w = arrays["depth"].shape[1:]
        if self.packet["scale"]["applied"]:
            raise SystemExit("this renderer draws the GLB backdrop at the model's scale; a scaled packet needs it rescaled first")
        plane = self.packet["ground"]["plane"]
        self.n = np.array(plane["normal"], float)
        self.n /= np.linalg.norm(self.n)
        self.offset = float(plane["offset"])
        seed = np.array([1.0, 0, 0]) if abs(self.n[0]) < 0.9 else np.array([0, 0, 1.0])
        e1 = seed - self.n * (seed @ self.n)
        self.e1 = e1 / np.linalg.norm(e1)
        self.e2 = np.cross(self.n, self.e1)  # right-handed, so (-dy, dx) is cross(normal, travel)

        rotation = self.extrinsics[:, :3, :3]
        translation = self.extrinsics[:, :3, 3]
        centres = -np.einsum("nji,nj->ni", rotation, translation)
        self.cameras = centres @ self.alignment[:3, :3].T + self.alignment[:3, 3]
        self.world_from_camera = np.einsum("ij,njk->nik", self.alignment[:3, :3], np.transpose(rotation, (0, 2, 1)))
        self.track = self.cameras - np.outer(self.cameras @ self.n + self.offset, self.n)
        tangent = np.gradient(self.track, axis=0)
        kernel = np.exp(-0.5 * (np.arange(-6, 7) / 3.0) ** 2)
        tangent = np.stack([np.convolve(np.pad(tangent[:, k], 6, mode="edge"), kernel / kernel.sum(), "valid") for k in range(3)], 1)
        self.tangent = tangent / np.linalg.norm(tangent, axis=1, keepdims=True)

        edge = np.array(self.packet["corridor"]["polyline"], float)
        self.edge_uv = np.stack([edge @ self.e1, edge @ self.e2], 1)
        self.station = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(self.edge_uv, axis=0), axis=1))])
        self.side = -1 if self.packet["assessment"]["band"]["bandSide"] == "negative" else 1

        self.cells = [c for c in self.packet["cells"] if c["status"] in ("measured", "structure", "slope")]
        self.quads = self.cell_quads(self.cells)
        self.reveal = np.array([self.reveal_frame(c) for c in self.cells])
        self._select_backdrop()

    # Road-local metres back to the world, the inverse of the grid's `stationOf`.
    def cell_quads(self, cells, size=0.5):
        along = np.array([c["coordinate"]["alongRoadM"] for c in cells])
        distance = np.array([c["coordinate"]["distanceFromRoadM"] for c in cells])
        lift = np.array([c["localGroundM"] or 0.0 for c in cells])
        corners = []
        for d_along, d_dist in [(-0.5, -0.5), (0.5, -0.5), (0.5, 0.5), (-0.5, 0.5)]:
            s = along + d_along * size
            i = np.clip(np.searchsorted(self.station, s, side="right") - 1, 0, len(self.station) - 2)
            a, b = self.edge_uv[i], self.edge_uv[i + 1]
            length = np.maximum(self.station[i + 1] - self.station[i], 1e-9)
            direction = (b - a) / length[:, None]
            point = a + direction * (s - self.station[i])[:, None]
            perpendicular = np.stack([-direction[:, 1], direction[:, 0]], 1)
            uv = point + perpendicular * (self.side * (distance + d_dist * size))[:, None]
            corners.append(np.outer(uv[:, 0], self.e1) + np.outer(uv[:, 1], self.e2)
                           - self.offset * self.n + np.outer(lift + 0.02, self.n))
        return np.stack(corners, 1)

    @staticmethod
    def reveal_frame(cell):
        """A cell is drawn from the frame at which its third vote arrived: the grid's own bar."""
        votes = sorted(v["frameIndex"] for v in cell.get("frameVotes") or [])
        if votes:
            return votes[min(2, len(votes) - 1)]
        return min(cell.get("evidenceFrameIndices") or [0])

    def _select_backdrop(self):
        """The verge side of the road only: the wet road's reflections fin out below the plane."""
        height = self.points @ self.n + self.offset
        flat = self.points - np.outer(height, self.n)
        nearest = np.empty(len(flat), int)
        track = self.track
        for start in range(0, len(flat), 150_000):
            chunk = flat[start:start + 150_000]
            nearest[start:start + 150_000] = ((chunk[:, None, :] - track[None, :, :]) ** 2).sum(-1).argmin(1)
        lateral_dir = np.cross(self.n, self.tangent[nearest])
        lateral = ((flat - track[nearest]) * lateral_dir).sum(1) * self.side
        keep = (lateral > 0.6) & (lateral < 32) & (height > -0.6) & (height < 9)
        self.backdrop = self.points[keep]
        grey = self.colours[keep].mean(1, keepdims=True)
        tinted = self.colours[keep] * 0.7 + grey * 0.3
        # The barrier and the shoulder between the track and the verge are rebuilt frame by frame
        # and fan out in saw-teeth; they stay visible as context, but quieter than the verge.
        bright = np.where(lateral[keep] < 4.5, 0.42, 1.0)[:, None]
        self.backdrop_colours = tinted * bright + np.array(BG) * (1 - bright)

    def frustum(self, index, depth=2.2):
        k = self.intrinsics[index]
        corners = []
        for x, y in [(0, 0), (self.depth_w, 0), (self.depth_w, self.depth_h), (0, self.depth_h)]:
            ray = np.array([(x - k[0, 2]) / k[0, 0], (y - k[1, 2]) / k[1, 1], 1.0]) * depth
            corners.append(self.cameras[index] + self.world_from_camera[index] @ ray)
        return self.cameras[index], np.array(corners)

    def overlays(self):
        """Every depth pixel each cell used, per frame, in the colour of the cell's verdict."""
        layers = np.zeros((len(self.cameras), self.depth_h * self.depth_w, 4), np.uint8)
        for cell in self.cells:
            colour = (*colour_of(cell), 255)
            for pixels in cell["pixels"]:
                runs = pixels["runs"]
                flat = np.concatenate([np.arange(s, s + n) for s, n in zip(runs[0::2], runs[1::2])]) if runs else []
                layers[pixels["frameIndex"], flat] = colour
        return layers.reshape(len(self.cameras), self.depth_h, self.depth_w, 4)


def colour_of(cell):
    if cell["status"] == "measured":
        return LEVEL[level_of(cell["extent95M"])]
    return STRUCTURE if cell["status"] == "structure" else SLOPE


# ---------------------------------------------------------------------------------- camera


def look_at(eye, target, up):
    forward = target - eye
    forward /= np.linalg.norm(forward)
    right = np.cross(forward, up)
    right /= np.linalg.norm(right)
    down = -np.cross(right, forward)
    return np.stack([right, down, forward])


def smoothstep(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


class Director:
    """Where the virtual camera stands at each moment of the animation."""

    def __init__(self, m: Measurement):
        self.m = m
        self.frames = len(m.cameras)
        # The closing shot flies over the stretch where all three verdicts sit side by side.
        along = np.array([c["coordinate"]["alongRoadM"] for c in m.cells])
        focus = (along > 62) & (along < 122)
        target = m.quads[focus].reshape(-1, 3).mean(0)
        k = int(np.argmin(((m.track - target) ** 2).sum(1)))
        verge = np.cross(m.n, m.tangent[k]) * m.side
        self.overview = (target - 10 * m.tangent[k] - 34 * verge + 24 * m.n, target + 2 * verge + 3 * m.n)

    def chase(self, tau):
        m = self.m
        i = int(math.floor(tau))
        j = min(i + 1, self.frames - 1)
        w = tau - i
        position = m.track[i] * (1 - w) + m.track[j] * w
        tangent = m.tangent[i] * (1 - w) + m.tangent[j] * w
        tangent /= np.linalg.norm(tangent)
        verge = np.cross(m.n, tangent) * m.side
        # High and behind: a cell is drawn once its third frame has passed, so the verge fills in
        # between this camera and the car, which drives on ahead of it.
        eye = position - 30 * tangent - 14 * verge + 22 * m.n
        target = position - 6 * tangent + 7 * verge
        return eye, target

    def at(self, t):
        """(tau, eye, target, card, everything): everything is the opacity of the finished carpet, or None."""
        last = self.frames - 1
        if t < OPEN_S:
            return last, *self.overview, 1.0, 1.0
        if t < OPEN_S + FLY_S:
            blend = smoothstep((t - OPEN_S) / FLY_S)
            eye0, target0 = self.chase(0.0)
            eye = self.overview[0] * (1 - blend) + eye0 * blend
            target = self.overview[1] * (1 - blend) + target0 * blend
            return 0.0, eye, target, 1.0 - smoothstep((t - OPEN_S) / 0.4), 1.0 - blend
        t -= OPEN_S + FLY_S
        if t <= DRIVE_S:
            # The last fifth of this clip passes under a viaduct with little verge to measure, so
            # the drive spends 86% of its time on the first 80% of the frames.
            split_t, split_tau = 0.86 * DRIVE_S, 0.8 * last
            tau = split_tau * t / split_t if t <= split_t else split_tau + (last - split_tau) * (t - split_t) / (DRIVE_S - split_t)
            return tau, *self.chase(tau), 0.0, None
        eye0, target0 = self.chase(last)
        blend = smoothstep((t - DRIVE_S) / PULL_S)
        eye = eye0 * (1 - blend) + self.overview[0] * blend
        target = target0 * (1 - blend) + self.overview[1] * blend
        card = smoothstep((t - DRIVE_S - PULL_S) / 0.6)
        return last, eye, target, card, None


# ---------------------------------------------------------------------------------- drawing


def project(points, eye, rotation, focal, cx, cy):
    q = (points - eye) @ rotation.T
    z = q[..., 2]
    with np.errstate(divide="ignore", invalid="ignore"):
        return focal * q[..., 0] / z + cx, focal * q[..., 1] / z + cy, z


def draw_view(m: Measurement, tau, eye, target, card, everything=None):
    w, h = VIEW_BOX[2] * SS, VIEW_BOX[3] * SS
    rotation = look_at(eye, target, m.n)
    focal = (w / 2) / math.tan(math.radians(34))
    cx, cy = w / 2, h / 2
    image = np.empty((h, w, 3), np.float32)
    image[:] = BG

    x, y, z = project(m.backdrop, eye, rotation, focal, cx, cy)
    visible = (z > 0.5) & (x >= 0) & (x < w - 2) & (y >= 0) & (y < h - 2)
    order = np.argsort(-z[visible])
    xi = x[visible][order].astype(int)
    yi = y[visible][order].astype(int)
    colours = m.backdrop_colours[visible][order]
    near = z[visible][order] < 45
    for dx, dy in [(0, 0), (1, 0), (0, 1), (1, 1)]:
        sel = near if (dx or dy) else slice(None)
        image[yi[sel] + dy, xi[sel] + dx] = colours[sel]
    canvas = Image.fromarray(image.clip(0, 255).astype(np.uint8)).convert("RGBA")

    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    qx, qy, qz = project(m.quads, eye, rotation, focal, cx, cy)
    revealed = np.ones(len(m.cells), bool) if everything is not None else (m.reveal <= tau)
    shown = np.where(revealed & (qz > 0.5).all(1))[0]
    for i in shown[np.argsort(-qz[shown].mean(1))]:
        fade = everything if everything is not None else min(1.0, (tau - m.reveal[i]) / 2.0 + 0.35)
        if fade <= 0.01:
            continue
        alpha = {"measured": 240, "structure": 225, "slope": 165}[m.cells[i]["status"]]
        draw.polygon(list(zip(qx[i], qy[i])), fill=(*colour_of(m.cells[i]), int(alpha * fade)))

    # The camera track, driven so far and still ahead.
    tx, ty, tz = project(m.track, eye, rotation, focal, cx, cy)
    k = int(math.floor(tau)) + 1
    for (start, stop), alpha in (((0, k), 190), ((k - 1, len(tx)), 45)):
        seg = [(a, b) for a, b, c in zip(tx[start:stop], ty[start:stop], tz[start:stop]) if c > 0.5]
        if len(seg) > 1:
            draw.line(seg, fill=(255, 255, 255, alpha), width=3 * SS // 2 + 1)
    if card < 0.99 and (everything is None or everything < 0.99):
        apex, corners = m.frustum(min(int(round(tau)), len(m.cameras) - 1))
        ax, ay, az = project(apex[None], eye, rotation, focal, cx, cy)
        fx, fy, fz = project(corners, eye, rotation, focal, cx, cy)
        if az[0] > 0.5 and (fz > 0.5).all():
            alpha = int(255 * (1 - card) * (1 - (everything or 0)))
            for c in range(4):
                draw.line([(ax[0], ay[0]), (fx[c], fy[c])], fill=(255, 255, 255, alpha), width=2 * SS)
                draw.line([(fx[c], fy[c]), (fx[(c + 1) % 4], fy[(c + 1) % 4])], fill=(255, 255, 255, alpha), width=2 * SS)
    canvas = Image.alpha_composite(canvas, layer)
    return canvas.resize((VIEW_BOX[2], VIEW_BOX[3]), Image.LANCZOS)


def draw_frame_panel(m: Measurement, index, overlays):
    x0, y0, w, h = FRAME_BOX
    frame = m.packet["frames"][index]
    photo = ImageOps.exif_transpose(Image.open(m.run_dir / "frames" / frame["file"])).convert("RGB").resize((w, h), Image.LANCZOS)
    dim = Image.blend(photo, Image.new("RGB", (w, h), BG), 0.18)
    layer = Image.fromarray(overlays[index], "RGBA").resize((w, h), Image.NEAREST)
    glow = layer.filter(ImageFilter.GaussianBlur(3))
    panel = Image.alpha_composite(dim.convert("RGBA"), glow)
    panel = Image.alpha_composite(panel, layer)
    return panel


def compose(m: Measurement, director: Director, t, overlays, totals):
    tau, eye, target, card, everything = director.at(t)
    canvas = Image.new("RGBA", (CANVAS_W, CANVAS_H), (*BG, 255))
    index = min(int(math.floor(tau)), len(m.cameras) - 1)
    canvas.alpha_composite(draw_frame_panel(m, index, overlays), (FRAME_BOX[0], FRAME_BOX[1]))
    view = draw_view(m, tau, eye, target, card, everything)
    canvas.alpha_composite(view, (VIEW_BOX[0], VIEW_BOX[1]))
    draw = ImageDraw.Draw(canvas)

    draw.text((FRAME_BOX[0], 18), "VÍDEO DO CARRO", font=font(22, True), fill=MUTED)
    draw.text((FRAME_BOX[0], FRAME_BOX[1] + FRAME_BOX[3] + 10), f"quadro {index + 1} de {len(m.cameras)}", font=font(22), fill=MUTED)
    draw.text((VIEW_BOX[0], 18), "RECONSTRUÇÃO 3D · CÉLULAS DE 0,5 m", font=font(22, True), fill=MUTED)

    counts = {"measured": 0, "structure": 0, "slope": 0}
    for i in (range(len(m.cells)) if everything is not None and everything > 0.5 else np.where(m.reveal <= tau)[0]):
        counts[m.cells[i]["status"]] += 1
    right = VIEW_BOX[0] + VIEW_BOX[2]
    label = f"medidas {counts['measured']}   ·   estrutura {counts['structure']}   ·   talude {counts['slope']}"
    draw.text((right, 18), label, font=font(22, True), fill=TEXT, anchor="ra")

    legend = [(LEVEL[1], "< 10 cm"), (LEVEL[2], "10–30 cm"), (LEVEL[3], "> 30 cm"),
              (STRUCTURE, "recusada: estrutura"), (SLOPE, "recusada: talude")]
    lx, ly = VIEW_BOX[0] + 18, VIEW_BOX[1] + VIEW_BOX[3] - 40
    backdrop = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    ImageDraw.Draw(backdrop).rounded_rectangle([lx - 10, ly - 10, lx + 780, ly + 34], radius=10, fill=(13, 12, 18, 200))
    canvas.alpha_composite(backdrop)
    for colour, text in legend:
        draw.rectangle([lx, ly + 3, lx + 18, ly + 21], fill=colour)
        draw.text((lx + 26, ly), text, font=font(20), fill=TEXT)
        lx += 26 + int(draw.textlength(text, font=font(20))) + 26

    if card > 0:
        a = int(255 * card)
        box = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
        bd = ImageDraw.Draw(box)
        bx0, by0, bx1, by1 = VIEW_BOX[0] + 24, VIEW_BOX[1] + 24, VIEW_BOX[0] + 660, VIEW_BOX[1] + 270
        bd.rounded_rectangle([bx0, by0, bx1, by1], radius=18, fill=(13, 12, 18, int(225 * card)), outline=(255, 255, 255, int(60 * card)), width=2)
        cxm = (bx0 + bx1) // 2
        bd.text((cxm, by0 + 28), f"{totals['measured']} células medidas", font=font(44, True), fill=(*TEXT, a), anchor="ma")
        bd.text((cxm, by0 + 92), f"em {totals['lengthM']:.0f} m, sem ninguém pintar máscara", font=font(24), fill=(*TEXT, a), anchor="ma")
        bd.text((cxm, by0 + 138), f"{totals['structure']} recusadas por estrutura  ·  {totals['slope']} por talude", font=font(24), fill=(*MUTED, a), anchor="ma")
        bd.text((cxm, by0 + 190), f"mediana {cm(totals['p50'])} cm  ·  p90 {cm(totals['p90'])} cm", font=font(26, True), fill=(*LEVEL[level_of(totals['p90'])], a), anchor="ma")
        canvas.alpha_composite(box)
    return canvas.convert("RGB")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--packet", required=True, type=Path)
    parser.add_argument("--runs", default=HOME / "verge-runs", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--stills", default="")
    args = parser.parse_args()

    m = Measurement(args.packet, args.runs)
    director = Director(m)
    overlays = m.overlays()
    measured = np.array([c["extent95M"] for c in m.cells if c["status"] == "measured"])
    totals = {
        "measured": int((np.array([c["status"] for c in m.cells]) == "measured").sum()),
        "structure": sum(c["status"] == "structure" for c in m.cells),
        "slope": sum(c["status"] == "slope" for c in m.cells),
        "lengthM": m.packet["corridor"]["lengthM"],
        "p50": float(np.percentile(measured, 50)),
        "p90": float(np.percentile(measured, 90)),
    }
    args.out.mkdir(parents=True, exist_ok=True)
    total_frames = int(round((OPEN_S + FLY_S + DRIVE_S + PULL_S + HOLD_S) * FPS))
    wanted = [int(s) for s in args.stills.split(",") if s] or range(total_frames)
    for n in wanted:
        compose(m, director, n / FPS, overlays, totals).save(args.out / f"frame-{n:04d}.png")
        print(f"frame {n + 1}/{total_frames}", flush=True)
    (args.out / "totals.json").write_text(json.dumps(totals, indent=2))
    if not args.stills:
        pattern = str(args.out / "frame-%04d.png")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", str(FPS), "-i", pattern,
                        "-c:v", "libx264", "-crf", "18", "-preset", "slow", "-pix_fmt", "yuv420p",
                        "-movflags", "+faststart", str(args.out / "automatic-measurement.mp4")], check=True)


if __name__ == "__main__":
    main()
