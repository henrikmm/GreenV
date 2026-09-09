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
      baseUrl: text("GREENV_INFER_BASE_URL", "http://127.0.0.1:5173/api"),
      token: text("GREENV_INFER_TOKEN", null),
      // Verge Studio grades 112 frames at 504 px as its best setting, and an L4 runs out above
      // 144 (measurement/docs/REGISTRY.md). The frame extractor already caps its sampling at
      // 112, so this is a second fence rather than the first.
      processRes: number("GREENV_INFER_PROCESS_RES", 504),
      maxFrames: number("GREENV_INFER_MAX_FRAMES", 112),
      fps: number("GREENV_INFER_FPS", 10),
      timeoutMs: number("GREENV_INFER_TIMEOUT_MS", 15 * 60 * 1000),
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
  return config;
}
