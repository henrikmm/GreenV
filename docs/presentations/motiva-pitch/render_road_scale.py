"""Render the tape of a roadside run inside its own 3D reconstruction, as a video.

The printed decimetre labels of the tape (the pink 10, 20, 30) are found in the measured frame,
lifted into 3D with that frame's depth map and cameras, and their distance is measured there. A
virtual ruler is then drawn along the tape in 3D, so from the recording camera its ticks land on
the printed labels only if the reconstruction's metres are real ones, and it stays on the tape as
the camera moves around it. The saved trials of the run supply the grass reading drawn afterwards.

Inputs, all local and read-only: `~/verge-runs/<runId>` (verge-result.npz, scene.glb for the
alignment, frames/, measurements/). Nothing is imported from `measurement/`.

    python render_road_scale.py rodovia_medida1 --out <dir>                      # the video
    python render_road_scale.py rodovia_medida1 --out <dir> --stills 0,4,12,17   # a few frames
    python render_road_scale.py rodovia_medida1 --out <dir> --scan 50,86         # label gaps per frame
    python render_road_scale.py rodovia_movimento --out <dir> --compare          # DA3's cloud vs rebuilt

The comparison explains the holes in the recordings: DA3's exporter takes one confidence floor for
the whole clip, `min(max(1.05, p40), p90)` over every pixel of every frame (the rule described in
`measurement/geometry/cloud.ts`), then keeps a random million of whatever survives.

Needs numpy, Pillow and moderngl (a headless OpenGL 4.1 context), plus ffmpeg on PATH.
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import subprocess
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

RUNS = Path.home() / "verge-runs"
FONTS = Path("/System/Library/Fonts/Supplemental")
W, H, FPS = 1920, 1080, 30

BG = (13, 12, 18)
TEXT = (244, 247, 243)
MUTED = (143, 168, 154)
RULER = (255, 214, 10)
GRASS = (255, 128, 0)

PHOTO_BOX = (60, 118, 484, 860)
VIEW_BOX = (574, 118, 640, 860)
TEXT_X = 1270

CLIPS = {
    # Label rows were read off the frame by eye; the script finds each label's pixels inside them.
    "rodovia_medida1": dict(run="20260914-144411-5032ee", frame=74, labels=[(10, 614, 628), (20, 465, 479), (30, 309, 324)],
                            zoom=1.4, centre=(255, 470)),
    "rodovia_movimento": dict(run="20260914-143905-ce30bc", frame=92, labels=[(10, 601, 622), (20, 373, 396)],
                              zoom=1.25, centre=(300, 520)),
}

# Seconds. The finished state first, so a slide's still frame and the loop seam both show it.
HOLD, ORBIT, GRASS_IN, GRASS_HOLD, GRASS_OUT = 2.5, 8.0, 1.0, 4.5, 1.0
# Beyond about 15 degrees the single measured frame opens gaps wider than its own gentle stretch can fill.
ORBIT_DEG = 14.0
TOTAL = HOLD + ORBIT + GRASS_IN + GRASS_HOLD + GRASS_OUT


def font(size, bold=False):
    return ImageFont.truetype(str(FONTS / ("Arial Bold.ttf" if bold else "Arial.ttf")), size)


def cm(value, digits=1):
    return f"{value:.{digits}f}".replace(".", ",")


def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


# ------------------------------------------------------------------------------ the run

def read_alignment(path: Path) -> np.ndarray:
    data = path.read_bytes()
    length = struct.unpack("<I", data[12:16])[0]
    gltf = json.loads(data[20:20 + length])
    return np.array(gltf["scenes"][gltf.get("scene", 0)]["extras"]["hf_alignment"], dtype=np.float64)


def decode_runs(runs, size):
    mask = np.zeros(size, np.uint8)
    for start, length in zip(runs[0::2], runs[1::2]):
        mask[start:start + length] = 1
    return mask


class Run:
    def __init__(self, run_id: str):
        self.dir = RUNS / run_id
        arrays = np.load(self.dir / "verge-result.npz")
        self.depth = arrays["depth"]
        self.ext = arrays["extrinsics"].astype(np.float64)
        self.K = arrays["intrinsics"].astype(np.float64)
        self.n, self.h, self.w = self.depth.shape
        self.align = read_alignment(self.dir / "scene.glb")
        self.frames = sorted((self.dir / "frames").glob("frame-*.jpg"))
        assert len(self.frames) == self.n
        self.packets = [json.loads(p.read_text()) for p in sorted((self.dir / "measurements").glob("*.json"))]
        rotation, translation = self.ext[:, :3, :3], self.ext[:, :3, 3]
        centres = -np.einsum("nji,nj->ni", rotation, translation)
        self.cameras = centres @ self.align[:3, :3].T + self.align[:3, 3]
        self.world_from_camera = np.einsum("ij,njk->nik", self.align[:3, :3], np.transpose(rotation, (0, 2, 1)))
        self.photo_w, self.photo_h = Image.open(self.frames[0]).size

    def photo(self, f, size=None):
        image = Image.open(self.frames[f]).convert("RGB")
        return np.asarray(image.resize(size, Image.BOX) if size else image)

    def to_world(self, f, x_low, y_low, depth):
        """Depth-grid pixel coordinates and metres to the aligned frame, as the app back-projects."""
        k = self.K[f]
        cam = np.stack([(x_low - k[0, 2]) * depth / k[0, 0], (y_low - k[1, 2]) * depth / k[1, 1], depth], -1)
        world = (cam - self.ext[f, :3, 3]) @ self.ext[f, :3, :3]
        return world @ self.align[:3, :3].T + self.align[:3, 3]

    def photo_to_grid(self, x, y):
        return (np.asarray(x) + 0.5) * self.w / self.photo_w - 0.5, (np.asarray(y) + 0.5) * self.h / self.photo_h - 0.5

    def valid(self, f, edge=0.05, max_depth=np.inf):
        d = self.depth[f]
        ok = np.isfinite(d) & (d > 1e-4) & (d < max_depth)
        pad = np.pad(d, 1, mode="edge")
        for neighbour in (pad[1:-1, :-2], pad[1:-1, 2:], pad[:-2, 1:-1], pad[2:, 1:-1]):
            ok &= np.abs(neighbour - d) <= np.abs(d) * edge
        return ok

    def labels(self, f, rows):
        """Each printed label's pink pixels inside its row band, lifted to 3D at their median depth."""
        photo = self.photo(f).astype(int)
        r, g, b = photo[..., 0], photo[..., 1], photo[..., 2]
        pink = (r > 170) & (g < 110) & (b > 90) & (r - g > 90)
        out = {}
        for value, top, bottom in rows:
            ys, xs = np.nonzero(pink[top:bottom + 1])
            ys = ys + top
            gx, gy = self.photo_to_grid(xs, ys)
            depth = float(np.median(self.depth[f][np.clip(np.round(gy).astype(int), 0, self.h - 1),
                                                  np.clip(np.round(gx).astype(int), 0, self.w - 1)]))
            cx, cy = self.photo_to_grid(xs.mean(), ys.mean())
            out[value] = dict(pixel=(float(xs.mean()), float(ys.mean())), depth=depth,
                              point=self.to_world(f, np.array(cx), np.array(cy), np.array(depth)))
        return out

    def label_blobs(self, f):
        """Compact pink blocks the width of the tape, top to bottom: printed labels, unnamed."""
        photo = self.photo(f).astype(int)
        r, g, b = photo[..., 0], photo[..., 1], photo[..., 2]
        ys, xs = np.nonzero((r > 170) & (g < 110) & (b > 90) & (r - g > 90))
        order = np.argsort(ys)
        ys, xs = ys[order], xs[order]
        found, start = [], 0
        for i in range(1, len(ys) + 1):
            if i == len(ys) or ys[i] - ys[i - 1] > 6:
                bx, by = xs[start:i], ys[start:i]
                # A label cut by the frame's edge has a false centre.
                inside = by.min() > 15 and by.max() < self.photo_h - 15
                if inside and i - start >= 40 and np.ptp(bx) < 40 and np.ptp(by) < 40:
                    gx, gy = self.photo_to_grid(bx, by)
                    depth = float(np.median(self.depth[f][np.clip(np.round(gy).astype(int), 0, self.h - 1),
                                                          np.clip(np.round(gx).astype(int), 0, self.w - 1)]))
                    cx, cy = self.photo_to_grid(bx.mean(), by.mean())
                    found.append((float(by.mean()), depth, self.to_world(f, np.array(cx), np.array(cy), np.array(depth))))
                start = i
        return found

    def cloud(self, voxel=0.004, max_depth=np.inf):
        """Every frame back-projected with photo colour, edge pixels dropped, fused into voxels."""
        points, colours, radii = [], [], []
        for f in range(self.n):
            ok = self.valid(f, max_depth=max_depth)
            ys, xs = np.nonzero(ok)
            d = self.depth[f][ok].astype(np.float64)
            points.append(self.to_world(f, xs, ys, d).astype(np.float32))
            colours.append(self.photo(f, (self.w, self.h))[ok])
            radii.append((0.75 * d / self.K[f, 0, 0]).astype(np.float32))
        p, c, r = np.concatenate(points), np.concatenate(colours), np.concatenate(radii)
        keys = np.floor(p / voxel).astype(np.int64)
        keys -= keys.min(0)
        dims = keys.max(0) + 1
        linear = (keys[:, 0] * dims[1] + keys[:, 1]) * dims[2] + keys[:, 2]
        order = np.argsort(linear, kind="stable")
        starts = np.flatnonzero(np.r_[True, linear[order][1:] != linear[order][:-1]])
        counts = np.diff(np.r_[starts, len(linear)])

        def mean(a):
            return np.add.reduceat(a[order].astype(np.float64), starts, axis=0) / (counts[:, None] if a.ndim > 1 else counts)

        return mean(p).astype(np.float32), mean(c).astype(np.uint8), np.maximum(mean(r), voxel * 0.6).astype(np.float32)

    def glb(self):
        data = (self.dir / "scene.glb").read_bytes()
        length = struct.unpack("<I", data[12:16])[0]
        gltf = json.loads(data[20:20 + length])
        base = 20 + length + 8

        def accessor(index):
            acc = gltf["accessors"][index]
            view = gltf["bufferViews"][acc["bufferView"]]
            width = {"VEC3": 3, "VEC4": 4}[acc["type"]]
            dtype = {5126: np.float32, 5121: np.uint8}[acc["componentType"]]
            return np.frombuffer(data, dtype=dtype, count=acc["count"] * width,
                                 offset=base + view.get("byteOffset", 0) + acc.get("byteOffset", 0)).reshape(-1, width)

        for mesh in gltf["meshes"]:
            for primitive in mesh["primitives"]:
                if primitive.get("mode") == 0:
                    colours = accessor(primitive["attributes"]["COLOR_0"])[:, :3]
                    if colours.dtype != np.uint8:
                        colours = (np.clip(colours, 0, 1) * 255).astype(np.uint8)
                    return accessor(primitive["attributes"]["POSITION"]).astype(np.float32), colours
        raise ValueError("no point primitive in scene.glb")

    def mesh(self, f, max_ratio=1.04):
        d = self.depth[f].astype(np.float64)
        ys, xs = np.mgrid[0:self.h, 0:self.w]
        vertices = self.to_world(f, xs, ys, d).reshape(-1, 3).astype(np.float32)
        uvs = np.stack([(xs + 0.5) / self.w, (ys + 0.5) / self.h], -1).reshape(-1, 2).astype(np.float32)
        index = np.arange(self.h * self.w).reshape(self.h, self.w)
        a, b, c, e = index[:-1, :-1], index[:-1, 1:], index[1:, :-1], index[1:, 1:]
        triangles = np.concatenate([np.stack([a, c, b], -1).reshape(-1, 3), np.stack([b, c, e], -1).reshape(-1, 3)])
        corner = d.reshape(-1)[triangles]
        ok = np.isfinite(corner).all(1) & (corner.min(1) > 1e-4) & (corner.max(1) <= corner.min(1) * max_ratio)
        return vertices, uvs, triangles[ok].astype(np.uint32)

    def grass(self, packet):
        """The trial's painted pixels at photo resolution, lifted with bilinear depth."""
        o = packet["observation"]
        m = o["mask"]
        painted = decode_runs(m["runs"], m["width"] * m["height"]).reshape(m["height"], m["width"]).astype(bool)
        f = o["npzFrame"]
        ok = self.valid(f)
        core = ok & np.roll(ok, 1, 0) & np.roll(ok, -1, 0) & np.roll(ok, 1, 1) & np.roll(ok, -1, 1)
        depth = np.asarray(Image.fromarray(self.depth[f].astype(np.float32)).resize((m["width"], m["height"]), Image.BILINEAR))
        keep = np.asarray(Image.fromarray(core.astype(np.uint8) * 255).resize((m["width"], m["height"]), Image.NEAREST)) > 0
        ys, xs = np.nonzero(painted & keep)
        gx, gy = self.photo_to_grid(xs, ys)
        return painted, self.to_world(f, gx, gy, depth[ys, xs].astype(np.float64)).astype(np.float32)


