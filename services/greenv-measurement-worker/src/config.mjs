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
      // The three settings below are named and defaulted after greenv-video-api and
      // greenv-frame-extractor, which read them at `greenv.storage.s3.*` in their
      // application.properties, so one `.env` and one Terraform `common_environment` block
      // configure all three services against one bucket.
      //
      // The region used to come from GREENV_S3_REGION, a name nothing in this repository sets;
      // the deployment sets GREENV_AWS_REGION, which the worker never saw. The default stays
      // `auto` rather than the services' `us-east-1` because they can also address real AWS,
      // and R2 — which wants `auto` — is the only endpoint this worker has been pointed at.
      region: text("GREENV_AWS_REGION", "auto"),
      // The deployment sets this false. The adapter used to force path style on whenever an
      // endpoint was set, which contradicted both other services reading this same flag about
      // this same bucket.
      pathStyleAccess: flag("GREENV_S3_PATH_STYLE_ACCESS", false),
      // R2 has no instance metadata and no role to assume, so the deployment injects a scoped
      // key pair as secrets. Left unset, the adapter falls back to the AWS SDK's own credential
      // chain — a developer's ~/.aws/credentials, or a real AWS deployment with a task role.
      accessKey: text("GREENV_AWS_ACCESS_KEY", null),
      secretKey: text("GREENV_AWS_SECRET_KEY", null),
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
      // Which depth service a run actually reached, in one place because it is written down in
      // three: the start line, the "inferring" line and the `depth.service` field of every stored
      // packet. Those all printed baseUrl whatever the adapter, so a RunPod run recorded itself as
      // having come from Verge Studio's local fixture mock - a provenance field that names the
      // wrong service is worse than no field, because it is read as evidence.
      get target() {
        return this.adapter === "runpod" ? `runpod:${this.runpod.endpointId}` : this.baseUrl;
      },
      token: text("GREENV_INFER_TOKEN", null),
      // Verge Studio grades 112 frames at 504 px as its best setting, and an L4 runs out above
      // 144 (measurement/docs/REGISTRY.md). The frame extractor already caps its sampling at
      // 112, so this is a second fence rather than the first.
      processRes: number("GREENV_INFER_PROCESS_RES", 504),
      maxFrames: number("GREENV_INFER_MAX_FRAMES", 112),
      fps: number("GREENV_INFER_FPS", 10),
      timeoutMs: number("GREENV_INFER_TIMEOUT_MS", 15 * 60 * 1000),
      runpod: {
        // The endpoint's id, not a URL: `infrastructure/locals.tf` hands it over under this name,
        // and two services that disagree about a variable name never meet. The API host is
        // separate so a self-hosted proxy or a future API version needs no code change.
        endpointId: text("GREENV_INFER_RUNPOD_ENDPOINT_ID", null),
        apiBase: text("GREENV_INFER_RUNPOD_API_BASE", "https://api.runpod.ai/v2"),
        // A cold endpoint spends about a minute starting before it computes anything, so polling
        // faster than this only buys requests.
        pollIntervalMs: number("GREENV_INFER_RUNPOD_POLL_MS", 5000),
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
      // The three settings a vehicle-mounted capture needs and a walked one does not, all
      // defaulting to Verge Studio's own behaviour (measurement/scripts/grass-anchor.mjs):
      //
      //   - `auto` lets the assessment choose the SIGN of offsetM from where the vegetation mask
      //     lies relative to the camera track. On 2026-09-13 every one of 37 usable segments had
      //     the verge on the side the fixed +2 m never reached, and 12 of them measured nothing.
      //   - a camera track shorter than minTrackM on the road plane means the reconstruction did
      //     not see the car move — a phone still being mounted, a stopped car — and four such
      //     segments were reported that day as 1.1 to 3.9 m of vegetation. 0 disables the gate.
      //   - cameraHeightM is the lens's height above the road for this mount, the one length a
      //     car keeps constant all day. DA3 fixes its scale once per clip and it varied more than
      //     two to one between neighbouring segments; the anchor rescales each run to put the
      //     camera where it physically was. Null leaves the model's scale alone.
      offsetSide: text("GREENV_MEASUREMENT_OFFSET_SIDE", "given"),
      minTrackM: number("GREENV_MEASUREMENT_MIN_TRACK_M", 0),
      cameraHeightM: number("GREENV_MEASUREMENT_CAMERA_HEIGHT_M", null),
      // Where the scale anchor comes from. `telemetry` hands Verge Studio the GPS path length of
      // the sampled frames, which the frame extractor writes into the manifest as each frame's
      // `distanceMeters`; the reconstruction is then stretched or shrunk until its camera track
      // is that long. `none` leaves DA3's per-clip scale alone. A configured camera height wins
      // over either, because a taped length beats an inferred one.
      scaleAnchor: text("GREENV_MEASUREMENT_SCALE_ANCHOR", "none"),
      // Trees. The `vegetation` class is the only one that captures the tall grass and brush a
      // mowing decision is about, and it captures crowns with them; nothing in a mask tells a
      // clump at 1 m from a crown at 6 m, their height does. A back-projected point higher than
      // maxHeightM above the road plane is canopy and never enters a cell; a cell whose extent
      // still exceeds canopyExtentM — a trunk with low branches, a cut face — is reported as
      // `canopy` with its numbers and counted in no aggregate. Unset leaves every height in,
      // which is how every graded fixture was measured. The deployment sets 3 and 2.
      maxHeightM: number("GREENV_MEASUREMENT_MAX_HEIGHT_M", null),
      canopyExtentM: number("GREENV_MEASUREMENT_CANOPY_EXTENT_M", null),
      // A wet road reflects the sky and the depth model reads the reflection as depth scattered
      // below the surface, so the true ground is a thin layer the strict plane fit refuses. With
      // this on, Verge Studio makes one coarser attempt, keeps it only if the camera stands a
      // plausible height above it, and names the relaxation in the packet's blockers. One
      // segment of 2026-09-13 measured nothing without it and 601 cells with it.
      groundFallback: flag("GREENV_MEASUREMENT_GROUND_FALLBACK", false),
      // Where a cell's own ground is taken from. `pooled` is Verge Studio's default and assumes
      // the frames agree about where the ground is; on the driven captures of 2026-09-13 the
      // same cell floated 24-52 cm between frames and a mown verge read half the float as
      // grass. `per-frame` measures each frame against its own ground and takes the median.
      datum: text("GREENV_MEASUREMENT_DATUM", "pooled"),
      // A crown floats: ground, then nothing for half a metre or more, then foliage. A cell
      // whose frames typically show a vertical gap wider than this is canopy whatever its
      // extent, which is how a low branch at two metres stays out of the verge's numbers.
      canopyGapM: number("GREENV_MEASUREMENT_CANOPY_GAP_M", null),
      // How far from the detected road edge the measured band reaches, in metres. The band is
      // placed 0.5 m before the vegetation starts, so 5.5 covers five metres of verge: the
      // mowing corridor Motiva cuts. Unset, the band widens to the vegetation's far edge (up to
      // 10 m) and the slope behind the corridor counts, which it must not.
      bandWidthM: number("GREENV_MEASUREMENT_BAND_WIDTH_M", null),
      // Cityscapes labels whose neighbourhood is not measured. At the model's 128x128 logits one
      // class pixel is 4.5 by 8 photograph pixels, so the grass against a guardrail carries the
      // rail's lower edge with it. Empty excludes nothing; the radius is in logit pixels.
      // The mowing corridor ends where the embankment begins. Walking outward along a column of
      // cells, two consecutive rises of more than this, in metres per half-metre cell, mark the
      // slope's foot; that cell and everything beyond it is reported as `slope` and aggregated
      // nowhere. 0.1 is a 20% grade. Unset never looks.
      slopeRiseM: number("GREENV_MEASUREMENT_SLOPE_RISE_M", null),
      // A wet guardrail or a concrete barrier is grass to the segmentation in some frames and a
      // structure in the rest, and the frames that call it grass measure it. A cell that this
      // many frames saw one of the excluded classes standing in is reported as `structure` and
      // aggregated nowhere, whatever the other frames read there. Unset never looks.
      structureFrames: number("GREENV_MEASUREMENT_STRUCTURE_FRAMES", null),
      // What becomes of a point past either end of the camera track. `fold` piles it onto the
      // nearer end with the overshoot turned into distance (Verge Studio's default, right for a
      // walked polyline that spans its stretch); `drop` leaves it out, which a driven capture
      // needs because the depth reaches on down the road past the last pose.
      pastEnds: text("GREENV_MEASUREMENT_PAST_ENDS", "fold"),
      // A second segmentation asked only what is not grass. The grass model is Cityscapes-trained
      // and Cityscapes never taught it a guardrail, so a wet W-beam or a concrete barrier is
      // `terrain` to it in many frames; ADE20K (`ade20k-b4`) knows `fence`, `railing`, `wall`
      // and `bannister`. Its named classes join the structure map: out of the grass mask with the
      // same margin as EXCLUDE_NEAR, and into the cells STRUCTURE_FRAMES counts. Empty runs one
      // model only; the classes are the second model's own names.
      structureModel: text("GREENV_MEASUREMENT_STRUCTURE_MODEL", ""),
      structureClasses: text("GREENV_MEASUREMENT_STRUCTURE_CLASSES", ""),
      // A pixel is a structure to the second model when the probability it gives those classes,
      // summed, reaches this; a rail is spread over fence, railing, wall and bannister, so no
      // one class need win. Unset leaves Verge Studio's 0.5, a majority of the probability.
      structureFloor: number("GREENV_MEASUREMENT_STRUCTURE_FLOOR", null),
      excludeNear: text("GREENV_MEASUREMENT_EXCLUDE_NEAR", ""),
      excludeNearPx: number("GREENV_MEASUREMENT_EXCLUDE_NEAR_PX", 1),
      // Start a re-measure from the reconstruction the depth handler left beside the frames when
      // it is there, instead of waking a GPU for geometry that has not changed. A request can
      // also ask for it per segment (`reuseDepth: true`), which is what a backfill does.
      reuseDepth: flag("GREENV_MEASUREMENT_REUSE_DEPTH", false),
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
  // Both or neither. Half a pair is a typo in a secret name, and the fallback would swallow it:
  // the worker would start, reach for a credential chain that has nothing in it, and fail on the
  // first frame with an SDK error naming none of the variables the deployment actually set. Both
  // Java services refuse the same way (CloudClientConfiguration#credentials).
  if (Boolean(config.storage.accessKey) !== Boolean(config.storage.secretKey)) {
    throw new Error(
      "GREENV_AWS_ACCESS_KEY and GREENV_AWS_SECRET_KEY must be set together, or both left unset " +
        "to use the AWS default credential chain",
    );
  }
  if (!["given", "auto"].includes(config.measurement.offsetSide)) {
    throw new Error(`GREENV_MEASUREMENT_OFFSET_SIDE must be "given" or "auto", got "${config.measurement.offsetSide}"`);
  }
  if (!["telemetry", "none"].includes(config.measurement.scaleAnchor)) {
    throw new Error(`GREENV_MEASUREMENT_SCALE_ANCHOR must be "telemetry" or "none", got "${config.measurement.scaleAnchor}"`);
  }
  if (!Number.isInteger(config.measurement.excludeNearPx) || config.measurement.excludeNearPx < 0 || config.measurement.excludeNearPx > 8) {
    throw new Error(`GREENV_MEASUREMENT_EXCLUDE_NEAR_PX must be a whole number of logit pixels from 0 to 8, got ${config.measurement.excludeNearPx}`);
  }
  if (!["pooled", "per-frame"].includes(config.measurement.datum)) {
    throw new Error(`GREENV_MEASUREMENT_DATUM must be "pooled" or "per-frame", got "${config.measurement.datum}"`);
  }
  for (const [name, value] of [["GREENV_MEASUREMENT_MAX_HEIGHT_M", config.measurement.maxHeightM], ["GREENV_MEASUREMENT_CANOPY_EXTENT_M", config.measurement.canopyExtentM], ["GREENV_MEASUREMENT_CANOPY_GAP_M", config.measurement.canopyGapM], ["GREENV_MEASUREMENT_BAND_WIDTH_M", config.measurement.bandWidthM], ["GREENV_MEASUREMENT_SLOPE_RISE_M", config.measurement.slopeRiseM]]) {
    if (value !== null && !(value > 0)) {
      throw new Error(`${name} must be a positive number of metres, got ${value}`);
    }
  }
  if (!(config.measurement.minTrackM >= 0)) {
    throw new Error(`GREENV_MEASUREMENT_MIN_TRACK_M must be zero or a positive number of metres, got ${config.measurement.minTrackM}`);
  }
  if (config.measurement.structureFrames !== null && !(Number.isInteger(config.measurement.structureFrames) && config.measurement.structureFrames >= 1)) {
    throw new Error(`GREENV_MEASUREMENT_STRUCTURE_FRAMES must be a whole number of frames, at least 1, got ${config.measurement.structureFrames}`);
  }
  if (!["fold", "drop"].includes(config.measurement.pastEnds)) {
    throw new Error(`GREENV_MEASUREMENT_PAST_ENDS must be "fold" or "drop", got "${config.measurement.pastEnds}"`);
  }
  if (config.measurement.structureModel && !config.measurement.structureClasses) {
    throw new Error("GREENV_MEASUREMENT_STRUCTURE_CLASSES must name the classes GREENV_MEASUREMENT_STRUCTURE_MODEL is asked for");
  }
  if (!config.measurement.structureModel && config.measurement.structureClasses) {
    throw new Error("GREENV_MEASUREMENT_STRUCTURE_CLASSES needs GREENV_MEASUREMENT_STRUCTURE_MODEL to read them from");
  }
  if (config.measurement.structureFloor !== null && !(config.measurement.structureFloor > 0 && config.measurement.structureFloor <= 1)) {
    throw new Error(`GREENV_MEASUREMENT_STRUCTURE_FLOOR must be a probability above 0 and at most 1, got ${config.measurement.structureFloor}`);
  }
  if (config.measurement.cameraHeightM !== null && !(config.measurement.cameraHeightM > 0)) {
    throw new Error(`GREENV_MEASUREMENT_CAMERA_HEIGHT_M must be a positive number of metres, got ${config.measurement.cameraHeightM}`);
  }
  if (!["http", "runpod"].includes(config.infer.adapter)) {
    throw new Error(`GREENV_INFER_ADAPTER must be "http" or "runpod", got "${config.infer.adapter}"`);
  }
  if (config.infer.adapter === "runpod") {
    // The credential is the depth stage's own, under the name the deployment already uses for
    // the FastAPI service's bearer: one endpoint, one token, whichever dialect reaches it.
    if (!config.infer.runpod.endpointId || !config.infer.token) {
      throw new Error("GREENV_INFER_RUNPOD_ENDPOINT_ID and GREENV_INFER_TOKEN are required when the infer adapter is runpod");
    }
    if (config.storage.adapter !== "s3") {
      // The handler reads the frames itself, from a bucket. A worker whose frames are on its own
      // filesystem has nothing to hand over, and would spend a GPU start to find that out.
      throw new Error("the runpod infer adapter needs GREENV_OBJECT_STORAGE_ADAPTER=s3: the depth handler reads frames from the bucket");
    }
  }
  return config;
}
