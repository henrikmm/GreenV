import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { measurementPipeline, RESULT_SCHEMA } from "../src/pipeline.mjs";
import { loadConfig } from "../src/config.mjs";

const PREFIX = "capture-sessions/11111111-1111-7111-8111-111111111111/segments/00000000";

function fakeStorage(objects = {}) {
  const store = new Map(Object.entries(objects));
  return {
    written: store,
    async get(key) {
      if (!store.has(key)) throw new Error(`no object ${key}`);
      const value = store.get(key);
      return Buffer.isBuffer(value) ? value : Buffer.from(JSON.stringify(value));
    },
    async getJson(key) {
      if (!store.has(key)) throw new Error(`no object ${key}`);
      return store.get(key);
    },
    async put(key, bytes) {
      store.set(key, bytes);
      return { objectKey: key, sha256: "x".repeat(64), bytes: bytes.length };
    },
  };
}

const segmentManifest = (over = {}) => ({
  schemaVersion: 2,
  sessionId: "11111111-1111-7111-8111-111111111111",
  segmentIndex: 0,
  sourceGeneration: "a".repeat(64),
  durationMillis: 10_000,
  sampledFrames: Array.from({ length: 4 }, (_, i) => ({
    index: i, fileName: `frame-${String(i + 1).padStart(4, "0")}.jpg`, timestampSeconds: i / 10, sizeBytes: 10, sha256: "b".repeat(64),
  })),
  ...over,
});

const telemetry = Array.from({ length: 4 }, (_, i) => ({
  index: i,
  presentationTimeNanos: i * 100_000_000,
  capturedAtUtc: new Date(Date.UTC(2026, 8, 8, 10, 0, i)).toISOString(),
  locationQuality: "good",
  location: { latitude: -23.5 + i / 100, longitude: -46.7, horizontalAccuracyMeters: 4 },
}));

function harness({ storageOverrides = {}, depthManifest, env = {} } = {}) {
  const objects = {
    [`${PREFIX}/segment-manifest-v2.json`]: segmentManifest(),
    [`${PREFIX}/frame-metadata-v2.json`]: telemetry,
    ...Object.fromEntries(segmentManifest().sampledFrames.map((f) => [`${PREFIX}/sampled-frames/${f.fileName}`, Buffer.from(`jpeg-${f.index}`)])),
    ...storageOverrides,
  };
  const storage = fakeStorage(objects);
  const calls = { infer: 0, assess: 0 };

  const infer = {
    async infer() {
      calls.infer += 1;
      return depthManifest ?? {
        run_id: "20260908-101500-abc123",
        model_repository_id: "depth-anything/DA3NESTED-GIANT-LARGE-1.1",
        model_revision: "b2359bd",
        frames: { count: 4 },
        timing: { gpu_seconds: 12.5 },
        artifacts: [
          { kind: "glb", name: "scene.glb", size_bytes: 3, sha256: "c".repeat(64), url: "/artifact/x/scene.glb" },
          { kind: "npz", name: "result.npz", size_bytes: 3, sha256: "d".repeat(64), url: "/artifact/x/result.npz" },
        ],
      };
    },
    async artifact() { return Buffer.from("BIN"); },
  };

  let observed = null;
  const runner = {
    async assess(request) {
      calls.assess += 1;
      observed = request;
      return {
        summary: { contentSha256: "e".repeat(64), quality: { operationalStatus: "not-ready", measuredCells: 7 }, timing: { totalMs: 1234 } },
        artifacts: {
          "assessment.json": Buffer.from("{}"),
          "report.html": Buffer.from("<html></html>"),
          "SHA256SUMS": Buffer.from("sums"),
        },
      };
    },
  };

  const config = loadConfig({ VERGE_RUNS_ROOT: join(tmpdir(), `runs-${Math.random().toString(16).slice(2)}`), ...env });
  return { measure: measurementPipeline({ config, storage, infer, runner }), storage, calls, config, request: () => observed };
}