# ------------------------------------------------------------------------------ cameras

def look_at(eye, target, up):
    forward = target - eye
    forward = forward / np.linalg.norm(forward)
    side = np.cross(forward, up)
    side /= np.linalg.norm(side)
    view = np.eye(4)
    view[0, :3], view[1, :3], view[2, :3] = side, np.cross(side, forward), -forward
    view[:3, 3] = -view[:3, :3] @ eye
    return view


def view_from_camera(centre, world_from_camera):
    view = np.eye(4)
    view[:3, :3] = np.diag([1.0, -1.0, -1.0]) @ world_from_camera.T
    view[:3, 3] = -view[:3, :3] @ centre
    return view


def perspective(focal, width, height, cx, cy, near=0.02, far=400.0):
    proj = np.zeros((4, 4))
    proj[0, 0], proj[1, 1] = 2 * focal / width, 2 * focal / height
    proj[0, 2], proj[1, 2] = 1 - 2 * cx / width, 2 * cy / height - 1
    proj[2, 2], proj[2, 3], proj[3, 2] = -(far + near) / (far - near), -2 * far * near / (far - near), -1
    return proj


def rotate(vector, axis, angle):
    axis = axis / np.linalg.norm(axis)
    return vector * math.cos(angle) + np.cross(axis, vector) * math.sin(angle) + axis * (axis @ vector) * (1 - math.cos(angle))


