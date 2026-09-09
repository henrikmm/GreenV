// The RunPod dialect, against a double that answers the way the API documents.
//
// No GPU is woken here and none is meant to be: the point is that a job is submitted with keys
// rather than bytes, that the worker waits through the queue without giving up early, and that a
// job which ends any way but COMPLETED becomes an error a human can read.

import assert from "node:assert/strict";
import test from "node:test";

import { runpodInferClient } from "../src/infer-runpod.mjs";

const ENDPOINT = "https://api.runpod.ai/v2/greenv-depth";

const MANIFEST = {
  run_id: "20260909-013000-abcdef",
  frames: { count: 2 },
  artifacts: [
    { kind: "glb", url: "https://bucket.example/scene.glb", size_bytes: 12 },
    { kind: "npz", url: "https://bucket.example/result.npz", size_bytes: 34 },
  ],
};

/** A double that walks a job through the statuses RunPod reports, in order. */
function runpod(statuses, { onRun } = {}) {
  const calls = [];
  let step = 0;
  const fetchImpl = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method ?? "GET", body: init.body });
    if (String(url).endsWith("/run")) {
      onRun?.(JSON.parse(init.body));
      return json({ id: "job-1", status: "IN_QUEUE" });
    }
    if (String(url).includes("/status/")) {
      return json(statuses[Math.min(step++, statuses.length - 1)]);
    }
    return { ok: true, status: 200, arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer };
  };
  return { fetchImpl, calls };
}

const json = (body) => ({ ok: true, status: 200, json: async () => body, text: async () => "" });

const client = (fetchImpl, overrides = {}) =>
  runpodInferClient(
    {
      endpoint: ENDPOINT,
      apiKey: "runpod-test-key",
      pollIntervalMs: 1,
      timeoutMs: 5000,
      processRes: 504,
      maxFrames: 112,
      fps: 10,
      storage: { bucket: "greenv-captures", endpoint: "https://r2.example", region: "auto" },
      ...overrides,
    },
    fetchImpl,
    async () => {},
  );

const frames = [
  { name: "frame-0001.jpg", key: "capture-sessions/s/segments/00000000/sampled-frames/frame-0001.jpg", bytes: Buffer.of(1) },
  { name: "frame-0002.jpg", key: "capture-sessions/s/segments/00000000/sampled-frames/frame-0002.jpg", bytes: Buffer.of(2) },
];

test("sends object keys, not the frames themselves, and waits out the queue", async () => {
  let submitted = null;
  const { fetchImpl, calls } = runpod(
    [{ status: "IN_QUEUE" }, { status: "IN_PROGRESS" }, { status: "COMPLETED", output: MANIFEST }],
    { onRun: (body) => { submitted = body; } },
  );

  const manifest = await client(fetchImpl).infer(frames, { sourceDurationSeconds: 10 });

  assert.deepEqual(manifest, MANIFEST);
  assert.deepEqual(
    submitted.input.frames,
    frames.map((frame) => ({ name: frame.name, key: frame.key })),
    "a frame's bytes never enter the request body",
  );
  assert.equal(submitted.input.storage.bucket, "greenv-captures");
  assert.deepEqual(submitted.input.params, {
    fps: 10, process_res: 504, max_frames: 112, source_duration_s: 10,
  });
  assert.equal(calls.filter((call) => call.url.includes("/status/")).length, 3,
    "polled until the job left the queue, rather than reading the first answer");
});

test("refuses a frame with no key rather than paying for a GPU to find out", async () => {
  const { fetchImpl, calls } = runpod([{ status: "COMPLETED", output: MANIFEST }]);
  await assert.rejects(
    () => client(fetchImpl).infer([frames[0], { name: "frame-0002.jpg", bytes: Buffer.of(2) }]),
    /frame-0002\.jpg has no object key/,
  );
  assert.equal(calls.length, 0, "nothing was submitted");
});

test("a job that fails is an error naming the job and the status", async () => {
  const { fetchImpl } = runpod([{ status: "FAILED", error: "handler raised RuntimeError" }]);
  await assert.rejects(
    () => client(fetchImpl).infer(frames),
    /RunPod job job-1 ended FAILED: handler raised RuntimeError/,
  );
});

test("a job that never leaves the queue ends on the deadline", async () => {
  const { fetchImpl } = runpod([{ status: "IN_QUEUE" }]);
  await assert.rejects(
    () => client(fetchImpl, { timeoutMs: 0 }).infer(frames),
    /still IN_QUEUE after 0 ms/,
  );
});

test("an artifact must be an absolute URL, because a handler serves nothing itself", async () => {
  const { fetchImpl } = runpod([{ status: "COMPLETED", output: MANIFEST }]);
  const depth = client(fetchImpl);

  assert.deepEqual(
    await depth.artifact({ url: "https://bucket.example/scene.glb", size_bytes: 3 }),
    Buffer.of(1, 2, 3),
  );
  await assert.rejects(
    () => depth.artifact({ url: "/artifacts/scene.glb" }),
    /must be absolute/,
  );
});
