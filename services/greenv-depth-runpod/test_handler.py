"""Everything about the RunPod handler that does not need a GPU.

The line is exact, and it is worth stating before the first assertion. What is proved here: the
job envelope, the refusals that must happen before money is spent, the frames travelling by
object key, which npz is published under which name, the digest check, and the manifest the
worker reads back. What is NOT proved here, and is not provable without waking a paid endpoint:
that DA3 loads, that the reconstruction is right, that an L4 holds what the ceiling table says it
holds, and that RunPod's own queue behaves as its documentation describes.

Written as a plain script of asserts rather than a framework, matching `server/test_contract.py`
in the `measurement/` subtree -- the only other Python in this repository. Stdlib only, so it runs
anywhere Python 3.12 does:

    python services/greenv-depth-runpod/test_handler.py
"""

import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))

# The module reads these at import. Clearing them keeps a developer's shell out of the fixture's
# expected numbers.
for name in ("GREENV_SIGNED_URL_TTL_SECONDS", "GREENV_DEPTH_MAX_FRAMES", "GREENV_DEPTH_OUTPUT_PREFIX"):
    os.environ.pop(name, None)

import handler  # noqa: E402
from handler import JobRejected  # noqa: E402

EXAMPLE = json.loads((HERE / "contract" / "depth-job-v1.example.json").read_text())


class FakeDepth:
    """The DA3 service, answering from the shared example instead of from a GPU."""

    def __init__(self, example=EXAMPLE, *, device=None):
        self.example = example
        self._device = device or example["device"]
        self.calls = []

    def device(self):
        self.calls.append(("device", None))
        return self._device

    def infer(self, paths, params):
        self.calls.append(("infer", {"paths": list(paths), "params": dict(params)}))
        for path in paths:
            assert Path(path).is_file(), f"{path} was not written before inference"
        return json.loads(json.dumps(self.example["service"]["manifest"]))

    def artifact(self, run_id, name):
        self.calls.append(("artifact", {"run_id": run_id, "name": name}))
        spec = self.example["service"]["artifactBytes"][name]
        return (spec["fill"] * spec["size_bytes"]).encode()


class FakeBucket:
    """One bucket, in memory. Signs a link the way a presigner would: absolutely."""

    def __init__(self, request, example=EXAMPLE):
        self.template = example["service"]["signedUrlTemplate"]
        self.bucket = request.bucket
        self.reads = []
        self.writes = {}

    def get(self, key):
        self.reads.append(key)
        return f"jpeg-bytes-of-{key}".encode()

    def put(self, key, data):
        self.writes[key] = data

    def signed_url(self, key):
        return self.template.replace("{key}", key)


def run(payload=None, *, depth=None, buckets=None, example=EXAMPLE):
    depth = depth or FakeDepth(example)
    opened = buckets if buckets is not None else []

    def open_store(request):
        store = FakeBucket(request, example)
        opened.append(store)
        return store

    return handler.run_job(payload if payload is not None else example["input"], depth=depth, open_store=open_store)


def rejects(fragment, thunk):
    try:
        thunk()
    except JobRejected as rejected:
        assert fragment in str(rejected), f"expected {fragment!r} in: {rejected}"
        return str(rejected)
    raise AssertionError(f"expected a JobRejected containing {fragment!r}, nothing was raised")


def mutated(**params):
    payload = json.loads(json.dumps(EXAMPLE["input"]))
    payload["params"].update(params)
    return payload


# ---------------------------------------------------------------- the shared envelope

buckets = []
depth = FakeDepth()
manifest = run(depth=depth, buckets=buckets)
assert manifest == EXAMPLE["output"], json.dumps(manifest, indent=2)
print("contract example round-trips OK:", manifest["run_id"])

