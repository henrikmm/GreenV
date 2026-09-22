/**
 * Runs, client side.
 *
 * A run is one DA3 inference with an identity: which clip, which settings, which artifacts.
 * Before this existed the app could only ever address three hardcoded door fixture
 * directories, which is why a live run could be measured but never recorded — there was
 * nothing stable to key a measurement to.
 *
 * Runs are TRANSIENT by default. A completed cloud run registers a stub (manifest only) and
 * stays selectable, but nothing large is written until Save. See `vite-plugins/runs.mjs`.
 */

import type { InferManifest } from "./contract";
import { localApiHeaders } from "./local-api";

/** Opaque, stable identity for a run. Measurements are keyed by this. */
export type RunId = string;

export interface RunRecord {
  id: RunId;
  label: string;
  clipName: string;
  /** Content digest of the source clip. Metric scale does not transfer between clips, so
   *  this is what decides which measurement targets a run may be graded against. */
  clipSha256: string;
  createdAt: string;
  /** `import` is a reconstruction computed elsewhere and uploaded as a `.glb` + `.npz` pair. */
  source: "cloud" | "fixture" | "import";
  /** Built-ins are the recorded door fixtures: read-only and not deletable. */
  builtin: boolean;
  /**
   * True when this run's RGB frames come from a SHARED canonical 256-frame extraction (the door
   * fixtures) rather than its own contiguous 1..N set. It decides how an NPZ index maps to a
   * JPEG filename, and getting it wrong desynchronises RGB from depth without erroring — see
   * `measurement/depth-field.ts`. Optional: records written before 2026-08-05 fall back to
   * `builtin`.
   */
  canonicalFrames?: boolean;
  /** True once the artifacts are on this disk rather than only on a cloud instance. */
  persisted: boolean;
  /**
   * False when an imported run arrived without the frames it was computed from. The geometry is
   * complete either way; what is missing is photograph colour and anything painted on an image.
   * Absent on every record written before importing existed, which is why the pane reads it as
   * "not an import" rather than "no frames".
   */
  framesAvailable?: boolean;
  /** False when a stub's instance is gone, or a fixture payload was never regenerated. */
  available: boolean;
  frameCount?: number;
  processRes?: number;
  gpuSeconds?: number;
  sizeBytes: number;
  serviceUrl?: string;
  manifest?: InferManifest | null;
  /** URL prefix for this run's artifacts, or null while the run is still transient. */
  artifactBase: string | null;
  framesBase: string | null;
}

export interface RunsListing {
  root: string;
  runs: RunRecord[];
}

const BASE = "/api";

