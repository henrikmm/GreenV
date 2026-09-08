// The whole chain on real geometry, without waking a GPU.
//
// Everything here is the production path except the depth service, and what stands in for it is
// not a fixture: it replays a reconstruction Verge Studio actually computed from these exact
// frames on an L4 in August. So the frames, the point cloud, the cameras, the segmentation, the
// ground fit and the packet are all real, and the only thing simulated is the HTTP call that
// would have produced them. That is the strongest end-to-end evidence available for free.
//
// Skipped when the run is not on this machine — a fresh clone has no saved runs, and a skipped
// test says so rather than passing vacuously.

import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createReadStream, existsSync } from "node:fs";
import { mkdtemp, readFile, readdir, stat } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { measurementPipeline } from "../src/pipeline.mjs";
import { inferClient } from "../src/infer.mjs";
import { measurementRunner } from "../src/measure.mjs";
import { localStorage } from "../src/storage/local.mjs";
import { loadConfig } from "../src/config.mjs";

const RUN = process.env.GREENV_E2E_RUN ?? "20260814-164826-0e4e4c";
const RUN_DIR = join(process.env.VERGE_RUNS_ROOT ?? join(homedir(), "verge-runs"), RUN);
const SESSION = "11111111-1111-7111-8111-111111111111";
const PREFIX = `capture-sessions/${SESSION}/segments/00000000`;

const available = existsSync(join(RUN_DIR, "scene.glb")) && existsSync(join(RUN_DIR, "frames"));

/** Replays one recorded reconstruction over the wire contract the deployed service serves. */
async function replayDepthService(runDirectory) {
  const npzName = ["verge-result.npz", "result.npz"].find((name) => existsSync(join(runDirectory, name)));
  const files = { "scene.glb": join(runDirectory, "scene.glb"), "result.npz": join(runDirectory, npzName) };
  let uploaded = 0;

  const server = createServer(async (request, response) => {
    if (request.method === "POST" && request.url === "/infer") {
      // Drain the multipart body; the frame count is what this stand-in verifies about it.
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      uploaded = (Buffer.concat(chunks).toString("latin1").match(/name="frames"/g) ?? []).length;

      const source = JSON.parse(await readFile(join(runDirectory, "manifest.json"), "utf8"));
      const body = JSON.stringify({
        ...source,
        run_id: `replay-${Date.now().toString(36)}`,
        artifacts: await Promise.all(Object.entries(files).map(async ([name, path]) => ({
          kind: name.endsWith(".glb") ? "glb" : "npz",
          name,
          size_bytes: (await stat(path)).size,
          sha256: "f".repeat(64),
          url: `/artifact/${name}`,
        }))),
      });
      response.writeHead(200, { "content-type": "application/json" });
      return response.end(body);
    }

    const artifact = /^\/artifact\/(scene\.glb|result\.npz)$/.exec(request.url ?? "");
    if (request.method === "GET" && artifact) {
      response.writeHead(200, { "content-type": "application/octet-stream" });
      return createReadStream(files[artifact[1]]).pipe(response);
    }
    response.writeHead(404).end();
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { baseUrl: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((r) => server.close(r)), uploads: () => uploaded };
}

test("a captured segment becomes a verified grass packet", { skip: available ? false : `no saved run at ${RUN_DIR}` }, async (t) => {
  const depth = await replayDepthService(RUN_DIR);
  t.after(() => depth.close());

  // Seed object storage exactly as the frame extractor leaves a segment.
  const storageRoot = await mkdtemp(join(tmpdir(), "greenv-storage-"));
  const storage = localStorage({ root: storageRoot });
  const names = (await readdir(join(RUN_DIR, "frames"))).filter((n) => n.endsWith(".jpg")).sort();

  const sampledFrames = [];
  const telemetry = [];
  for (const [index, name] of names.entries()) {
    const bytes = await readFile(join(RUN_DIR, "frames", name));
    await storage.put(`${PREFIX}/sampled-frames/${name}`, bytes);
    sampledFrames.push({ index, fileName: name, timestampSeconds: index / 10, sizeBytes: bytes.length, sha256: "b".repeat(64) });
    telemetry.push({
      index,
      presentationTimeNanos: Math.round((index / 10) * 1e9),
      capturedAtUtc: new Date(Date.UTC(2026, 7, 14, 16, 48, 26) + index * 100).toISOString(),
      locationQuality: "good",
      location: { latitude: -23.5 + index * 1e-4, longitude: -46.7, horizontalAccuracyMeters: 4.2, speedMetersPerSecond: 16.7 },
    });
  }
  await storage.put(`${PREFIX}/frame-metadata-v2.json`, Buffer.from(JSON.stringify(telemetry)));
  await storage.put(`${PREFIX}/segment-manifest-v2.json`, Buffer.from(JSON.stringify({
    schemaVersion: 2, sessionId: SESSION, segmentIndex: 0,
    sourceGeneration: "a".repeat(64), durationMillis: 10_000, sampledFrames,
  })));

  const config = loadConfig({
    GREENV_PIPELINE_ROOT: storageRoot,
    GREENV_INFER_BASE_URL: depth.baseUrl,
    GREENV_INFER_MAX_FRAMES: String(names.length),
    VERGE_RUNS_ROOT: await mkdtemp(join(tmpdir(), "greenv-runs-")),
  });

  const measure = measurementPipeline({
    config,
    storage,
    infer: inferClient(config.infer),
    runner: measurementRunner(config.measurement),
  });

  const result = await measure({ sessionId: SESSION, segmentIndex: 0, rodovia: "SP-021", sentido: "norte" });

  assert.equal(depth.uploads(), names.length, "every sampled frame reached the depth service");
  assert.equal(result.mock, false);
  assert.equal(result.depth.framesSent, names.length);
  assert.deepEqual(result.measurement.classes, ["terrain", "vegetation"]);

  // The packet is published beside the frames it came from, and it verifies with Verge Studio's
  // own checker — checksums, mask digests, and the report agreeing with the JSON.
  const packetDirectory = join(storageRoot, PREFIX, "measurement");
  const { checkGrassPacket } = await import(new URL("../../../measurement/scripts/check-grass-quality.mjs", import.meta.url));
  const verified = await checkGrassPacket(packetDirectory);
  assert.equal(verified.checksums, "pass");
  assert.equal(verified.reportMatchesJson, true);
  assert.equal(verified.maskDigests, "pass");
  assert.equal(verified.frames, names.length, "every frame is represented in the packet");

  // The reading is never presented as ready for an operational decision.
  const assessment = JSON.parse(await readFile(join(packetDirectory, "assessment.json"), "utf8"));
  assert.equal(assessment.quality.operationalStatus, "not-ready");
  assert.deepEqual(assessment.semanticClasses, ["terrain", "vegetation"]);
  assert.equal(assessment.km, null, "km is never derived from reconstructed distance");
  assert.equal(assessment.rodovia, "SP-021");

  // The positions Verge Studio cannot carry are kept beside the packet for GreenV to resolve.
  assert.equal(result.positions.length, names.length);
  assert.equal(result.positions[0].latitude, -23.5);
  assert.ok(result.measurement.quality.measuredCells >= 0);

  console.log(`  measured cells: ${result.measurement.quality.measuredCells}, ` +
    `coverage ${(result.measurement.quality.observedCellCoverage * 100).toFixed(1)}%, ` +
    `${(result.measurement.timing.totalMs / 1000).toFixed(1)} s`);
});
