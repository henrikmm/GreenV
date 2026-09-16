import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { measurementPipeline, windowsOf, RESULT_SCHEMA, measureEvery } from "../src/pipeline.mjs";
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

test("a re-measure starts from the reconstruction kept beside the frames and wakes no GPU", async () => {
  const earlier = {
    runId: "20260913-140757-87b58d", sourceGeneration: "a".repeat(64), mock: false,
    depth: { service: "runpod:2q5q0j3e9ug08q", modelRepositoryId: "depth-anything/DA3NESTED-GIANT-LARGE-1.1", modelRevision: "b2359bd", framesDescribed: 4 },
  };
  const { measure, calls, request } = harness({
    storageOverrides: {
      [`${PREFIX}/measurement/measurement-result-v1.json`]: earlier,
      [`${PREFIX}/depth/${earlier.runId}/scene.glb`]: Buffer.from("GLB"),
      [`${PREFIX}/depth/${earlier.runId}/result.npz`]: Buffer.from("NPZ"),
    },
    env: { GREENV_MEASUREMENT_OFFSET_SIDE: "auto", GREENV_MEASUREMENT_MIN_TRACK_M: "3", GREENV_MEASUREMENT_CAMERA_HEIGHT_M: "1.25", GREENV_MEASUREMENT_SCALE_ANCHOR: "telemetry", GREENV_MEASUREMENT_MAX_HEIGHT_M: "3", GREENV_MEASUREMENT_CANOPY_EXTENT_M: "2", GREENV_MEASUREMENT_GROUND_FALLBACK: "true", GREENV_MEASUREMENT_CANOPY_GAP_M: "0.5", GREENV_MEASUREMENT_BAND_WIDTH_M: "5.5", GREENV_MEASUREMENT_DATUM: "per-frame", GREENV_MEASUREMENT_EXCLUDE_NEAR: "fence,wall,pole,building", GREENV_MEASUREMENT_SLOPE_RISE_M: "0.1", GREENV_MEASUREMENT_STRUCTURE_FRAMES: "3", GREENV_MEASUREMENT_PAST_ENDS: "drop", GREENV_MEASUREMENT_STRUCTURE_MODEL: "ade20k-b4", GREENV_MEASUREMENT_STRUCTURE_CLASSES: "fence,railing,wall", GREENV_MEASUREMENT_STRUCTURE_FLOOR: "0.4", GREENV_MEASUREMENT_STRUCTURE_MODEL_MASK: "band" },
  });
  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0, force: true, reuseDepth: true });

  assert.equal(calls.infer, 0, "the geometry has not changed, so no GPU runs");
  assert.equal(calls.assess, 1);
  assert.equal(result.runId, earlier.runId);
  assert.equal(result.depth.reused, true);
  assert.equal(result.depth.service, "runpod:2q5q0j3e9ug08q", "the packet still names the service that computed the geometry");
  assert.equal(result.depth.gpuSeconds, null);
  // The vehicle-mount settings travel to Verge Studio as an explicit request, and are recorded.
  assert.equal(request().offsetSide, "auto");
  assert.equal(request().minTrackM, 3);
  assert.equal(request().cameraHeightM, 1.25);
  // The test manifest is not distance-grouped, so the telemetry anchor has nothing to say.
  assert.equal(request().trackLengthM, null);
  assert.deepEqual(request().gridOptions, { maxHeightM: 3, canopyExtentM: 2, canopyGapM: 0.5, maxDistanceFromRoadM: 5.5, slopeRiseM: 0.1, structureFrames: 3, pastEnds: "drop", datum: "per-frame" }, "the ceilings, the gap, the band width, the slope rise, the structure bar, the ends and the datum travel as grid options");
  assert.equal(result.measurement.slopeRiseM, 0.1);
  assert.deepEqual([result.measurement.structureFrames, result.measurement.pastEnds], [3, "drop"]);
  assert.deepEqual([request().structureModel, request().structureClasses, request().structureFloor, request().structureModelMask], ["ade20k-b4", "fence,railing,wall", 0.4, "band"], "the second model, its classes, its floor and what its mask does travel with the request");
  assert.deepEqual([result.measurement.structureModel, result.measurement.structureClasses, result.measurement.structureFloor, result.measurement.structureModelMask], ["ade20k-b4", "fence,railing,wall", 0.4, "band"]);
  assert.deepEqual([result.measurement.maxHeightM, result.measurement.canopyExtentM, result.measurement.canopyGapM, result.measurement.bandWidthM, result.measurement.datum], [3, 2, 0.5, 5.5, "per-frame"]);
  assert.equal(request().groundFallback, true);
  assert.equal(result.measurement.groundFallback, true);
  assert.deepEqual([request().excludeNearClasses, request().excludeNearPx], ["fence,wall,pole,building", 1]);
  assert.deepEqual([result.measurement.excludeNear, result.measurement.excludeNearPx], ["fence,wall,pole,building", 1]);
  assert.deepEqual(
    [result.measurement.offsetSide, result.measurement.minTrackM, result.measurement.cameraHeightM, result.measurement.scaleAnchor],
    ["auto", 3, 1.25, "telemetry"],
  );
});

