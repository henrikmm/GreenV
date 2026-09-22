// Turning a bare pair of files into a run this app can open.
//
// Until now a run could only enter the registry two ways: the app itself paid for it on a GPU,
// or it was one of the three door fixtures compiled in. A reconstruction computed anywhere else —
// by the GreenV worker, by a colleague, by an earlier session on another machine — was a `.glb`
// and a `.npz` sitting in a directory with no way in, even though every stage downstream reads
// exactly those two files.
//
// The gap was never the geometry. It was the MANIFEST: `loadRunDepthField` needs one, and a pair
// of uploaded files does not carry it. So this module builds one, and the rule it follows is the
// repository's own — every field is either measured from the bytes or declared as unknown. It
// never guesses a number that looks like a measurement.
//
// | Field | Where it comes from |
// |---|---|
// | `frames.count`, `frames.width/height` | the npz's own `depth` shape — exact |
// | `artifacts[].size_bytes`, `sha256` | the uploaded bytes — exact |
// | `diagnostics.native_npz` | the arrays the file actually carries — exact |
// | `params.fps`, `frames.effective_fps` | DECLARED by whoever imported, never observed |
// | `timing.*`, `vram.*` | zero, because this machine ran no GPU |
// | `model_repository_id`, `model_revision` | empty, because the file does not say |
//
// Pure on purpose: buffers in, a description out, nothing touched on disk. `runs.mjs` owns the
// writing, this owns the judgement, and the judgement is what the tests need to reach.

import { createHash } from "node:crypto";
import { readNpzMeta } from "./npz-meta.mjs";

export const IMPORTED_GLB_NAME = "scene.glb";
export const IMPORTED_NPZ_NAME = "result.npz";

/** The arrays a reconstruction is useless without. `confidence` is genuinely optional. */
const REQUIRED_ARRAYS = ["depth", "intrinsics", "extrinsics"];

/** What `app/src/lib/npz.ts` can decode. Accepting more here only defers the failure. */
const SUPPORTED_DTYPE = "<f4";

export function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

/**
 * Is this actually a binary glTF?
 *
 * Checked because the alternative is a cheerful import followed by an empty viewport: three.js
 * reports a parse failure deep inside the Point Cloud node, long after the file was accepted and
 * copied. The header is twelve bytes and says everything — magic, version, and its own length,
 * which catches a truncated download that a magic-only check would wave through.
 */
export function assertGlb(bytes, name) {
  if (bytes.length < 12) throw new Error(`${name} is too short to be a GLB`);
  if (bytes.subarray(0, 4).toString("latin1") !== "glTF") {
    throw new Error(`${name} is not a GLB — it does not start with the glTF magic`);
  }
  const version = bytes.readUInt32LE(4);
  if (version !== 2) throw new Error(`${name} is glTF version ${version}; only 2 is supported`);
  const declared = bytes.readUInt32LE(8);
  if (declared !== bytes.length) {
    throw new Error(
      `${name} declares ${declared} bytes but ${bytes.length} arrived — the file is truncated`,
    );
  }
}

/**
 * What the npz says about itself, and whether it can drive a measurement.
 *
 * The leading dimension of `depth`, `intrinsics` and `extrinsics` is the frame count, and all
 * three must agree: `geometry.geometryFrame` slices the same index out of each, so a mismatch
 * would pair one frame's depth with another frame's camera. That is the exact failure this
 * project spent 2026-08-05 on, and it never raises an error on its own — it just measures the
 * wrong thing.
 */
export function describeNpz(bytes, name) {
  const arrays = readNpzMeta(bytes);

  const missing = REQUIRED_ARRAYS.filter((key) => !arrays[key]);
  if (missing.length > 0) {
    throw new Error(
      `${name} has no ${missing.join(", ")} array — it carries ${Object.keys(arrays).join(", ") || "nothing"}. ` +
        "An importable npz is the bundle the depth service writes, not DA3's own native export.",
    );
  }
  if (arrays.depth.dtype !== SUPPORTED_DTYPE) {
    throw new Error(`${name} stores depth as ${arrays.depth.dtype}; only ${SUPPORTED_DTYPE} can be read`);
  }
  if (arrays.depth.fortranOrder) throw new Error(`${name} stores depth in Fortran order, which cannot be read`);
  if (arrays.depth.shape.length !== 3) {
    throw new Error(
      `${name} has depth of shape (${arrays.depth.shape.join(", ")}); a run needs (frames, height, width)`,
    );
  }

  const [frameCount, height, width] = arrays.depth.shape;
  if (!(frameCount >= 2)) {
    throw new Error(
      `${name} holds ${frameCount} frame${frameCount === 1 ? "" : "s"}. Accuracy here comes from ` +
        "comparing many views of one scene, so at least 2 are required.",
    );
  }
  for (const key of REQUIRED_ARRAYS) {
    if (arrays[key].shape[0] !== frameCount) {
      throw new Error(
        `${name} has ${frameCount} depth frames but ${arrays[key].shape[0]} ${key} — ` +
          "these index together, so a mismatch would measure one frame's depth with another's camera",
      );
    }
  }

  return { arrays, frameCount, width, height };
}