# Frames travel by key, in the order the segment recorded them, and their bytes never enter the
# job body -- which is the entire reason this dialect exists.
assert buckets[0].reads == [frame["key"] for frame in EXAMPLE["input"]["frames"]], buckets[0].reads
inferred = next(call for name, call in depth.calls if name == "infer")
assert [Path(path).name for path in inferred["paths"]] == ["frame-0001.jpg", "frame-0002.jpg", "frame-0003.jpg"]
assert inferred["params"] == {"fps": 10, "process_res": 504, "max_frames": 112, "source_duration_s": 10}
print("frames travel by key, in order OK:", len(buckets[0].reads), "keys")

# The npz published as `result.npz` must be Verge Studio's own arrays. DA3 exports a file with
# that exact name, so a handler matching on file name instead of on kind would publish the wrong
# bundle and every downstream measurement would read keys that are not there.
uploaded = buckets[0].writes
scene = EXAMPLE["output"]["artifacts"][0]["object_key"]
result = EXAMPLE["output"]["artifacts"][1]["object_key"]
assert set(uploaded) == {scene, result}, sorted(uploaded)
assert uploaded[result] == b"n" * 96, "published result.npz is DA3's own npz, not Verge Studio's"
assert uploaded[scene] == b"g" * 48
assert manifest["published"]["skipped"] == ["result.npz (npz_native)"]
print("published result.npz is verge-result.npz OK:", len(uploaded[result]), "bytes")

# Every artifact URL is absolute. `infer-runpod.mjs` rejects a relative one rather than resolving
# it against RunPod's API host, because a serverless handler serves nothing itself.
for artifact in manifest["artifacts"]:
    assert artifact["url"].startswith("https://"), artifact
print("artifact URLs are absolute OK")

# ---------------------------------------------------------------- the memory ceiling

many = json.loads(json.dumps(EXAMPLE["input"]))
many["frames"] = [
    {"name": f"frame-{index:04d}.jpg", "key": f"{EXAMPLE['input']['frames'][0]['key'].rsplit('/', 1)[0]}/frame-{index:04d}.jpg"}
    for index in range(1, 130)
]
counting = FakeDepth()
message = rejects("exceeds the 112", lambda: run(many, depth=counting))
assert "NVIDIA L4" in message, message
assert not any(name == "infer" for name, _ in counting.calls), "the model was asked about a job that cannot fit"
print("over the ceiling is refused before inference OK:", message[:72])

# A smaller GPU is not a smaller version of an L4. The table was measured on one device and does
# not transfer down, so the endpoint's operator has to say what theirs holds.
small = FakeDepth(device={**EXAMPLE["device"], "device_name": "NVIDIA T4", "total_bytes": 15_843_721_216})
message = rejects("GREENV_DEPTH_MAX_FRAMES", lambda: run(depth=small))
assert "14.76 GiB" in message and "22.03 GiB" in message, message
print("a GPU smaller than an L4 is refused OK:", message[:72])

# ...and naming it is enough to proceed, with the service's own cap lowered to that truth rather
# than to the caller's assumption about a different device.
os.environ["GREENV_DEPTH_MAX_FRAMES"] = "3"
try:
    clamped = FakeDepth(device={**EXAMPLE["device"], "device_name": "NVIDIA T4", "total_bytes": 15_843_721_216})
    answer = run(depth=clamped)
    assert answer["published"]["frame_ceiling"] == 3, answer["published"]
    assert next(call for name, call in clamped.calls if name == "infer")["params"]["max_frames"] == 3
finally:
    del os.environ["GREENV_DEPTH_MAX_FRAMES"]
print("GREENV_DEPTH_MAX_FRAMES overrides the table and clamps the request OK")

# There is no measurement above 504 px, and extrapolating one is how a run gets killed.
rejects("no VRAM measurement exists above 504 px", lambda: run(mutated(process_res=720)))
# A lower resolution is bounded safely by the next measured one up.
assert handler.frame_ceiling(400, device_total_bytes=handler.L4_USABLE_BYTES) == 112
assert handler.frame_ceiling(252, device_total_bytes=handler.L4_USABLE_BYTES) == 256
print("the ceiling table refuses above 504 px and bounds below it OK")