test("the GPS path length of the sampled frames reaches Verge Studio as the scale anchor", async () => {
  const grouped = segmentManifest({
    samplingStrategy: "distance-groups",
    sampledFrames: segmentManifest().sampledFrames.map((f, i) => ({ ...f, distanceMeters: 4.2 + i * 9.9 })),
  });
  const { measure, request } = harness({
    storageOverrides: { [`${PREFIX}/segment-manifest-v2.json`]: grouped },
    env: { GREENV_MEASUREMENT_SCALE_ANCHOR: "telemetry" },
  });
  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });
  assert.ok(Math.abs(request().trackLengthM - 29.7) < 1e-9, `span of the sampled frames' distances, got ${request().trackLengthM}`);
  assert.ok(Math.abs(result.measurement.trackLengthM - 29.7) < 1e-9);

  const off = harness({ storageOverrides: { [`${PREFIX}/segment-manifest-v2.json`]: grouped } });
  await off.measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });
  assert.equal(off.request().trackLengthM, null, "no anchor unless the deployment asks for one");
});

test("a kept reconstruction missing one artifact falls through to a fresh inference", async () => {
  const earlier = { runId: "earlier", sourceGeneration: "a".repeat(64), mock: false, depth: { framesDescribed: 4 } };
  const { measure, calls, request } = harness({
    storageOverrides: {
      [`${PREFIX}/measurement/measurement-result-v1.json`]: earlier,
      [`${PREFIX}/depth/earlier/scene.glb`]: Buffer.from("GLB"),
    },
    env: { GREENV_MEASUREMENT_REUSE_DEPTH: "true" },
  });
  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0, force: true });
  assert.equal(calls.infer, 1);
  assert.equal(result.depth.reused, false);
  assert.equal(result.measurement.offsetSide, "given", "Verge Studio's own default unless the deployment says otherwise");
  assert.equal("gridOptions" in request(), false, "no ceiling is sent when none is configured");
  assert.equal(request().groundFallback, false, "the strict fit alone unless the deployment says otherwise");
  assert.equal("excludeNearClasses" in request(), false, "nothing excluded unless the deployment names it");
});

test("the 110 MB run directory does not survive the measurement", async () => {
  const { measure, config } = harness();
  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });
  await assert.rejects(stat(join(config.measurement.runsRoot, result.runId)), "the run is discarded once its packet is published");
});

// A segmento de 13 de setembro: quatro grupos de 10 m e 110 quadros entre eles. O empacotamento
// devolve uma janela só, e o pacote continua onde sempre esteve.
const grouped = (groupSizes, metresPerGroup = 25) => {
  const frames = [];
  const groups = [];
  let index = 0;
  groupSizes.forEach((count, group) => {
    groups.push({
      index: group,
      published: true,
      startMeters: group * metresPerGroup,
      endMeters: (group + 1) * metresPerGroup,
    });
    for (let i = 0; i < count; i++, index++) {
      frames.push({
        index,
        fileName: `frame-${String(index + 1).padStart(4, "0")}.jpg`,
        timestampSeconds: index / 10,
        sizeBytes: 10,
        sha256: "b".repeat(64),
        distanceMeters: group * metresPerGroup + (i * metresPerGroup) / count,
        groupIndex: group,
      });
    }
  });
  return segmentManifest({ sampledFrames: frames, groups });
};

test("um segmento medido inteiro continua sendo uma janela só", () => {
  const windows = windowsOf(grouped([28, 27, 28, 27], 10), 112);
  assert.equal(windows.length, 1);
  assert.equal(windows[0].windowed, false, "sem janela nomeada, o pacote fica no caminho de sempre");
  assert.equal(windows[0].frames.length, 110);
});

