// Every setting this worker reads, in one place, with the reason for each default.
//
// Names follow the GREENV_ prefix the Java services already use, so one `.env` configures the
// whole stack. Where a setting also exists in `greenv-video-api` or `greenv-frame-extractor`,
// the name and the default are copied deliberately: two services that disagree about which
// bucket or which queue they are using fail silently, and that failure looks like an empty
// dashboard rather than an error.

import { homedir } from "node:os";
import { resolve, join } from "node:path";

const text = (name, fallback) => {
  const value = process.env[name];
  return value === undefined || value.trim() === "" ? fallback : value.trim();
};

const number = (name, fallback) => {
  const raw = text(name, null);
  if (raw === null) return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new Error(`${name} must be a number, got ${JSON.stringify(raw)}`);
  return value;
};

const flag = (name, fallback) => {
  const raw = text(name, null);
  if (raw === null) return fallback;
  if (!["true", "false"].includes(raw)) throw new Error(`${name} must be "true" or "false", got ${JSON.stringify(raw)}`);
  return raw === "true";
};

export function loadConfig(env = process.env) {
  const previous = process.env;
  process.env = env;
  try {
    return build();
  } finally {
    process.env = previous;
  }
}

function build() {
  const config = {
    // `local` reads and writes a filesystem tree, which is what `docker compose` runs and what
    // the tests exercise. `s3` addresses any S3-compatible endpoint, R2 included.
    storage: {
      adapter: text("GREENV_OBJECT_STORAGE_ADAPTER", "local"),
      root: resolve(text("GREENV_PIPELINE_ROOT", join(homedir(), ".greenv", "pipeline"))),
      bucket: text("GREENV_S3_BUCKET", null),
      endpoint: text("GREENV_S3_ENDPOINT", null),
      region: text("GREENV_S3_REGION", "auto"),
    },

    queue: {
      // Absent RabbitMQ settings are not an error: the HTTP trigger alone is a legitimate
      // deployment, and it is the one the tests and a backfill use.
      enabled: flag("GREENV_MEASUREMENT_QUEUE_ENABLED", true),
      // Which transport carries a segment here and a result back. The name is the Java services'
      // own, and infrastructure/locals.tf already sets it to azure-queue for every container app
      // in the deployment, so this worker reads the switch the rest of the stack is already
      // reading rather than adding a fourth thing that can disagree.
      adapter: text("GREENV_SEGMENT_QUEUE_ADAPTER", "rabbitmq"),
      host: text("GREENV_RABBITMQ_HOST", "127.0.0.1"),
      port: number("GREENV_RABBITMQ_PORT", 5672),
      user: text("GREENV_RABBITMQ_USER", "guest"),
      password: text("GREENV_RABBITMQ_PASSWORD", "guest"),
      exchange: text("GREENV_SEGMENT_EXCHANGE", "greenv.capture"),
      queue: text("GREENV_MEASUREMENT_QUEUE", "greenv.segment.measure.v1"),
      routingKey: text("GREENV_MEASUREMENT_ROUTING_KEY", "segment.measure.v1"),
      resultRoutingKey: text("GREENV_MEASUREMENT_RESULT_ROUTING_KEY", "segment.measured.v1"),
      // Declaring topology from a worker is convenient locally and wrong in a deployment, where
      // the queues are infrastructure. Same switch, same default, as GREENV_RABBITMQ_DYNAMIC.
      dynamic: flag("GREENV_RABBITMQ_DYNAMIC", false),
      // One segment occupies the depth service for its whole run and the CPU for the whole
      // assessment. Prefetching more only makes messages time out in a buffer.
      prefetch: 1,

      // Azure Queue Storage has no exchange, so the one exchange and two routing keys above
      // become two queue names. They are separate settings rather than reused ones because an
      // Azure queue name is 3-63 lower-case alphanumerics and dashes: `greenv.segment.measure.v1`
      // is a legal RabbitMQ queue and an illegal Azure one.
      azure: {
        queue: text("GREENV_AZURE_MEASUREMENT_QUEUE_NAME", null),
        resultQueue: text("GREENV_AZURE_MEASURED_QUEUE_NAME", null),
        // Where a segment goes when it will never succeed. Optional: without it such a message is
        // deleted, which is what the RabbitMQ path does with one anyway.
        poisonQueue: text("GREENV_AZURE_MEASUREMENT_POISON_QUEUE_NAME", null),
        endpoint: text("GREENV_AZURE_QUEUE_ENDPOINT", null),
        connectionString: text("GREENV_AZURE_STORAGE_CONNECTION_STRING", null),
        // The same bound as `prefetch`, for the same reason.
        maximumMessages: 1,
        // How long a received segment stays invisible to other readers. This is the deadline for
        // the whole measurement, not for an acknowledgement, so it tracks
        // GREENV_MEASUREMENT_TIMEOUT_MS rather than the seconds the Java services use for a poll
        // that only queues work. Too short and Azure redelivers a segment still being measured.
        visibilityTimeoutSeconds: number("GREENV_MEASUREMENT_VISIBILITY_SECONDS", 30 * 60),
        pollDelayMs: number("GREENV_CLOUD_QUEUE_POLL_DELAY_MS", 1000),
      },
    },

    infer: {
      // The DA3 service. The base carries whatever path prefix the deployment needs: the Vite
      // fixture serves the contract under /api, the deployed FastAPI serves it at the origin.
      //
      // The default is the fixture, but it will not answer this worker. Its privileged routes -
      // which every POST is - require an `Origin` header naming loopback on 5173 AND a nonce that
      // the dev server only injects into the HTML it serves, so it answers browsers and refuses
      // processes. Observed 8 Sep 2026 from the compose stack:
      //   POST http://<host>:5173/api/infer failed with 403:
      //   {"detail":"local API requires a loopback origin on port 5173"}
      // Running end to end therefore needs a real depth service, which costs money and needs the
      // user's agreement each time (AGENTS.md), or a stand-in that does not exist yet.
      // `http` is the FastAPI dialect above. `runpod` addresses a serverless endpoint, which is a
      // job queue rather than one request, and sends frames by object key instead of by upload -
      // see src/infer-runpod.mjs. Default unchanged, so nothing moves for anyone already running.
      adapter: text("GREENV_INFER_ADAPTER", "http"),
      baseUrl: text("GREENV_INFER_BASE_URL", "http://127.0.0.1:5173/api"),
      token: text("GREENV_INFER_TOKEN", null),
      // Verge Studio grades 112 frames at 504 px as its best setting, and an L4 runs out above
      // 144 (measurement/docs/REGISTRY.md). The frame extractor already caps its sampling at
      // 112, so this is a second fence rather than the first.
      processRes: number("GREENV_INFER_PROCESS_RES", 504),
      maxFrames: number("GREENV_INFER_MAX_FRAMES", 112),
      fps: number("GREENV_INFER_FPS", 10),
      timeoutMs: number("GREENV_INFER_TIMEOUT_MS", 15 * 60 * 1000),
      runpod: {
        // https://api.runpod.ai/v2/<endpoint-id> - the whole prefix, so a self-hosted proxy or a
        // future API version needs no code change.
        endpoint: text("GREENV_RUNPOD_ENDPOINT", null),
        apiKey: text("GREENV_RUNPOD_API_KEY", null),
        // A cold endpoint spends about a minute starting before it computes anything, so polling
        // faster than this only buys requests.
        pollIntervalMs: number("GREENV_RUNPOD_POLL_MS", 5000),
      },
    },

    measurement: {
      // Where `assess-grass.mjs` lives. The worker spawns it as a process and reads its JSON;
      // it never imports anything from inside `measurement/`, which is what keeps that subtree
      // round-trippable (AGENTS.md).
      cliPath: resolve(text(
        "GREENV_MEASUREMENT_CLI",
        new URL("../../../measurement/scripts/assess-grass.mjs", import.meta.url).pathname,
      )),
      runsRoot: resolve(text("VERGE_RUNS_ROOT", join(homedir(), "verge-runs"))),
      // Which Cityscapes labels count as the area of interest. `terrain` alone is Verge Studio's
      // default and it reads 0.000 m on a plant taped at 0.980 m, because Cityscapes puts
      // vertically growing vegetation in `vegetation` and only horizontally spreading growth in
      // `terrain` (measurement/docs/evidence/2026-09-05-class-fit.md). Roçada is about the
      // vertical kind, so this worker names the union and records it on every frame. No policy
      // here is validated; this one is the one whose failure mode is visible rather than silent.
      classes: text("GREENV_MEASUREMENT_CLASSES", "terrain,vegetation"),
      offsetM: number("GREENV_MEASUREMENT_OFFSET_M", 2),
      timeoutMs: number("GREENV_MEASUREMENT_TIMEOUT_MS", 30 * 60 * 1000),
      // A packet built on the fixture-backed mock describes the fixture's scene, not the
      // uploaded video. It is worth producing — it exercises every seam — and it must never be
      // mistaken for a reading, which is what happened on 2026-08-05. Compose sets this true;
      // anything else has to say so out loud.
      allowMock: flag("GREENV_MEASUREMENT_ALLOW_MOCK", false),
    },

    http: {
      port: number("PORT", 8090),
      address: text("SERVER_ADDRESS", "127.0.0.1"),
    },
  };

  if (!["local", "s3"].includes(config.storage.adapter)) {
    throw new Error(`GREENV_OBJECT_STORAGE_ADAPTER must be "local" or "s3", got "${config.storage.adapter}"`);
  }
  if (config.storage.adapter === "s3" && !config.storage.bucket) {
    throw new Error("GREENV_S3_BUCKET is required when the object-storage adapter is s3");
  }
  if (!["rabbitmq", "azure-queue"].includes(config.queue.adapter)) {
    throw new Error(`GREENV_SEGMENT_QUEUE_ADAPTER must be "rabbitmq" or "azure-queue", got "${config.queue.adapter}"`);
  }
  if (config.queue.enabled && config.queue.adapter === "azure-queue") {
    // Checked at startup rather than at the first message: a worker that cannot name its queues
    // is a worker that will look healthy and drain nothing.
    if (!config.queue.azure.queue || !config.queue.azure.resultQueue) {
      throw new Error(
        "GREENV_AZURE_MEASUREMENT_QUEUE_NAME and GREENV_AZURE_MEASURED_QUEUE_NAME are required when the segment-queue adapter is azure-queue",
      );
    }
    if (!config.queue.azure.connectionString && !config.queue.azure.endpoint) {
      throw new Error(
        "GREENV_AZURE_QUEUE_ENDPOINT or GREENV_AZURE_STORAGE_CONNECTION_STRING is required when the segment-queue adapter is azure-queue",
      );
    }
  }
  if (!["http", "runpod"].includes(config.infer.adapter)) {
    throw new Error(`GREENV_INFER_ADAPTER must be "http" or "runpod", got "${config.infer.adapter}"`);
  }
  if (config.infer.adapter === "runpod") {
    if (!config.infer.runpod.endpoint || !config.infer.runpod.apiKey) {
      throw new Error("GREENV_RUNPOD_ENDPOINT and GREENV_RUNPOD_API_KEY are required when the infer adapter is runpod");
    }
    if (config.storage.adapter !== "s3") {
      // The handler reads the frames itself, from a bucket. A worker whose frames are on its own
      // filesystem has nothing to hand over, and would spend a GPU start to find that out.
      throw new Error("the runpod infer adapter needs GREENV_OBJECT_STORAGE_ADAPTER=s3: the depth handler reads frames from the bucket");
    }
  }
  return config;
}