async function expectOk(res: Response): Promise<unknown> {
  if (!res.ok) {
    const detail = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${detail.slice(0, 300)}`);
  }
  return res.json();
}

export async function fetchRuns(): Promise<RunsListing> {
  return (await expectOk(await fetch(`${BASE}/runs`))) as RunsListing;
}

export async function registerRun(entry: {
  id: string;
  label: string;
  clipName: string;
  clipSha256: string;
  frameCount: number;
  processRes: number;
  gpuSeconds: number;
  serviceUrl: string;
  manifest: InferManifest;
  framePaths: string[];
}): Promise<RunRecord> {
  return (await expectOk(
    await fetch(`${BASE}/runs`, {
      method: "POST",
      headers: localApiHeaders({ "content-type": "application/json" }),
      body: JSON.stringify(entry),
    }),
  )) as RunRecord;
}

export interface ImportSelection<T> {
  glb: T[];
  npz: T[];
  frames: T[];
  /** Anything that is none of the three, kept so the pane can name what it is ignoring. */
  ignored: T[];
}

/**
 * Sort a dropped or picked set of files into the three things an import is made of.
 *
 * By extension, because that is all a browser reliably offers: a dropped file has a name and
 * bytes, and the bytes are not read here. The real check on what a file IS happens in the
 * middleware, which reads the glTF header and the npz directory — so a `.glb` that is not one is
 * refused there with a reason, rather than accepted here on the strength of its name.
 */
export function sortImportFiles<T extends { name: string }>(files: readonly T[]): ImportSelection<T> {
  const selection: ImportSelection<T> = { glb: [], npz: [], frames: [], ignored: [] };
  for (const file of files) {
    const extension = file.name.slice(file.name.lastIndexOf(".")).toLowerCase();
    if (extension === ".glb") selection.glb.push(file);
    else if (extension === ".npz") selection.npz.push(file);
    else if (extension === ".jpg" || extension === ".jpeg") selection.frames.push(file);
    else selection.ignored.push(file);
  }
  return selection;
}

/**
 * Whether a selection can be imported, and what to say when it cannot.
 *
 * Separate from the pane so the wording is asserted rather than eyeballed. Every branch here is
 * a mistake somebody will actually make — dropping one file of the pair, dropping two runs at
 * once, dropping the frames folder and forgetting the geometry.
 */
export function importReadiness<T extends { name: string }>(
  selection: ImportSelection<T>,
): { ready: boolean; detail: string } {
  const { glb, npz, frames, ignored } = selection;
  if (glb.length === 0 && npz.length === 0) {
    return { ready: false, detail: "Drop a .glb and a .npz — the frames they were computed from are optional." };
  }
  if (glb.length === 0) return { ready: false, detail: "No .glb. The point cloud and its alignment live in that file." };
  if (npz.length === 0) return { ready: false, detail: "No .npz. Depth, intrinsics and extrinsics live in that file, and nothing can be measured without them." };
  if (glb.length > 1 || npz.length > 1) {
    return { ready: false, detail: "One run at a time: this selection holds more than one .glb or .npz." };
  }
  const extra = ignored.length > 0 ? ` Ignoring ${ignored.length} other file${ignored.length === 1 ? "" : "s"}.` : "";
  const images = frames.length > 0
    ? `${frames.length} frame${frames.length === 1 ? "" : "s"}`
    : "no frames — photo colour and mask painting will be unavailable";
  return { ready: true, detail: `${glb[0].name} + ${npz[0].name}, ${images}.${extra}` };
}

/**
 * What an import is allowed to declare about itself. Everything else about the run is read out
 * of the files — see `vite-plugins/imported-run.mjs` for which fields are measured and which
 * are taken on the importer's word.
 */
export interface ImportDeclaration {
  label?: string;
  clipName?: string;
  /**
   * Sampling rate of the frames behind the reconstruction. Nothing in a `.npz` records it, so it
   * is declared rather than observed; it sets the per-frame timestamps and nothing measured.
   */
  fps?: number;
}

/**
 * Upload a reconstruction computed somewhere else and register it as a run.
 *
 * `frames` is optional and all-or-nothing: the geometry works without them, but a partial set
 * would pair depth with the wrong picture, so the middleware refuses one.
 */
export async function importRun(
  files: { glb: File; npz: File; frames?: File[] },
  meta: ImportDeclaration = {},
): Promise<RunRecord> {
  const form = new FormData();
  form.append("glb", files.glb, files.glb.name);
  form.append("npz", files.npz, files.npz.name);
  for (const frame of files.frames ?? []) form.append("frames", frame, frame.name);
  form.append("meta", JSON.stringify(meta));

  return (await expectOk(
    await fetch(`${BASE}/runs/import`, { method: "POST", headers: localApiHeaders(), body: form }),
  )) as RunRecord;
}

export async function saveRun(id: RunId): Promise<{ run: RunRecord; output: string }> {
  return (await expectOk(
    await fetch(`${BASE}/runs/${encodeURIComponent(id)}/save`, { method: "POST", headers: localApiHeaders() }),
  )) as { run: RunRecord; output: string };
}

/**
 * Delete a run's artifacts. Its recorded trials are archived first, and `archived` says how many —
 * the artifacts can be reconstructed by running the clip again, the trials cannot.
 */
export async function deleteRun(id: RunId): Promise<{ deleted: RunId; archived: number }> {
  return (await expectOk(
    await fetch(`${BASE}/runs/${encodeURIComponent(id)}`, { method: "DELETE", headers: localApiHeaders() }),
  )) as { deleted: RunId; archived: number };
}

/**
 * Rough disk cost of saving a run, so the Save button can say what it will consume before
 * it consumes it. Measured on the real fixtures: ~108 MB npz + ~16 MB GLB at 112f/504px,
 * plus ~11 MB of source frames at 1024 px.
 */
export function estimateSaveBytes(run: RunRecord): number {
  const fromManifest = (run.manifest?.artifacts ?? []).reduce(
    (total, artifact) => total + (artifact.sizeBytes || 0),
    0,
  );
  const frames = (run.frameCount ?? 0) * 100_000;
  return fromManifest + frames;
}

export function formatBytes(bytes: number): string {
  if (bytes <= 0) return "—";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}