test("grupos consecutivos são empacotados até o teto de uma execução", () => {
  const windows = windowsOf(grouped([40, 40, 40]), 112);
  assert.deepEqual(windows.map((w) => w.frames.length), [80, 40], "80 cabem, 120 não");
  assert.deepEqual(windows.map((w) => w.groupIndices), [[0, 1], [2]]);
  assert.deepEqual(windows.map((w) => [w.startMeters, w.endMeters]), [[0, 50], [50, 75]]);
  assert.ok(windows.every((w) => w.windowed), "duas janelas, cada uma com o seu lugar na estrada");
});

test("um grupo por janela quando cada um já enche a execução", () => {
  const windows = windowsOf(grouped([76, 76, 76, 76, 76, 76, 76, 76]), 112);
  assert.equal(windows.length, 8, "200 m em janelas de 25 m, que é o que o extrator agora publica");
  assert.deepEqual(windows[7].groupIndices, [7]);
});

test("um manifesto sem grupos é uma janela, como o extrator de tempo uniforme entrega", () => {
  const windows = windowsOf(segmentManifest(), 112);
  assert.equal(windows.length, 1);
  assert.equal(windows[0].windowed, false);
});

test("cada janela vira uma execução, um pacote e um anúncio", async () => {
  const manifest = grouped([50, 50, 50]);
  const { measure, storage, calls } = harness({
    storageOverrides: {
      [`${PREFIX}/segment-manifest-v2.json`]: manifest,
      [`${PREFIX}/frame-metadata-v2.json`]: manifest.sampledFrames.map((f, i) => ({
        index: i,
        presentationTimeNanos: i * 100_000_000,
        capturedAtUtc: new Date(Date.UTC(2026, 8, 8, 10, 0, 0)).toISOString(),
        locationQuality: "good",
        location: { latitude: -23.5 + i / 1000, longitude: -46.7, horizontalAccuracyMeters: 4 },
      })),
      ...Object.fromEntries(manifest.sampledFrames.map((f) => [`${PREFIX}/sampled-frames/${f.fileName}`, Buffer.from("jpeg")])),
    },
  });

  const result = await measure({ sessionId: segmentManifest().sessionId, segmentIndex: 0 });

  assert.equal(calls.infer, 2, "50+50 cabem numa execução, o terceiro grupo pede outra");
  assert.equal(calls.assess, 2);
  assert.equal(result.windows.length, 2);
  assert.deepEqual(result.windows.map((w) => w.windowIndex), [0, 1]);
  assert.deepEqual(result.windows.map((w) => w.windowStartMeters), [0, 50]);
  for (const name of ["assessment.json", "report.html", "SHA256SUMS"]) {
    assert.ok(storage.written.has(`${PREFIX}/measurement/w00/${name}`), `janela 0 publicou ${name}`);
    assert.ok(storage.written.has(`${PREFIX}/measurement/w01/${name}`), `janela 1 publicou ${name}`);
  }
  assert.ok(!storage.written.has(`${PREFIX}/measurement/assessment.json`), "nada no caminho do segmento inteiro");
});

test("windows are measured a few at a time and still come back in their own order", async () => {
  let running = 0;
  let maximum = 0;
  const started = [];
  const medir = async (window) => {
    running += 1;
    maximum = Math.max(maximum, running);
    started.push(window.index);
    // A later window finishing first is the whole point: the first window is the slowest here.
    await new Promise((resolve) => setTimeout(resolve, window.index === 0 ? 20 : 1));
    running -= 1;
    return { windowIndex: window.index };
  };

  const windows = [0, 1, 2, 3, 4].map((index) => ({ index }));
  const results = await measureEvery(windows, medir, 2);

  assert.deepEqual(results.map((r) => r.windowIndex), [0, 1, 2, 3, 4]);
  assert.equal(maximum, 2, "never more than the limit at once");
  assert.deepEqual(started.slice(0, 2), [0, 1], "the first two start together");
});

test("one at a time is the default, and a single window needs no lane of its own", async () => {
  let running = 0;
  let maximum = 0;
  const medir = async () => {
    running += 1;
    maximum = Math.max(maximum, running);
    await new Promise((resolve) => setTimeout(resolve, 1));
    running -= 1;
    return {};
  };

  await measureEvery([{ index: 0 }, { index: 1 }, { index: 2 }], medir);

  assert.equal(maximum, 1);
});
