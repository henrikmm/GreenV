// The trigger's poll contract: a caller who is handed a job id must be able to read the result.
//
// The failure this guards against is not in measuring — it is in answering. A finished job that
// cannot be serialised is a measurement that ran, published, and is unreachable through the API
// that produced it, which is indistinguishable from never having run.

import test from "node:test";
import assert from "node:assert/strict";
import { createHttpTrigger } from "../src/http.mjs";
import { singleFlight } from "../src/gate.mjs";

/** Listens on an ephemeral port so the suite can run anywhere, including in CI. */
async function trigger(measure) {
  const http = createHttpTrigger({ measure, gate: singleFlight() });
  await http.listen(0, "127.0.0.1");
  const { port } = http.server.address();
  const call = async (method, path, body) => {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, {
      method,
      ...(body ? { headers: { "content-type": "application/json" }, body: JSON.stringify(body) } : {}),
    });
    return { status: response.status, body: await response.json() };
  };
  return { call, close: () => http.close() };
}

const settled = async (call, id) => {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const polled = await call("GET", `/measurements/${id}`);
    if (polled.body.status !== "running") return polled;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error("job never settled");
};

test("a completed job polls through to done, with its result", async (t) => {
  const server = await trigger(async () => ({ runId: "replay-1", measurement: { offsetM: 2 } }));
  t.after(() => server.close());

  const accepted = await server.call("POST", "/measurements", { sessionId: "s", segmentIndex: 0 });
  assert.equal(accepted.status, 202);

  const polled = await settled(server.call, accepted.body.id);
  assert.equal(polled.status, 200, "a finished job answers 200, not 400");
  assert.equal(polled.body.status, "done");
  assert.equal(polled.body.result.runId, "replay-1");
});

test("a failed job polls through to failed, with its reason", async (t) => {
  const server = await trigger(async () => {
    const error = new Error("no segment manifest");
    error.code = "segment_manifest_absent";
    throw error;
  });
  t.after(() => server.close());

  const accepted = await server.call("POST", "/measurements", { sessionId: "s", segmentIndex: 0 });
  const polled = await settled(server.call, accepted.body.id);

  assert.equal(polled.status, 200);
  assert.equal(polled.body.status, "failed");
  assert.deepEqual(polled.body.error, { code: "segment_manifest_absent", message: "no segment manifest" });
});
