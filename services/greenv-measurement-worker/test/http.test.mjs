// The polled trigger, exercised the way docs/AUTOMATIC-HEIGHT.md tells a caller to use it:
// POST /measurements, then GET /measurements/<id> until the status is terminal.
//
// It is polled through the real route on purpose. Both defects these tests cover were invisible
// to anything that inspected the job map directly — the job was always correct, and only
// serialising it was not — so a test that asserts on `http.jobs` would have passed throughout.

import test from "node:test";
import assert from "node:assert/strict";
import { createHttpTrigger } from "../src/http.mjs";
import { singleFlight } from "../src/gate.mjs";

const defer = () => { let resolve, reject; const promise = new Promise((res, rej) => { resolve = res; reject = rej; }); return { promise, resolve, reject }; };

/** A trigger on an ephemeral port, plus the two calls a caller makes against it. */
async function trigger(measure) {
  const events = [];
  const http = createHttpTrigger({ measure, gate: singleFlight(), log: (fields) => events.push(fields) });
  await http.listen(0, "127.0.0.1");
  const base = `http://127.0.0.1:${http.server.address().port}`;

  const start = async (body = { sessionId: "s", segmentIndex: 0 }) => {
    const response = await fetch(`${base}/measurements`, {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body),
    });
    return { status: response.status, body: await response.json() };
  };
  const poll = async (id) => {
    const response = await fetch(`${base}/measurements/${id}`);
    return { status: response.status, body: await response.json() };
  };
  const pollUntilTerminal = async (id) => {
    for (let attempt = 0; attempt < 50; attempt++) {
      const polled = await poll(id);
      // A non-200 is the failure being reproduced, not a state to keep polling through.
      assert.equal(polled.status, 200, `poll answered ${polled.status}: ${JSON.stringify(polled.body)}`);
      if (polled.body.status !== "running") return polled;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error("job never reached a terminal state");
  };

  return { events, start, poll, pollUntilTerminal, close: () => http.close() };
}

// The defect this exists for: `retire` parks a live Timeout on the job when it finishes, and
// serialising the raw job threw "Converting circular structure to JSON" on the timer. So the
// route worked for as long as there was nothing to report and broke at the exact moment the
// result existed — which is every finished job, every time.
test("a finished job is polled to its result through the real route", async (t) => {
  const held = defer();
  const result = { outputPrefix: "capture-sessions/s/segments/00000000", runId: "run-1", mock: false, heightCm: 42 };
  const http = await trigger(async () => held.promise);
  t.after(() => http.close());

  const accepted = await http.start();
  assert.equal(accepted.status, 202);
  assert.equal(accepted.body.status, "running");

  // Before the result exists the route already worked, so this is the half that never broke.
  const running = await http.poll(accepted.body.id);
  assert.equal(running.status, 200);
  assert.equal(running.body.status, "running");

  held.resolve(result);
  const done = await http.pollUntilTerminal(accepted.body.id);
  assert.equal(done.body.status, "done");
  assert.deepEqual(done.body.result, result);
  assert.equal(done.body.error, null);
});

// What a poller is answered with is a contract, so it is named here rather than left to be
// whatever the job object happens to hold. A job grows internal bookkeeping over time — the TTL
// timer is one piece of it — and none of that belongs on the wire.
test("the polled body carries the job's public fields and nothing else", async (t) => {
  const http = await trigger(async () => ({ runId: "run-2" }));
  t.after(() => http.close());

  const accepted = await http.start();
  const done = await http.pollUntilTerminal(accepted.body.id);

  assert.deepEqual(Object.keys(done.body).sort(), ["error", "id", "result", "startedAt", "status"]);
  assert.equal(done.body.id, accepted.body.id);
  assert.match(done.body.startedAt, /^\d{4}-\d{2}-\d{2}T/);
});

// The second defect: neither continuation logged, so a measurement started over HTTP that failed
// left nothing on stdout at all. Combined with the first defect it looked exactly like a hang —
// nothing in `docker compose logs`, and the one route that would have shown the error answered
// 400 instead. The queue path logs `message-failed` with the code and message, which is how the
// underlying cause was eventually found; this path has to be findable the same way.
test("a failing job reports its error to the poller and to the log", async (t) => {
  const failure = Object.assign(new Error("depth service unreachable"), { code: "depth-unreachable" });
  const http = await trigger(async () => { throw failure; });
  t.after(() => http.close());

  const accepted = await http.start();
  const failed = await http.pollUntilTerminal(accepted.body.id);

  assert.equal(failed.body.status, "failed");
  assert.deepEqual(failed.body.error, { code: "depth-unreachable", message: "depth service unreachable" });
  assert.equal(failed.body.result, null);

  const terminal = http.events.find((event) => event.event === "measurement-failed");
  assert.ok(terminal, `no terminal event logged; got ${JSON.stringify(http.events)}`);
  assert.equal(terminal.id, accepted.body.id);
  assert.equal(terminal.code, "depth-unreachable");
  assert.equal(terminal.error, "depth service unreachable");
});

// An error with no `code` is the common case for a bug rather than an outage, and it must still
// be findable — `unknown` is what the queue path records for the same thing.
test("an uncoded failure is still logged, as unknown", async (t) => {
  const http = await trigger(async () => { throw new Error("frames missing"); });
  t.after(() => http.close());

  const accepted = await http.start();
  const failed = await http.pollUntilTerminal(accepted.body.id);

  assert.deepEqual(failed.body.error, { code: "unknown", message: "frames missing" });
  assert.equal(http.events.find((event) => event.event === "measurement-failed")?.code, "unknown");
});

test("a successful job logs the segment it measured", async (t) => {
  const result = { outputPrefix: "capture-sessions/s/segments/00000007", runId: "run-3", mock: true };
  const http = await trigger(async () => result);
  t.after(() => http.close());

  const accepted = await http.start();
  await http.pollUntilTerminal(accepted.body.id);

  const terminal = http.events.find((event) => event.event === "measured");
  assert.ok(terminal, `no terminal event logged; got ${JSON.stringify(http.events)}`);
  assert.deepEqual(terminal, {
    event: "measured", id: accepted.body.id, segment: result.outputPrefix, runId: "run-3", mock: true,
  });
});

test("an unknown job id is a 404, not a 500", async (t) => {
  const http = await trigger(async () => ({}));
  t.after(() => http.close());

  const missing = await http.poll("00000000-0000-4000-8000-000000000000");
  assert.equal(missing.status, 404);
  assert.equal(missing.body.error, "no such measurement job");
});