# ------------------------------------------------------------------------------ GPU

POINT_VS = """
#version 410
uniform mat4 mvp; uniform mat4 mv; uniform float focal; uniform float scale; uniform float max_px;
in vec3 in_pos; in vec3 in_col; in float in_radius;
out vec3 v_col; out float v_depth;
void main() {
    vec4 view = mv * vec4(in_pos, 1.0);
    float z = max(-view.z, 1e-4);
    v_depth = z; v_col = in_col;
    gl_Position = mvp * vec4(in_pos, 1.0);
    gl_PointSize = clamp(2.0 * in_radius * scale * focal / z, 1.0, max_px);
}
"""
POINT_FS = """
#version 410
uniform float fade;
in vec3 v_col; in float v_depth;
layout(location = 0) out vec4 f_col; layout(location = 1) out vec4 f_depth;
void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    if (dot(c, c) > 1.0) discard;
    f_col = vec4(v_col * fade, 1.0);
    f_depth = vec4(log2(v_depth), 1.0, 0.0, 0.0);
}
"""
MESH_VS = """
#version 410
uniform mat4 mvp; uniform mat4 mv;
in vec3 in_pos; in vec2 in_uv;
out vec2 v_uv; out float v_depth;
void main() {
    v_depth = max(-(mv * vec4(in_pos, 1.0)).z, 1e-4);
    v_uv = in_uv;
    gl_Position = mvp * vec4(in_pos, 1.0);
}
"""
MESH_FS = """
#version 410
uniform sampler2D photo;
in vec2 v_uv; in float v_depth;
layout(location = 0) out vec4 f_col; layout(location = 1) out vec4 f_depth;
void main() {
    f_col = vec4(texture(photo, v_uv).rgb, 1.0);
    f_depth = vec4(log2(v_depth), 1.0, 0.0, 0.0);
}
"""
QUAD_VS = """
#version 410
in vec2 in_pos; out vec2 uv;
void main() { uv = in_pos * 0.5 + 0.5; gl_Position = vec4(in_pos, 0.0, 1.0); }
"""
EDL_FS = """
#version 410
uniform sampler2D color_tex; uniform sampler2D depth_tex;
uniform sampler2D fill_color; uniform sampler2D fill_depth; uniform int use_fill;
uniform vec2 texel; uniform float strength; uniform float radius; uniform vec3 background;
in vec2 uv; out vec4 out_col;
float depth_at(vec2 p, float fallback) {
    vec4 main = texture(depth_tex, p);
    if (main.g > 0.5) return main.r;
    vec4 filled = texture(fill_depth, p);
    return (use_fill == 1 && filled.g > 0.5) ? filled.r : fallback;
}
void main() {
    vec4 d = texture(depth_tex, uv);
    if (d.g < 0.5) {
        bool filled = use_fill == 1 && texture(fill_depth, uv).g > 0.5;
        out_col = vec4(filled ? texture(fill_color, uv).rgb : background, 1.0);
        return;
    }
    float sum = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = 6.2831853 * float(i) / 8.0;
        sum += max(0.0, d.r - depth_at(uv + vec2(cos(a), sin(a)) * texel * radius, d.r + 4.0));
    }
    out_col = vec4(texture(color_tex, uv).rgb * exp(-sum / 8.0 * strength), 1.0);
}
"""


