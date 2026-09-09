// The RunPod envelope, read from the same file the handler is tested against.
//
// `infer-runpod.test.mjs` proves this client against a double of its own invention, which keeps
// the client honest and cannot keep the two SIDES honest — a handler written to a slightly
// different shape would pass its tests and this one's, and the mismatch would surface on a paid
// endpoint. So both suites read `services/greenv-depth-runpod/contract/depth-job-v1.example.json`:
// one job in, one manifest out, in one file. Change the shape and both fail together.
//
// Nothing here wakes anything. The fixture was written by hand and no endpoint has been deployed.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { runpodInferClient } from "../src/infer-runpod.mjs";
import { artifactOfKind } from "../src/infer.mjs";
import { isMockManifest } from "../src/run-directory.mjs";

const EXAMPLE = JSON.parse(
  readFileSync(new URL("../../greenv-depth-runpod/contract/depth-job-v1.example.json", import.meta.url), "utf8"),
);

/** The frames the worker holds: the contract's name and key, plus the bytes it downloaded. */
const frames = EXAMPLE.input.frames.map((frame, index) => ({ ...frame, bytes: Buffer.of(index) }));

/** A RunPod double that records the submitted job and answers with the contract's output. */
function endpoint(output = EXAMPLE.output) {
  const state = { submitted: null, fetched: [] };
  const fetchImpl = async (url, init = {}) => {
    const address = String(url);
    if (address.endsWith("/run")) {
      state.submitted = JSON.parse(init.body);
      return { ok: true, status: 200, json: async () => ({ id: "job-1", status: "IN_QUEUE" }), text: async () => "" };
    }
    if (address.includes("/status/")) {
      return { ok: true, status: 200, json: async () => ({ status: "COMPLETED", output }), text: async () => "" };
    }
    state.fetched.push(address);
    return { ok: true, status: 200, arrayBuffer: async () => new Uint8Array([7]).buffer };
  };
  return { state, fetchImpl };
}

const client = (fetchImpl) =>
  runpodInferClient(
    {
      endpoint: EXAMPLE.worker.endpoint,
      apiKey: "runpod-test-key",
      pollIntervalMs: 1,
      timeoutMs: 5000,
      processRes: EXAMPLE.worker.processRes,
      maxFrames: EXAMPLE.worker.maxFrames,
      fps: EXAMPLE.worker.fps,
      storage: EXAMPLE.worker.storage,
    },
    fetchImpl,
    async () => {},
  );

test("the job this client submits is the job the handler is tested against", async () => {
  const { state, fetchImpl } = endpoint();

  await client(fetchImpl).infer(frames, { sourceDurationSeconds: EXAMPLE.worker.sourceDurationSeconds });

  assert.deepEqual(state.submitted.input, EXAMPLE.input);
});

test("the manifest the handler returns is one this worker can use", async () => {
  const { state, fetchImpl } = endpoint();
  const depth = client(fetchImpl);

  const manifest = await depth.infer(frames, { sourceDurationSeconds: EXAMPLE.worker.sourceDurationSeconds });

  // What `pipeline.mjs` looks up, and what `run-directory.mjs` refuses a run without.
  const glb = artifactOfKind(manifest, "glb");
  const npz = artifactOfKind(manifest, "npz");
  assert.ok(glb && npz, "the handler must publish both a glb and an npz");
  assert.ok(Number.isInteger(manifest.frames.count) && manifest.frames.count >= 2);
  assert.ok(manifest.frames.count <= frames.length, "geometry described for frames that were never sent");
  assert.doesNotMatch(String(manifest.run_id), /[\\/]/, "a run_id becomes a directory name");
  assert.equal(isMockManifest(manifest), false, "a handler answer must never look like fixture geometry");

  // Absolute, both of them: a serverless handler has no origin to serve files from, and this
  // client rejects a relative URL rather than resolving it against RunPod's API host.
  assert.deepEqual(await depth.artifact(glb), Buffer.of(7));
  assert.deepEqual(await depth.artifact(npz), Buffer.of(7));
  assert.deepEqual(state.fetched, [glb.url, npz.url]);
});

test("provenance survives the queue, so a packet can name the model it came from", async () => {
  const { fetchImpl } = endpoint();

  const manifest = await client(fetchImpl).infer(frames);

  // `pipeline.mjs` copies these three into `measurement-result-v1.json`. A handler that answered
  // with only the four documented fields would leave every published packet unable to say which
  // model revision measured it.
  assert.equal(manifest.model_repository_id, "depth-anything/DA3NESTED-GIANT-LARGE-1.1");
  assert.match(String(manifest.model_revision), /^[0-9a-f]{40}$/);
  assert.equal(typeof manifest.timing.gpu_seconds, "number");
});
