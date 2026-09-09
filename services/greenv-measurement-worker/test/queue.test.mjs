import test from "node:test";
import assert from "node:assert/strict";
import { loadConfig } from "../src/config.mjs";
import { connectQueue } from "../src/queue/index.mjs";
import { nextAction } from "../src/queue/azure.mjs";

const AZURE = {
  GREENV_SEGMENT_QUEUE_ADAPTER: "azure-queue",
  GREENV_AZURE_MEASUREMENT_QUEUE_NAME: "greenv-segment-measure-v1",
  GREENV_AZURE_MEASURED_QUEUE_NAME: "greenv-segment-measured-v1",
  GREENV_AZURE_QUEUE_ENDPOINT: "https://account.queue.core.windows.net/",
};

test("the transport is the switch the whole stack already reads", () => {
  assert.equal(loadConfig({}).queue.adapter, "rabbitmq", "nothing moves for anyone running locally");
  assert.equal(loadConfig(AZURE).queue.adapter, "azure-queue");
  assert.throws(
    () => loadConfig({ GREENV_SEGMENT_QUEUE_ADAPTER: "sqs" }),
    /must be "rabbitmq" or "azure-queue"/,
  );
});

// A worker that cannot name its queues looks healthy and drains nothing, which is the failure
// this whole change exists to remove. It has to be loud, and it has to be loud at startup.
test("an azure-queue deployment that cannot name its queues fails at startup", () => {
  assert.throws(
    () => loadConfig({ GREENV_SEGMENT_QUEUE_ADAPTER: "azure-queue" }),
    /GREENV_AZURE_MEASUREMENT_QUEUE_NAME and GREENV_AZURE_MEASURED_QUEUE_NAME are required/,
  );
  assert.throws(
    () => loadConfig({ ...AZURE, GREENV_AZURE_QUEUE_ENDPOINT: "" }),
    /GREENV_AZURE_QUEUE_ENDPOINT or GREENV_AZURE_STORAGE_CONNECTION_STRING is required/,
  );
  // The HTTP trigger alone stays a legitimate deployment: with no queue there is nothing to name.
  assert.doesNotThrow(() =>
    loadConfig({ GREENV_SEGMENT_QUEUE_ADAPTER: "azure-queue", GREENV_MEASUREMENT_QUEUE_ENABLED: "false" }));
});

test("the selector routes azure-queue away from RabbitMQ", async () => {
  // The message names the Azure queues, which the RabbitMQ adapter has no concept of - so this
  // fails only if the selector dispatched to the wrong module. It also never touches a network:
  // the guard runs before any client is built.
  await assert.rejects(
    connectQueue({ adapter: "azure-queue", azure: { endpoint: "https://account.queue.core.windows.net" } }),
    /GREENV_AZURE_MEASUREMENT_QUEUE_NAME/,
  );
});

// Azure keeps the attempt counter RabbitMQ makes the publisher carry in a header, so the two
// paths count from different ends: dequeueCount is 1 on the first delivery where the header is 0.
// Both must still spend exactly GREENV_MEASUREMENT_MAX_ATTEMPTS deliveries on a segment.
test("a retryable failure is redelivered three times and then kept, not dropped", () => {
  const retryable = Object.assign(new Error("depth service asleep"), { retryable: true });

  assert.equal(nextAction(retryable, 1), "leave");
  assert.equal(nextAction(retryable, 2), "leave");
  assert.equal(nextAction(retryable, 3), "poison", "the third delivery is the last one");
});

test("a failure a retry cannot fix goes straight to the poison queue", () => {
  // Same rule as rabbit.mjs: a malformed request or a mock depth service fails identically for
  // ever, so it must not be left to starve everything behind it.
  assert.equal(nextAction(new Error("insufficient frames"), 1), "poison");
  assert.equal(nextAction(null, 1), "delete");
});