class Renderer:
    def __init__(self, supersample=2):
        self.ctx = moderngl.create_standalone_context(require=410)
        self.ss = supersample
        self.targets = {}
        self.point_prog = self.ctx.program(vertex_shader=POINT_VS, fragment_shader=POINT_FS)
        self.mesh_prog = self.ctx.program(vertex_shader=MESH_VS, fragment_shader=MESH_FS)
        self.edl = self.ctx.program(vertex_shader=QUAD_VS, fragment_shader=EDL_FS)
        quad = self.ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], "f4").tobytes())
        self.quad = self.ctx.vertex_array(self.edl, [(quad, "2f", "in_pos")])
        self.items = {}

    def target(self, width, height, key=""):
        name = (width, height, key)
        if name not in self.targets:
            size = (width * self.ss, height * self.ss)
            color, depth = self.ctx.texture(size, 4), self.ctx.texture(size, 4, dtype="f4")
            # A float texture is not filterable everywhere; sampled linearly it reads back as zero.
            depth.filter = (moderngl.NEAREST, moderngl.NEAREST)
            fbo = self.ctx.framebuffer([color, depth], self.ctx.depth_renderbuffer(size))
            out = self.ctx.texture(size, 4)
            self.targets[name] = (color, depth, fbo, out, self.ctx.framebuffer([out]))
        return self.targets[name]

    def draw(self, fbo, size, view, proj, layers):
        fbo.use()
        self.ctx.viewport = (0, 0, *size)
        self.ctx.enable(moderngl.PROGRAM_POINT_SIZE | moderngl.DEPTH_TEST)
        fbo.clear(0, 0, 0, 0, depth=1.0)
        mvp, mv = (proj @ view).astype("f4").T.tobytes(), view.astype("f4").T.tobytes()
        for name, scale, max_px, fade in layers:
            kind, vao, texture = self.items[name]
            program = self.point_prog if kind == "points" else self.mesh_prog
            program["mvp"].write(mvp)
            program["mv"].write(mv)
            if kind == "points":
                program["focal"].value = float(proj[1, 1] * size[1] / 2)
                program["scale"].value = float(scale)
                program["max_px"].value = float(max_px * self.ss)
                program["fade"].value = float(fade)
                vao.render(moderngl.POINTS)
            else:
                texture.use(2)
                program["photo"].value = 2
                vao.render(moderngl.TRIANGLES)

    def points(self, name, positions, colours, radii):
        buffers = [self.ctx.buffer(np.ascontiguousarray(positions, "f4").tobytes()),
                   self.ctx.buffer((np.asarray(colours, "f4") / 255.0).tobytes()),
                   self.ctx.buffer(np.ascontiguousarray(np.broadcast_to(radii, (len(positions),)), "f4").tobytes())]
        vao = self.ctx.vertex_array(self.point_prog, [(buffers[0], "3f", "in_pos"), (buffers[1], "3f", "in_col"), (buffers[2], "1f", "in_radius")])
        self.items[name] = ("points", vao, None)

    def mesh(self, name, vertices, uvs, triangles, photo):
        texture = self.ctx.texture((photo.shape[1], photo.shape[0]), 3, np.ascontiguousarray(photo[::-1]).tobytes())
        texture.build_mipmaps()
        texture.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
        flipped = uvs.copy()
        flipped[:, 1] = 1 - flipped[:, 1]
        vao = self.ctx.vertex_array(self.mesh_prog, [(self.ctx.buffer(np.ascontiguousarray(vertices, "f4").tobytes()), "3f", "in_pos"),
                                                     (self.ctx.buffer(np.ascontiguousarray(flipped, "f4").tobytes()), "2f", "in_uv")],
                                    index_buffer=self.ctx.buffer(np.ascontiguousarray(triangles, "u4").tobytes()), index_element_size=4)
        self.items[name] = ("mesh", vao, texture)

    def render(self, width, height, view, proj, layers, fill=None, edl=40.0):
        """layers: (name, splat scale, max splat px, fade). fill: layers seen only where layers left a gap."""
        color, depth, fbo, out, out_fbo = self.target(width, height)
        size = (width * self.ss, height * self.ss)
        if fill:
            fill_color, fill_depth, fill_fbo, _, _ = self.target(width, height, "fill")
            self.draw(fill_fbo, size, view, proj, fill)
        else:
            # Unused, but a sampler with nothing bound makes the driver complain on every draw.
            fill_color, fill_depth = color, depth
        self.draw(fbo, size, view, proj, layers)
        fill_color.use(3)
        fill_depth.use(4)
        out_fbo.use()
        self.ctx.disable(moderngl.DEPTH_TEST)
        color.use(0)
        depth.use(1)
        self.edl["color_tex"].value, self.edl["depth_tex"].value = 0, 1
        self.edl["fill_color"].value, self.edl["fill_depth"].value = 3, 4
        self.edl["use_fill"].value = 1 if fill else 0
        self.edl["texel"].value = (1.0 / size[0], 1.0 / size[1])
        self.edl["strength"].value, self.edl["radius"].value = float(edl), float(1.4 * self.ss)
        self.edl["background"].value = tuple(v / 255 for v in BG)
        self.quad.render(moderngl.TRIANGLE_STRIP)
        raw = np.frombuffer(out.read(), np.uint8).reshape(size[1], size[0], 4)[::-1, :, :3]
        return Image.fromarray(raw).resize((width, height), Image.LANCZOS)


