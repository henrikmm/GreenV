import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { materialiseRun, isMockManifest, discardRun } from "../src/run-directory.mjs";

const frames = (count) => Array.from({ length: count }, (_, i) => ({
  name: `frame-${String(i + 1).padStart(4, "0")}.jpg`,
  bytes: Buffer.from(`jpeg-${i}`),
}));

const manifest = (over = {}) => ({
  run_id: "20260908-101500-abc123",
  frames: { count: 4 },
  artifacts: [{ kind: "glb", sha256: "a".repeat(64) }],
  ...over,
});

test("the run is laid out where the inspector resolves it", async () => {
  const root = await mkdtemp(join(tmpdir(), "runs-"));
  const run = await materialiseRun({
    runsRoot: root, manifest: manifest(), glb: Buffer.from("GLB"), npz: Buffer.from("NPZ"), frames: frames(4),
  });

  assert.equal(run.runId, "20260908-101500-abc123");
  assert.equal(run.frameCount, 4);
  assert.equal(await readFile(join(run.directory, "scene.glb"), "utf8"), "GLB");
  assert.equal(await readFile(join(run.directory, "result.npz"), "utf8"), "NPZ");
  assert.equal(JSON.parse(await readFile(join(run.directory, "manifest.json"), "utf8")).run_id, run.runId);
  assert.deepEqual(await readdir(join(run.directory, "frames")), ["frame-0001.jpg", "frame-0002.jpg", "frame-0003.jpg", "frame-0004.jpg"]);
});

// source.mjs refuses a run whose JPEG count disagrees with its manifest, so the count written
// has to be the depth service's, not the count we happened to upload.
test("only as many frames as the depth manifest describes are written", async () => {
  const root = await mkdtemp(join(tmpdir(), "runs-"));
  const run = await materialiseRun({
    runsRoot: root, manifest: manifest({ frames: { count: 4 } }), glb: Buffer.alloc(1), npz: Buffer.alloc(1), frames: frames(100),
  });
  assert.equal((await readdir(join(run.directory, "frames"))).length, 4);
});

test("a manifest describing more frames than were sent is refused", async () => {
  const root = await mkdtemp(join(tmpdir(), "runs-"));
  await assert.rejects(
    materialiseRun({ runsRoot: root, manifest: manifest({ frames: { count: 50 } }), glb: Buffer.alloc(1), npz: Buffer.alloc(1), frames: frames(4) }),
    /describes 50 frames but only 4 were sent/,
  );
});

test("a run id that could escape its root is refused", async () => {
  const root = await mkdtemp(join(tmpdir(), "runs-"));
  await assert.rejects(
    materialiseRun({ runsRoot: root, manifest: manifest({ run_id: "../escape" }), glb: Buffer.alloc(1), npz: Buffer.alloc(1), frames: frames(4) }),
    /unusable run_id/,
  );
});

// Each of these is how the fixture-backed mock announces itself. Missing any one of them is how
// a mock run gets mistaken for a reading.
test("a mock reconstruction is recognised by every marker it carries", () => {
  assert.equal(isMockManifest({ mock: true, run_id: "x" }), true);
  assert.equal(isMockManifest({ run_id: "mock-9f2c1a44" }), true);
  assert.equal(isMockManifest({ run_id: "x", artifacts: [{ kind: "glb", sha256: "fixture" }] }), true);
  assert.equal(isMockManifest(manifest()), false);
});

test("discarding a run removes its artifacts", async () => {
  const root = await mkdtemp(join(tmpdir(), "runs-"));
  const run = await materialiseRun({ runsRoot: root, manifest: manifest(), glb: Buffer.alloc(1), npz: Buffer.alloc(1), frames: frames(4) });
  await discardRun(run.directory);
  await assert.rejects(stat(run.directory));
});
