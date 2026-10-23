"""A thinned copy of DA3's exported point cloud, small enough to reach a slow network first.

The full `scene.glb` is ~16 MB for a million points, and the npz beside it 60–105 MB; on the
FIAP Next demo the GPU was 7 s of a 51 s capture (`cap-20261023-012557-k5en`, 2026-10-23) and
most of the rest was publishing and moving those bytes. A preview of ~150k points is ~2.4 MB and
can be published the moment the forward pass returns, before the npz is even written.

It is DA3's own file with fewer points, not a second reconstruction: the glTF JSON — scene,
nodes, the camera frustums, and `scenes[0].extras.hf_alignment` that the studio aligns by — is
kept as written, and only the points primitive's two accessors are replaced. A viewer that
places the full cloud therefore places the preview in the same spot with no code of its own.

Pure numpy and the GLB container format, no torch and no trimesh, so it is testable on a CPU.
"""

from __future__ import annotations

import json
import struct
from pathlib import Path

import numpy as np

GLB_MAGIC = 0x46546C67  # "glTF"
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942
POINTS_MODE = 0

_COMPONENT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
_WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def read_glb(path: Path) -> tuple[dict, bytes]:
    data = path.read_bytes()
    magic, version, _length = struct.unpack_from("<III", data, 0)
    if magic != GLB_MAGIC or version != 2:
        raise ValueError(f"{path.name} is not a glTF 2.0 binary")
    offset = 12
    doc: dict | None = None
    binary = b""
    while offset < len(data):
        length, kind = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8 : offset + 8 + length]
        if kind == CHUNK_JSON:
            doc = json.loads(chunk)
        elif kind == CHUNK_BIN:
            binary = bytes(chunk)
        offset += 8 + length
    if doc is None:
        raise ValueError(f"{path.name} has no JSON chunk")
    return doc, binary


def _pad(block: bytes, fill: bytes) -> bytes:
    return block + fill * ((4 - len(block) % 4) % 4)


def write_glb(path: Path, doc: dict, binary: bytes) -> None:
    body = _pad(json.dumps(doc, separators=(",", ":")).encode(), b" ")
    blob = _pad(binary, b"\x00")
    total = 12 + 8 + len(body) + 8 + len(blob)
    with path.open("wb") as handle:
        handle.write(struct.pack("<III", GLB_MAGIC, 2, total))
        handle.write(struct.pack("<II", len(body), CHUNK_JSON))
        handle.write(body)
        handle.write(struct.pack("<II", len(blob), CHUNK_BIN))
        handle.write(blob)


def _accessor_array(doc: dict, binary: bytes, index: int) -> np.ndarray:
    accessor = doc["accessors"][index]
    view = doc["bufferViews"][accessor["bufferView"]]
    dtype = np.dtype(_COMPONENT[accessor["componentType"]])
    width = _WIDTH[accessor["type"]]
    if view.get("byteStride", dtype.itemsize * width) != dtype.itemsize * width:
        raise ValueError("interleaved buffers are not something DA3's exporter writes")
    start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
    count = accessor["count"]
    return np.frombuffer(binary, dtype=dtype, count=count * width, offset=start).reshape(count, width)


def _points_primitive(doc: dict) -> dict:
    """The largest POINTS primitive. DA3's GLB has one cloud plus small frustum line meshes."""
    best: dict | None = None
    best_count = -1
    for mesh in doc.get("meshes", []):
        for primitive in mesh.get("primitives", []):
            if primitive.get("mode", 4) != POINTS_MODE or "POSITION" not in primitive.get("attributes", {}):
                continue
            count = doc["accessors"][primitive["attributes"]["POSITION"]]["count"]
            if count > best_count:
                best, best_count = primitive, count
    if best is None:
        raise ValueError("no point cloud in this GLB")
    return best


def thin_glb(source: Path, target: Path, max_points: int, seed: int = 0) -> dict[str, int]:
    """Write `target`: `source` with its point cloud reduced to at most `max_points`.

    The subset is uniform and seeded, and kept in the original order: DA3 writes points view by
    view, so ordered indices keep the demo's draw-in reading as the scene assembling.
    """
    doc, binary = read_glb(source)
    primitive = _points_primitive(doc)
    attributes = primitive["attributes"]
    replaced = {name: attributes[name] for name in ("POSITION", "COLOR_0") if name in attributes}
    total = doc["accessors"][replaced["POSITION"]]["count"]
    keep = total if total <= max_points else max_points
    index = np.sort(np.random.default_rng(seed).choice(total, size=keep, replace=False)) if keep < total else None

    # Rebuild the binary chunk view by view, swapping in the thinned data for the two accessors
    # of the cloud and copying every other view (the frustums) byte for byte.
    new_views: dict[int, bytes] = {}
    for accessor_index in replaced.values():
        values = _accessor_array(doc, binary, accessor_index)
        if index is not None:
            values = values[index]
        accessor = doc["accessors"][accessor_index]
        accessor["count"] = int(values.shape[0])
        accessor["byteOffset"] = 0
        if accessor_index == replaced["POSITION"]:
            accessor["min"] = values.min(axis=0).astype(float).tolist()
            accessor["max"] = values.max(axis=0).astype(float).tolist()
        else:
            accessor["min"] = values.min(axis=0).tolist()
            accessor["max"] = values.max(axis=0).tolist()
        new_views[accessor["bufferView"]] = np.ascontiguousarray(values).tobytes()

    out = bytearray()
    for view_index, view in enumerate(doc["bufferViews"]):
        if view_index in new_views:
            block = new_views[view_index]
        else:
            start = view.get("byteOffset", 0)
            block = binary[start : start + view["byteLength"]]
        while len(out) % 4:
            out.append(0)
        view["byteOffset"] = len(out)
        view["byteLength"] = len(block)
        view["buffer"] = 0
        out.extend(block)
    doc["buffers"] = [{"byteLength": len(out)}]
    write_glb(target, doc, bytes(out))
    return {"points": int(keep), "source_points": int(total), "size_bytes": target.stat().st_size}
