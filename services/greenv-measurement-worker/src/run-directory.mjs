// Laying out a run on disk in the shape Verge Studio's inspector already resolves.
//
// `measurement/scripts/inspect/source.mjs` finds a run by looking for `scene.glb`, reads the
// frame count from `manifest.json`'s `frames.count`, and then looks for that many JPEGs in
// `frames/`. It refuses a directory whose JPEG count disagrees with its manifest, and that
// refusal is load-bearing: this project has already lost time to a mask painted on one frame and
// back-projected with another frame's camera. So the count written here is the count the depth
// service reported, never the count we happened to upload.
//
// The two numbers differ in exactly one case, and it is the fixture-backed mock: it answers every
// request with the roadside fixture's four-frame reconstruction regardless of what was sent. A
// run assembled from it therefore pairs this segment's images with an unrelated scene's geometry.
// That is worth doing — it exercises every seam for free — and it is never a measurement, which
// is why `isMock` travels with the result and the pipeline refuses to publish one unless the
// deployment has said out loud that it accepts mock packets.

import { mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

export function isMockManifest(manifest) {
  return manifest?.mock === true
    || String(manifest?.run_id ?? "").startsWith("mock-")
    || (manifest?.artifacts ?? []).some((artifact) => artifact.sha256 === "fixture");
}

/**
 * Write `<runsRoot>/<runId>/` with the reconstruction, the manifest and the frames it describes.
 *
 * Returns the run id and the directory, so the caller passes an id to the measurement process
 * and never a path — which is the boundary `AGENTS.md` asks for.
 */
export async function materialiseRun({ runsRoot, manifest, glb, npz, frames }) {
  const runId = String(manifest.run_id ?? "").trim();
  if (!runId || /[\\/]/.test(runId)) throw new Error(`depth service returned an unusable run_id: ${JSON.stringify(manifest.run_id)}`);

  const described = Number(manifest.frames?.count);
  if (!Number.isInteger(described) || described < 2) {
    throw new Error(`depth manifest describes ${manifest.frames?.count} frames; at least 2 are required`);
  }
  if (described > frames.length) {
    throw new Error(`depth manifest describes ${described} frames but only ${frames.length} were sent`);
  }

  const directory = join(runsRoot, runId);
  const frameDirectory = join(directory, "frames");
  await mkdir(frameDirectory, { recursive: true });

  await writeFile(join(directory, "scene.glb"), glb);
  await writeFile(join(directory, "result.npz"), npz);
  await writeFile(join(directory, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);

  // Recorded order, and the file names are kept as the extractor wrote them so the number Verge
  // Studio parses out of each name stays the segment's own canonical frame number. That number
  // is what `frameContext` is keyed by; renaming here would silently unkey it.
  for (const frame of frames.slice(0, described)) {
    await writeFile(join(frameDirectory, frame.name), frame.bytes);
  }

  return { runId, directory, frameCount: described, isMock: isMockManifest(manifest) };
}

/** A run is ~110 MB of npz and GLB. Keep it only when someone asked to inspect it. */
export async function discardRun(directory) {
  await rm(directory, { recursive: true, force: true });
}