/**
 * An id that reads like the run ids already in the registry, and says which reconstruction it is.
 *
 * The suffix is the npz's own digest rather than a random draw, so two imports of one file are
 * visibly the same geometry — worth knowing when a run was re-imported to attach the frames that
 * were missing the first time.
 *
 * That makes the id a function of the file and the second, which is NOT unique on its own: the
 * timestamp is second-resolution, so importing the same pair twice inside one second produces one
 * id twice. `taken` closes that, because uniqueness has to hold against the registry rather than
 * against a clock — and collapsing a second import onto the first would destroy whatever had
 * already been measured against it.
 */
export function importedRunId(npzDigest, now, taken = () => false) {
  const stamp = now.toISOString().replace(/[-:]/g, "").replace(/\.\d+Z$/, "").replace("T", "-");
  const base = `imported-${stamp}-${npzDigest.slice(0, 6)}`;
  if (!taken(base)) return base;
  for (let attempt = 2; ; attempt++) {
    const candidate = `${base}-${attempt}`;
    if (!taken(candidate)) return candidate;
  }
}

/**
 * The wire manifest for an imported pair.
 *
 * Wire shape (snake_case) rather than the app's camelCase, because it is written to disk as the
 * run's `manifest.json` and read back through `manifestFromWire` exactly like a saved cloud run's.
 * Producing the app shape here would make imported runs the one kind that cannot be re-read.
 */
export function importedManifest({ runId, glb, npz, npzName, shape, fps, now }) {
  const { arrays, frameCount, width, height } = shape;
  const artifact = (kind, name, bytes) => ({
    kind,
    name,
    size_bytes: bytes.length,
    sha256: sha256(bytes),
    // Rewritten to this run's own base by `loadRunDepthField`; kept in the service's shape so a
    // manifest on disk reads the same whether the run was saved or imported.
    url: `/artifact/${runId}/${name}`,
    gs_uri: null,
  });

  return {
    schema_version: "verge.infer-manifest/0.1.0",
    run_id: runId,
    // Empty, not DA3's identifiers. The npz key names say it came from this project's service
    // wrapper; nothing in the file says which model or which revision produced it, and writing
    // the constants here would turn an assumption into a recorded fact.
    model_repository_id: "",
    model_revision: "",
    // The one field asserted rather than read. `InferManifest` admits no other value, and every
    // stage downstream measures in metres — so an imported relative-depth bundle would produce
    // numbers in no unit at all. Stated here so the assumption is somewhere a reader can find it.
    depth_mode: "metric",
    linear_unit: "metre",
    params: {
      fps,
      source_duration_s: null,
      // The long edge of the depth map, which is what `process_res` means: the door run's
      // (112, 504, 280) depth was taken at 504 px. Derived, not declared.
      process_res: Math.max(width, height),
      process_res_method: "upper_bound_resize",
      ref_view_strategy: "middle",
      max_frames: frameCount,
    },
    frames: {
      count: frameCount,
      requested_count: frameCount,
      width,
      height,
      // Nothing in the file records whether sampling hit a cap. False is the neutral reading,
      // and the frame count beside it is exact either way.
      capped: false,
      effective_fps: fps,
    },
    // No GPU ran on this machine for this run. Zero is the honest entry; the alternative is a
    // plausible number nobody measured.
    timing: { gpu_seconds: 0, wall_seconds: 0, model_load_seconds: null },
    vram: {
      peak_bytes: 0,
      current_bytes: 0,
      total_bytes: 0,
      device_name: "",
      torch_peak_bytes: 0,
      baseline_bytes: 0,
    },
    artifacts: [
      artifact("glb", IMPORTED_GLB_NAME, glb),
      artifact("npz", IMPORTED_NPZ_NAME, npz),
    ],
    diagnostics: {
      // What the uploaded file actually carried, under the name it arrived with. This is the
      // one place the import records its own provenance, and it is entirely observed.
      native_npz: {
        [npzName]: Object.keys(arrays).sort(),
      },
      export_dir_listing: [IMPORTED_GLB_NAME, IMPORTED_NPZ_NAME],
      publish_mode: "local",
      publish_errors: [],
    },
    // Neither true: the bytes are on this disk and no bucket will expire them.
    transient: false,
    expires_after_days: 0,
    mock: false,
    imported_at: now.toISOString(),
  };
}

/**
 * Validate an uploaded pair and describe the run it would become.
 *
 * Nothing is written. A caller that wants the run on disk takes this description to
 * `runs.mjs::importRun`; a caller that only wants to know whether the files are usable stops here.
 */
export function describeImportedRun({ glb, npz, glbName, npzName, fps, now = new Date(), taken }) {
  assertGlb(glb, glbName);
  const shape = describeNpz(npz, npzName);
  const npzDigest = sha256(npz);
  const runId = importedRunId(npzDigest, now, taken);
  return {
    runId,
    npzDigest,
    shape,
    manifest: importedManifest({ runId, glb, npz, npzName, shape, fps, now }),
  };
}