def project(view, proj, width, height, points):
    h = np.c_[points, np.ones(len(points))] @ (proj @ view).T
    ndc = h[:, :3] / h[:, 3:4]
    return np.stack([(ndc[:, 0] * 0.5 + 0.5) * width, (0.5 - ndc[:, 1] * 0.5) * height], 1)


# ------------------------------------------------------------------------------ the video

class Film:
    def __init__(self, clip: str):
        cfg = CLIPS[clip]
        self.clip, self.cfg = clip, cfg
        self.run = run = Run(cfg["run"])
        f = self.f = cfg["frame"]
        self.labels = run.labels(f, cfg["labels"])
        values = sorted(self.labels)
        self.gaps = [float(np.linalg.norm(self.labels[b]["point"] - self.labels[a]["point"]) * 100) for a, b in zip(values, values[1:])]
        low, high = self.labels[values[0]]["point"], self.labels[values[-1]]["point"]
        self.axis = (high - low) / np.linalg.norm(high - low)
        self.low_value = values[0]
        self.origin = low

        trials = [p for p in run.packets if p["observation"]["npzFrame"] == f]
        self.trials = [p["observation"]["rawM"] * 100 for p in trials]
        self.truth = trials[0]["target"]["truthM"] * 100
        self.bracket = (np.mean([p["observation"]["ruler"]["bottom"] for p in trials], 0),
                        np.mean([p["observation"]["ruler"]["top"] for p in trials], 0))
        self.normal = np.array(trials[0]["live"]["ground"]["plane"]["normal"])
        self.normal /= np.linalg.norm(self.normal)
        painted, grass_points = run.grass(trials[0])
        self.painted = painted

        self.gpu = Renderer()
        vertices, uvs, triangles = run.mesh(f)
        self.gpu.mesh("frame", vertices, uvs, triangles, run.photo(f))
        # Small depth steps inside the grass may stretch to close their seams; the tape's edge may not.
        # Neighbouring frames would fill more, but they carry the hand and the tape in other places.
        vertices, uvs, triangles = run.mesh(f, max_ratio=1.12)
        self.gpu.mesh("seams", vertices, uvs, triangles, run.photo(f))
        self.gpu.points("grass", grass_points, np.tile([[255, 128, 0]], (len(grass_points), 1)), 0.0009)

        k = run.K[f]
        s_photo = run.photo_h / run.h
        self.fy_photo, self.cx_photo, self.cy_photo = k[1, 1] * s_photo, (k[0, 2] + 0.5) * run.photo_w / run.w - 0.5, (k[1, 2] + 0.5) * s_photo - 0.5
        self.source_view = view_from_camera(run.cameras[f], run.world_from_camera[f])
        self.pivot = self.labels[values[len(values) // 2]]["point"]
        right = run.world_from_camera[f][:, 0]
        self.side = np.cross(self.axis, np.cross(right, self.axis))
        self.side /= np.linalg.norm(self.side)

        photo = Image.fromarray(run.photo(f)).resize(PHOTO_BOX[2:], Image.LANCZOS)
        self.photo_panel = photo
        mask = Image.fromarray((painted * 255).astype(np.uint8)).resize(PHOTO_BOX[2:], Image.NEAREST)
        tint = Image.new("RGB", PHOTO_BOX[2:], GRASS)
        self.photo_grass = Image.composite(Image.blend(photo, tint, 0.55), photo, mask)

    # A camera around the tape: the recording pose rotated about the ground normal through the tape.
    def tape_camera(self, angle_deg):
        pw, ph = VIEW_BOX[2:]
        zoom = self.cfg["zoom"] * ph / self.run.photo_h
        u0, v0 = self.cfg["centre"]
        proj = perspective(self.fy_photo * zoom, pw, ph, (self.cx_photo - u0) * zoom + pw / 2, (self.cy_photo - v0) * zoom + ph / 2)
        if abs(angle_deg) < 1e-6:
            return self.source_view, proj
        eye = self.pivot + rotate(self.run.cameras[self.f] - self.pivot, self.normal, math.radians(angle_deg))
        # Keep the recording camera's own tilt: rotate its whole frame, not just its position.
        R = np.stack([rotate(self.run.world_from_camera[self.f][:, i], self.normal, math.radians(angle_deg)) for i in range(3)], 1)
        return view_from_camera(eye, R), proj

    @staticmethod
    def state(t):
        t = t % TOTAL
        s = dict(angle=0.0, grass=0.0)
        grass_start = HOLD + ORBIT
        if HOLD <= t < grass_start:
            s["angle"] = ORBIT_DEG * math.sin(2 * math.pi * (t - HOLD) / ORBIT)
        elif t >= grass_start:
            into = t - grass_start
            s["grass"] = smooth(into / GRASS_IN) if into < GRASS_IN + GRASS_HOLD else 1 - smooth((into - GRASS_IN - GRASS_HOLD) / GRASS_OUT)
        return s

    def ruler(self, draw, view, proj, alpha):
        pw, ph = VIEW_BOX[2:]
        top_value = max(self.labels) + 4
        marks = list(range(self.low_value - 9, top_value + 1))
        base = np.array([self.origin + self.axis * (m - self.low_value) / 100 + self.side * 0.02 for m in marks])
        tip = np.array([b + self.side * (0.014 if m % 10 == 0 else 0.009 if m % 5 == 0 else 0.005) for b, m in zip(base, marks)])
        sb, st = project(view, proj, pw, ph, base) * 2, project(view, proj, pw, ph, tip) * 2
        colour = (*RULER, int(255 * alpha))
        shadow = (10, 10, 10, int(200 * alpha))
        draw.line([tuple(sb[0]), tuple(sb[-1])], fill=shadow, width=10)
        draw.line([tuple(sb[0]), tuple(sb[-1])], fill=colour, width=5)
        label_font = font(46, True)
        for m, a, b in zip(marks, sb, st):
            draw.line([tuple(a), tuple(b)], fill=shadow, width=8)
            draw.line([tuple(a), tuple(b)], fill=colour, width=4 if m % 10 == 0 else 3)
            if m % 10 == 0:
                draw.text((b[0] + 12, b[1] - 27), str(m), font=label_font, fill=colour, stroke_width=5, stroke_fill=shadow)

    def bracket_overlay(self, draw, view, proj, alpha):
        """The trials' own extent, bottom to top along the ground normal, beside the painted grass."""
        pw, ph = VIEW_BOX[2:]
        bottom, top = self.bracket
        lateral = -self.side * 0.035
        cap = self.side * 0.012
        pts = np.array([bottom + lateral, top + lateral, bottom + lateral - cap, bottom + lateral + cap,
                        top + lateral - cap, top + lateral + cap])
        s = project(view, proj, pw, ph, pts) * 2
        colour, shadow = (*GRASS, int(255 * alpha)), (10, 10, 10, int(210 * alpha))
        for a, b in ((0, 1), (2, 3), (4, 5)):
            draw.line([tuple(s[a]), tuple(s[b])], fill=shadow, width=14)
            draw.line([tuple(s[a]), tuple(s[b])], fill=colour, width=7)
        label = f"{cm(np.mean(self.trials))} cm"
        label_font = font(60, True)
        box = draw.textbbox((0, 0), label, font=label_font)
        x = min(s[2][0], s[4][0]) - (box[2] - box[0]) - 34
        y = (s[0][1] + s[1][1]) / 2 - (box[3] - box[1]) / 2 - box[1]
        draw.rounded_rectangle([x - 16, y + box[1] - 12, x + box[2] + 16, y + box[3] + 12], radius=14,
                               fill=(13, 12, 18, int(215 * alpha)))
        draw.text((x, y), label, font=label_font, fill=colour)

    def frame(self, t):
        s = self.state(t)
        canvas = Image.new("RGB", (W, H), BG)
        draw = ImageDraw.Draw(canvas)
        f_label = font(22, True)
        draw.text((PHOTO_BOX[0], 70), f"VÍDEO · QUADRO {self.f} DE {self.run.n}", font=f_label, fill=MUTED)
        draw.text((VIEW_BOX[0], 70), "O MESMO QUADRO EM 3D", font=f_label, fill=MUTED)

        photo = Image.blend(self.photo_panel, self.photo_grass, s["grass"]) if s["grass"] > 0 else self.photo_panel
        canvas.paste(photo, PHOTO_BOX[:2])
        view, proj = self.tape_camera(s["angle"])
        pw, ph = VIEW_BOX[2:]
        fill = [("seams", 1, 1, 1)]
        tape = self.gpu.render(pw, ph, view, proj, [("frame", 1, 1, 1)], fill=fill)
        if s["grass"] > 0:
            # Opaque splats cannot fade on their own; the picture with them fades over the one without.
            with_grass = self.gpu.render(pw, ph, view, proj, [("frame", 1, 1, 1), ("grass", 1.6, 6, 1.0)], fill=fill)
            tape = Image.blend(tape, with_grass, s["grass"])
        # Overlays drawn at twice the size and reduced, so the ruler's thin ticks stay smooth.
        overlay = Image.new("RGBA", (pw * 2, ph * 2), (0, 0, 0, 0))
        odraw = ImageDraw.Draw(overlay)
        self.ruler(odraw, view, proj, 1.0)
        if s["grass"] > 0:
            self.bracket_overlay(odraw, view, proj, s["grass"])
        tape = Image.alpha_composite(tape.convert("RGBA"), overlay.resize((pw, ph), Image.LANCZOS)).convert("RGB")
        canvas.paste(tape, VIEW_BOX[:2])

        self.text_column(canvas, s)
        return canvas

    def text_column(self, canvas, s):
        draw = ImageDraw.Draw(canvas)
        x = TEXT_X
        draw.text((x, 70), "A TRENA DENTRO DO 3D", font=font(22, True), fill=MUTED)
        draw.text((x, 140), " · ".join(f"{cm(g)} cm" for g in self.gaps), font=font(64, True), fill=RULER)
        draw.multiline_text((x, 230), "entre as marcas de 10 em 10 cm\nda trena real, medidas no\nmodelo 3D deste quadro",
                            font=font(30), fill=TEXT, spacing=10)
        worst = max(abs(g - 10) / 10 for g in self.gaps)
        if worst <= 0.06:
            verdict = "A régua amarela foi traçada no\n3D, em centímetros, e cai sobre\nas marcas rosa da trena."
        else:
            verdict = (f"A régua amarela foi traçada no\n3D, em centímetros, e não cai\nsobre as marcas rosa: aqui o\n"
                       f"modelo estica a trena {cm(100 * (np.mean(self.gaps) / 10 - 1), 0)}%.")
        draw.multiline_text((x, 385), verdict, font=font(26), fill=MUTED, spacing=9)
        g = s["grass"]
        if g > 0:
            colour = tuple(int(BG[i] + (GRASS[i] - BG[i]) * g) for i in range(3))
            text = tuple(int(BG[i] + (TEXT[i] - BG[i]) * g) for i in range(3))
            draw.text((x, 560), f"{cm(np.mean(self.trials))} cm", font=font(64, True), fill=colour)
            draw.multiline_text((x, 650), f"altura da vegetação pintada ao\nlado da trena, média de {len(self.trials)} ensaios\n"
                                f"(na trena: {cm(self.truth, 0)} cm)", font=font(30), fill=text, spacing=10)
        draw.multiline_text((x, 900), f"{self.clip} · 14/09/2026\nprofundidade DA3, escala do próprio modelo",
                            font=font(22), fill=MUTED, spacing=8)


def scan(clip, first, last):
    """Distance in 3D between consecutive printed labels, wherever a frame shows two of them."""
    run = Run(CLIPS[clip]["run"])
    gaps = []
    for f in range(first, last + 1):
        blobs = run.label_blobs(f)
        row = []
        for (y1, d1, p1), (y2, d2, p2) in zip(blobs, blobs[1:]):
            # Consecutive labels are 10 cm apart on the tape and 100-250 px apart in these frames.
            if 90 < y2 - y1 < 260:
                gap = float(np.linalg.norm(p1 - p2) * 100)
                gaps.append(gap)
                row.append(f"{gap:.2f} cm (rows {y1:.0f}-{y2:.0f}, depth {d1:.2f}-{d2:.2f} m)")
        if row:
            print(f"frame {f}: " + "; ".join(row))
    print(f"{len(gaps)} gaps of 10 cm on the tape: median {np.median(gaps):.2f} cm, range {min(gaps):.2f}-{max(gaps):.2f} cm")


def compare(clip, out: Path):
    run = Run(CLIPS[clip]["run"])
    conf = np.load(run.dir / "verge-result.npz")["confidence"]
    floor = min(max(1.05, float(np.percentile(conf, 40))), float(np.percentile(conf, 90)))
    starved = int(((conf >= floor).reshape(run.n, -1).mean(1) < 0.10).sum())
    points, colours, radii = run.cloud()
    gpu = Renderer()
    glb_points, glb_colours = run.glb()
    gpu.points("glb", glb_points, glb_colours, 0.004)
    gpu.points("rebuilt", points, colours, radii)
    up = np.array([0.0, 1.0, 0.0])
    low, high = np.percentile(points, 2, axis=0), np.percentile(points, 98, axis=0)
    centre, extent = (low + high) / 2, float(np.linalg.norm(high - low))
    path = run.cameras[-1] - run.cameras[0]
    path -= up * (path @ up)
    path /= np.linalg.norm(path)
    side = np.cross(path, up)
    view = look_at(centre - side * 0.75 * extent + up * 0.55 * extent - path * 0.1 * extent, centre, up)
    pw, ph = 900, 800
    proj = perspective(560, pw, ph, pw / 2, ph / 2)
    canvas = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((40, 36), f"{clip.upper()} · MESMA RUN, MESMA CÂMERA VIRTUAL", font=font(24, True), fill=MUTED)
    for x, name, title in ((40, "glb", "Exportação do DA3 (scene.glb)"), (980, "rebuilt", "Reconstruída dos mapas de profundidade")):
        draw.text((x, 92), title, font=font(34, True), fill=TEXT)
        canvas.paste(gpu.render(pw, ph, view, proj, [(name, 1.0, 14, 1.0)]), (x, 150))
    millions = lambda v: cm(v / 1e6)
    count = f"{len(glb_points):,}".replace(",", ".")
    draw.multiline_text((40, 960), f"{count} pontos, a nuvem das gravações. Um único corte\n"
                        f"de confiança para os {run.n} quadros deixa {starved} deles com menos de\n"
                        "10% dos pixels, e o que sobra é sorteado até 1 milhão.", font=font(26), fill=(200, 208, 203), spacing=8)
    draw.multiline_text((980, 960), f"{millions(len(points))} milhões de pontos (voxel de 4 mm), dos {millions(run.n * run.h * run.w)} milhões de\n"
                        "pixels. Sem corte de confiança; bordas de profundidade removidas.\n"
                        "Ficam fantasmas do que se moveu entre os quadros.", font=font(26), fill=(200, 208, 203), spacing=8)
    canvas.save(out)
    print(f"{clip}: DA3 floor {floor:.3f}; {starved} of {run.n} frames keep under 10% of their pixels; "
          f"rebuilt {len(points):,} points; wrote {out}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("clip", choices=sorted(CLIPS))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--stills", help="comma-separated seconds; writes PNGs instead of the video")
    parser.add_argument("--scan", help="first,last frame: print the tape's label gaps in each")
    parser.add_argument("--compare", action="store_true", help="write DA3's cloud beside the rebuilt one")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.scan:
        scan(args.clip, *(int(v) for v in args.scan.split(",")))
        return
    if args.compare:
        compare(args.clip, args.out / f"{args.clip}-nuvem-da3-vs-reconstruida.png")
        return
    film = Film(args.clip)
    print(f"{args.clip}: label gaps {', '.join(f'{g:.2f} cm' for g in film.gaps)}; trials {', '.join(f'{v:.3f}' for v in film.trials)} cm")
    if args.stills:
        for second in (float(v) for v in args.stills.split(",")):
            film.frame(second).save(args.out / f"{args.clip}-{second:05.1f}s.png")
        return
    target = args.out / f"{args.clip}-escala.mp4"
    encoder = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
                                "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-pix_fmt", "yuv420p",
                                "-movflags", "+faststart", str(target)], stdin=subprocess.PIPE)
    for i in range(int(round(TOTAL * FPS))):
        encoder.stdin.write(film.frame(i / FPS).tobytes())
    encoder.stdin.close()
    encoder.wait()
    print("wrote", target)


if __name__ == "__main__":
    main()
