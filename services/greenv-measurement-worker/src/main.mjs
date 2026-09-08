#!/usr/bin/env node
// Wiring, and nothing else.
//
// Every decision this file makes is a choice of adapter; the behaviour lives in `pipeline.mjs`
// so it can be tested without a queue, a bucket or a GPU.

import { loadConfig } from "./config.mjs";
import { objectStorage } from "./storage/index.mjs";
import { inferClient } from "./infer.mjs";
import { measurementRunner } from "./measure.mjs";
import { measurementPipeline } from "./pipeline.mjs";
import { createHttpTrigger } from "./http.mjs";
import { connectQueue } from "./queue/rabbit.mjs";
import { singleFlight } from "./gate.mjs";

const log = (fields) => process.stdout.write(`${JSON.stringify({ at: new Date().toISOString(), ...fields })}\n`);

export async function start(config = loadConfig()) {
  const storage = objectStorage(config.storage);
  const infer = inferClient(config.infer);
  const runner = measurementRunner(config.measurement, (progress) => log({ event: "progress", ...progress }));
  const measure = measurementPipeline({ config, storage, infer, runner, log });
  // Shared by both trigger paths, so the bound holds no matter which one is used.
  const gate = singleFlight();

  let queue = null;
  if (config.queue.enabled) {
    queue = await connectQueue(config.queue, { log });
    await queue.consume(async (request) => {
      const result = await gate.run(() => measure(request));
      await queue.publishResult(result);
      log({ event: "measured", segment: result.outputPrefix, runId: result.runId, mock: result.mock });
    });
  }

  const http = createHttpTrigger({ measure, gate, log });
  await http.listen(config.http.port, config.http.address);
  log({
    event: "started",
    http: `${config.http.address}:${config.http.port}`,
    storage: config.storage.adapter,
    queue: config.queue.enabled ? config.queue.queue : "disabled",
    depth: config.infer.baseUrl,
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
