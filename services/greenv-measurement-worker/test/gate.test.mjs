import test from "node:test";
import assert from "node:assert/strict";
import { singleFlight, boundedFlight } from "../src/gate.mjs";

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

test("lets as many through at once as the bound allows, and no more", async () => {
  const gate = boundedFlight(3);
  let running = 0;
  let maximum = 0;
  const hold = async () => {
    running += 1;
    maximum = Math.max(maximum, running);
    await new Promise((resolve) => setTimeout(resolve, 5));
    running -= 1;
  };

  await Promise.all(Array.from({ length: 8 }, () => gate.run(hold)));

  assert.equal(maximum, 3);
  assert.equal(gate.inFlight, 0, "every slot comes back");
});

test("a fourth caller at the door is told no rather than queued", async () => {
  const gate = boundedFlight(2);
  const held = [];
  const hold = () => new Promise((resolve) => held.push(resolve));

  const first = gate.tryRun(hold);
  const second = gate.tryRun(hold);
  const third = gate.tryRun(hold);

  assert.ok(first, "the first takes a slot");
  assert.ok(second, "the second takes the other");
  assert.equal(third, null, "the third is refused, not queued");

  for (const release of held) release();
  await Promise.all([first, second]);
  assert.equal(gate.busy, false)
})