# An endpoint configured without a GPU costs a cold start to discover at /infer. Ask first.
cpu = FakeDepth(device={**EXAMPLE["device"], "available": False})
rejects("no CUDA device", lambda: run(depth=cpu))
print("an endpoint with no CUDA device is refused OK")

# ---------------------------------------------------------------- request parsing

rejects("at least 2 frames", lambda: run({**EXAMPLE["input"], "frames": EXAMPLE["input"]["frames"][:1]}))
rejects(
    "frames[1].key must be a non-empty string",
    lambda: run({**EXAMPLE["input"], "frames": [EXAMPLE["input"]["frames"][0], {"name": "frame-0002.jpg"}]}),
)
rejects(
    "must be a bare file name",
    lambda: run({**EXAMPLE["input"], "frames": [{"name": "../escape.jpg", "key": "a/b.jpg"}, EXAMPLE["input"]["frames"][1]]}),
)
rejects("storage.bucket", lambda: run({**EXAMPLE["input"], "storage": {"endpoint": "https://r2.example"}}))
rejects("carries no `storage` object", lambda: run({"frames": EXAMPLE["input"]["frames"]}))
rejects("params.process_res must be between 126 and 1024", lambda: run(mutated(process_res=64)))
rejects("params.max_frames must be a whole number", lambda: run(mutated(max_frames=12.5)))
print("bad job inputs are named rather than sent to the GPU OK")

# ---------------------------------------------------------------- where artifacts land

request = handler.parse_job_input(EXAMPLE["input"])
assert handler.output_prefix(request) == "capture-sessions/2f1d9c48-5c2f-4a4b-9c1e-0a6b3d7e5f21/segments/00000000"
explicit = handler.parse_job_input({**EXAMPLE["input"], "storage": {**EXAMPLE["input"]["storage"], "prefix": "elsewhere/here/"}})
assert handler.output_prefix(explicit) == "elsewhere/here"
mixed = handler.parse_job_input({
    **EXAMPLE["input"],
    "frames": [{"name": "a.jpg", "key": "one/a.jpg"}, {"name": "b.jpg", "key": "two/b.jpg"}],
})
rejects("one job is one segment", lambda: handler.output_prefix(mixed))
print("artifacts land beside the frames they came from OK")

# ---------------------------------------------------------------- what the service returns

class Tampered(FakeDepth):
    def artifact(self, run_id, name):
        return super().artifact(run_id, name) + b"!"


rejects("are not the ones it measured", lambda: run(depth=Tampered()))

no_glb = json.loads(json.dumps(EXAMPLE))
no_glb["service"]["manifest"]["artifacts"] = [
    artifact for artifact in no_glb["service"]["manifest"]["artifacts"] if artifact["kind"] != "glb"
]
rejects("published no glb artifact", lambda: run(example=no_glb, depth=FakeDepth(no_glb)))

overcounted = json.loads(json.dumps(EXAMPLE))
overcounted["service"]["manifest"]["frames"]["count"] = 9
rejects("describes 9 frames of the 3 sent", lambda: run(example=overcounted, depth=FakeDepth(overcounted)))

unusable = json.loads(json.dumps(EXAMPLE))
unusable["service"]["manifest"]["run_id"] = "../elsewhere"
rejects("unusable run_id", lambda: run(example=unusable, depth=FakeDepth(unusable)))
print("a depth answer that cannot be trusted is refused OK")

# ---------------------------------------------------------------- RunPod's own envelope

rejected = handler.handler({"id": "job-1", "input": {"frames": []}})
assert set(rejected) == {"error"} and "at least 2 frames" in rejected["error"], rejected
print("a rejected job answers with an error RunPod reports as FAILED OK:", rejected["error"][:60])

print("test_handler: OK")
