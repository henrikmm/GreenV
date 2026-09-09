import test from "node:test";
import assert from "node:assert/strict";
import { singleFlight } from "../src/gate.mjs";

const defer = () => { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; };

// Two measurements at once is never faster: they serialise behind the depth service's own lock
// and each materialises a ~110 MB run. The bound has to hold across both trigger paths.
test("run() serialises, so a second caller waits its turn", async () => {
  const gate = singleFlight();
  const first = defer();
  const order = [];

  const a = gate.run(async () => { order.push("a-start"); await first.promise; order.push("a-end"); });
  const b = gate.run(async () => { order.push("b-start"); });

  assert.equal(gate.busy, true);
  first.resolve();
  await Promise.all([a, b]);
  assert.deepEqual(order, ["a-start", "a-end", "b-start"]);
  assert.equal(gate.busy, false);
});

test("tryRun() refuses rather than queueing", async () => {
  const gate = singleFlight();
  const held = defer();

  const running = gate.tryRun(async () => { await held.promise; return "done"; });
  assert.notEqual(running, null);
  assert.equal(gate.tryRun(() => "second"), null, "the second caller is told no, not queued");

  held.resolve();
  assert.equal(await running, "done");

  const afterwards = gate.tryRun(() => "later");
  assert.notEqual(afterwards, null, "the slot frees up once the first job finishes");
  assert.equal(await afterwards, "later");
});

test("a failing job still releases the slot", async () => {
  const gate = singleFlight();
  await assert.rejects(gate.run(async () => { throw new Error("boom"); }), /boom/);
  assert.equal(gate.busy, false);
  assert.equal(await gate.run(async () => "next"), "next");
});
