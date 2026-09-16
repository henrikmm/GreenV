#!/usr/bin/env node
// Wiring, and nothing else.
//
// Every decision this file makes is a choice of adapter; the behaviour lives in `pipeline.mjs`
// so it can be tested without a queue, a bucket or a GPU.

import { loadConfig } from "./config.mjs";
import { objectStorage } from "./storage/index.mjs";
import { inferClient } from "./infer.mjs";
import { runpodInferClient } from "./infer-runpod.mjs";
import { measurementRunner } from "./measure.mjs";
import { measurementPipeline } from "./pipeline.mjs";
import { createHttpTrigger } from "./http.mjs";
import { connectQueue } from "./queue/index.mjs";
import { boundedFlight } from "./gate.mjs";

const log = (fields) => process.stdout.write(`${JSON.stringify({ at: new Date().toISOString(), ...fields })}\n`);

export async function start(config = loadConfig()) {
  const storage = objectStorage(config.storage);
  const infer = config.infer.adapter === "runpod"
    ? runpodInferClient({
        ...config.infer,
        ...config.infer.runpod,
        endpoint: `${config.infer.runpod.apiBase}/${config.infer.runpod.endpointId}`,
        apiKey: config.infer.token,
        storage: config.storage,
      })
    : inferClient(config.infer);
  const runner = measurementRunner(config.measurement, (progress) => log({ event: "progress", ...progress }));
  // Shared by both trigger paths, so the bound holds no matter which one is used. How many at
  // once is the same number that decides how many messages a replica takes off the queue: they
  // are two halves of one decision about what this container can carry.
  const gate = boundedFlight(config.measurement.windowConcurrency);

  let queue = null;
  if (config.queue.enabled) {
    queue = await connectQueue(config.queue, { log });
  }
  // The pipeline can put work back on the queue, which is how a segment becomes its windows.
  // Without a queue — the HTTP door, and the tests — it measures them in place instead.
  const measure = measurementPipeline({
    config,
    storage,
    infer,
    runner,
    work: queue ? { publish: (request) => queue.publishWork(request) } : null,
    log,
  });

  if (queue) {
    await queue.consume(async (request) => {
      const result = await gate.run(() => measure(request));
      // A segment that fanned out measured nothing and has nothing to announce: the windows it
      // queued will each announce their own reading when they are measured.
      if (result.fannedOut) {
        log({ event: "queued-windows", segment: result.outputPrefix, windows: result.fannedOut });
        return;
      }
      // One announcement per window: each is a stretch of its own, with its own reading, and the
      // control plane stores them one by one. A segment measured whole announces once, as always.
      for (const window of result.windows ?? [result]) {
        await queue.publishResult(window);
      }
      log({
        event: "measured",
        segment: result.outputPrefix,
        window: result.windowIndex ?? null,
        runId: result.runId,
        mock: result.mock,
      });
    });
  }

  const http = createHttpTrigger({ measure, gate, log });
  await http.listen(config.http.port, config.http.address);
  log({
    event: "started",
    http: `${config.http.address}:${config.http.port}`,
    storage: config.storage.adapter,
    transport: config.queue.adapter,
    queue: config.queue.enabled
      ? (config.queue.adapter === "azure-queue" ? config.queue.azure.queue : config.queue.queue)
      : "disabled",
    depth: config.infer.target,
    classes: config.measurement.classes,
  });

  const stop = async () => {
    log({ event: "stopping" });
    await http.close();
    await queue?.close();
  };
  for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, () => void stop().then(() => process.exit(0)));
  return { stop, measure, http };
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  start().catch((error) => {
    log({ event: "failed-to-start", error: error.message });
    process.exit(1);
  });
}