test("a segment becomes a packet published beside its frames", async () => {
  const { measure, storage, calls, request } = harness();
  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0, rodovia: "SP-021", sentido: "norte" });

  assert.equal(result.schemaVersion, RESULT_SCHEMA);
  assert.equal(result.runId, "20260908-101500-abc123");
  assert.equal(result.mock, false);
  assert.equal(calls.infer, 1);
  assert.equal(calls.assess, 1);

  for (const name of ["assessment.json", "report.html", "SHA256SUMS"]) {
    assert.ok(storage.written.has(`${PREFIX}/measurement/${name}`), `${name} was published`);
  }
  assert.ok(storage.written.has(`${PREFIX}/measurement/measurement-result-v1.json`));

  // The class policy and the road identity reach Verge Studio as an explicit request, never as
  // an assumed default.
  assert.equal(request().classes, "terrain,vegetation");
  assert.equal(request().context.rodovia, "SP-021");
  assert.equal(request().context.km, null);
  assert.equal(Object.keys(request().frameContext).length, 4);
});

test("a segment already measured from the same bytes is not measured again", async () => {
  const { measure, calls } = harness({
    storageOverrides: {
      [`${PREFIX}/measurement/measurement-result-v1.json`]: { runId: "earlier", sourceGeneration: "a".repeat(64) },
    },
  });
  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });

  assert.equal(result.reused, true);
  assert.equal(result.runId, "earlier");
  assert.equal(calls.infer, 0, "the GPU is not woken to recompute an answer we already hold");
});

test("a re-uploaded segment is measured again rather than reusing a stale reading", async () => {
  const { measure, calls } = harness({
    storageOverrides: {
      [`${PREFIX}/measurement/measurement-result-v1.json`]: { runId: "earlier", sourceGeneration: "different" },
    },
  });
  await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });
  assert.equal(calls.infer, 1);
});

// The 2026-08-05 lesson: fixture geometry wearing another clip's face.
test("a mock reconstruction is refused unless the deployment accepts one", async () => {
  const depthManifest = {
    run_id: "mock-9f2c1a44", frames: { count: 4 }, mock: true,
    artifacts: [
      { kind: "glb", name: "scene.glb", size_bytes: 3, sha256: "fixture", url: "/roadside/scene.glb" },
      { kind: "npz", name: "result.npz", size_bytes: 3, sha256: "fixture", url: "/roadside/result.npz" },
    ],
  };
  const refused = harness({ depthManifest });
  await assert.rejects(
    refused.measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 }),
    (error) => error.code === "depth_service_is_a_mock" && error.retryable === false,
  );
  assert.equal(refused.calls.assess, 0);

  const allowed = harness({ depthManifest, env: { GREENV_MEASUREMENT_ALLOW_MOCK: "true" } });
  const result = await allowed.measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });
  assert.equal(result.mock, true, "an accepted mock packet still says so");
});

test("absent frames are retryable, an unusable segment is not", async () => {
  const missing = harness({ storageOverrides: { [`${PREFIX}/segment-manifest-v2.json`]: undefined } });
  missing.storage.written.delete(`${PREFIX}/segment-manifest-v2.json`);
  await assert.rejects(
    missing.measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 }),
    (error) => error.code === "segment_manifest_absent" && error.retryable === true,
  );

  const single = harness({
    storageOverrides: { [`${PREFIX}/segment-manifest-v2.json`]: segmentManifest({ sampledFrames: [{ index: 0, fileName: "frame-0001.jpg", timestampSeconds: 0 }] }) },
  });
  await assert.rejects(
    single.measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 }),
    (error) => error.code === "insufficient_frames" && error.retryable === false,
  );
});

test("the 110 MB run directory does not survive the measurement", async () => {
  const { measure, config } = harness();
  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });
  await assert.rejects(stat(join(config.measurement.runsRoot, result.runId)), "the run is discarded once its packet is published");
});
