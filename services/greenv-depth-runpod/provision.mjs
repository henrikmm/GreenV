#!/usr/bin/env node
// Create (or bring into line) the RunPod template and serverless endpoint this handler runs on.
//
// Terraform manages everything else in this stack; it does not manage this. There is no RunPod
// provider in the Terraform registry, and the two resources here are a template and an endpoint
// created once against a REST API, so a `null_resource` wrapping this script would buy a worse
// lifecycle than the script alone: no plan worth reading, and a destroy that deletes a paid
// endpoint on a refresh nobody meant to run.
//
// Instead this is idempotent by name. Run it as often as you like: it reads what exists, creates
// what does not, and reports the difference on what does rather than silently rewriting it.
//
//   RUNPOD_API_KEY=... GREENV_AWS_ACCESS_KEY=... GREENV_AWS_SECRET_KEY=... node provision.mjs
//
// Creating an endpoint costs nothing: with `workersMin` at 0 there is no worker until a request
// arrives. The first *job* is what bills, and `AGENTS.md` asks for the user's agreement before
// that, every time. This script never sends a job.

const API = process.env.RUNPOD_API_BASE ?? "https://rest.runpod.io/v1";

const settings = (env) => {
  const required = (name) => {
    const value = (env[name] ?? "").trim();
    if (!value) throw new Error(`${name} is required`);
    return value;
  };
  const number = (name, fallback) => {
    const raw = (env[name] ?? "").trim();
    if (!raw) return fallback;
    const value = Number(raw);
    if (!Number.isFinite(value)) throw new Error(`${name} must be a number, got ${JSON.stringify(raw)}`);
    return value;
  };
  return {
    apiKey: required("RUNPOD_API_KEY"),
    name: env.RUNPOD_ENDPOINT_NAME?.trim() || "greenv-depth",
    image: env.GREENV_DEPTH_IMAGE?.trim() || "ghcr.io/matomomitsu/greenv-depth-runpod:pipeline",
    // The bucket credentials the handler reads through the ordinary AWS chain. Nothing else about
    // the bucket belongs here: the worker names it in every job, so one endpoint serves any bucket
    // the caller can address.
    accessKey: required("GREENV_AWS_ACCESS_KEY"),
    secretKey: required("GREENV_AWS_SECRET_KEY"),
    // Every memory ceiling on record was measured on an L4's 22.03 GiB. A smaller card is refused
    // by the handler at startup rather than killed mid-run, so naming the card here is what keeps
    // that refusal from ever being needed.
    gpuTypeIds: (env.RUNPOD_GPU_TYPE_IDS?.trim() || "NVIDIA L4").split(",").map((id) => id.trim()),
    // The image is 15.3 GB unpacked. 50 GB is RunPod's own default and leaves room for the ~110 MB
    // of artifacts a run materialises; this has not been checked against a real worker.
    containerDiskInGb: number("RUNPOD_CONTAINER_DISK_GB", 50),
    // One segment is one inference. A second worker is a second cold start, not more throughput,
    // and the depth service holds a lock of its own anyway.
    workersMax: number("RUNPOD_WORKERS_MAX", 1),
    // The dial the whole bill hangs on. Segments arriving back to back from one drive ride a
    // single warm worker; a lone segment pays the entire tail. 60 s is short on purpose - raise it
    // deliberately once a real drive shows how they arrive.
    idleTimeout: number("RUNPOD_IDLE_TIMEOUT_SECONDS", 60),
    // A 112-frame run took 41 to 117 s of wall clock behind a ~64 s cold start, so 900 s is room
    // for the worst recorded case and its start, and no more.
    executionTimeoutMs: number("RUNPOD_EXECUTION_TIMEOUT_MS", 900_000),
    maxFrames: env.GREENV_DEPTH_MAX_FRAMES?.trim() || null,
    dryRun: env.RUNPOD_DRY_RUN === "true",
  };
};

export function templateBody(config) {
  return {
    name: config.name,
    imageName: config.image,
    isServerless: true,
    containerDiskInGb: config.containerDiskInGb,
    // A serverless worker has no volume to keep and nothing to serve over a port: the handler
    // talks to RunPod's queue, and to the DA3 service over loopback inside the container.
    volumeInGb: 0,
    ports: [],
    env: {
      AWS_ACCESS_KEY_ID: config.accessKey,
      AWS_SECRET_ACCESS_KEY: config.secretKey,
      ...(config.maxFrames ? { GREENV_DEPTH_MAX_FRAMES: config.maxFrames } : {}),
    },
  };
}

export function endpointBody(config, templateId) {
  return {
    name: config.name,
    templateId,
    computeType: "GPU",
    gpuTypeIds: config.gpuTypeIds,
    gpuCount: 1,
    // Zero, so an idle endpoint costs nothing. This is the difference between a pilot and a bill.
    workersMin: 0,
    workersMax: config.workersMax,
    idleTimeout: config.idleTimeout,
    executionTimeoutMs: config.executionTimeoutMs,
    flashboot: true,
  };
}

/** What an existing resource would have to change to match, or null when it already does. */
export function drift(existing, desired) {
  const differences = {};
  for (const [key, value] of Object.entries(desired)) {
    const current = existing[key];
    const same = Array.isArray(value)
      ? Array.isArray(current) && value.length === current.length && value.every((v, i) => v === current[i])
      : key === "env"
        ? Object.entries(value).every(([k, v]) => (current ?? {})[k] === v)
        : current === value;
    if (!same) differences[key] = { from: current, to: value };
  }
  return Object.keys(differences).length ? differences : null;
}

export async function provision(config, { fetchImpl = fetch, log = console.log } = {}) {
  const call = async (path, init = {}) => {
    const response = await fetchImpl(`${API}${path}`, {
      ...init,
      headers: {
        authorization: `Bearer ${config.apiKey}`,
        "content-type": "application/json",
        ...(init.headers ?? {}),
      },
    });
    const text = await response.text();
    if (!response.ok) {
      // The key is in the header, never in the message: this output is meant to be pasted.
      throw new Error(`${init.method ?? "GET"} ${path} failed with ${response.status}: ${text.slice(0, 400)}`);
    }
    return text ? JSON.parse(text) : null;
  };

  const byName = (list, name) => (Array.isArray(list) ? list : (list?.data ?? [])).find((item) => item.name === name);

  const wantedTemplate = templateBody(config);
  const wantedEndpoint = (templateId) => endpointBody(config, templateId);

  if (config.dryRun) {
    log(JSON.stringify({ dryRun: true, template: redact(wantedTemplate), endpoint: wantedEndpoint("<template-id>") }, null, 2));
    return { dryRun: true };
  }

  let template = byName(await call("/templates"), config.name);
  if (!template) {
    template = await call("/templates", { method: "POST", body: JSON.stringify(wantedTemplate) });
    log(`template created: ${template.id}`);
  } else {
    const changes = drift(template, wantedTemplate);
    if (changes) {
      template = await call(`/templates/${template.id}`, { method: "PATCH", body: JSON.stringify(wantedTemplate) });
      log(`template updated: ${template.id} (${Object.keys(changes).join(", ")})`);
    } else {
      log(`template unchanged: ${template.id}`);
    }
  }

  let endpoint = byName(await call("/endpoints"), config.name);
  if (!endpoint) {
    endpoint = await call("/endpoints", { method: "POST", body: JSON.stringify(wantedEndpoint(template.id)) });
    log(`endpoint created: ${endpoint.id}`);
  } else {
    const changes = drift(endpoint, wantedEndpoint(template.id));
    if (changes) {
      endpoint = await call(`/endpoints/${endpoint.id}`, { method: "PATCH", body: JSON.stringify(wantedEndpoint(template.id)) });
      log(`endpoint updated: ${endpoint.id} (${Object.keys(changes).join(", ")})`);
    } else {
      log(`endpoint unchanged: ${endpoint.id}`);
    }
  }

  return { templateId: template.id, endpointId: endpoint.id };
}

/** Secrets never reach the log, not even in a dry run someone pastes into a chat. */
function redact(body) {
  return { ...body, env: Object.fromEntries(Object.keys(body.env ?? {}).map((key) => [key, "<set>"])) };
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  const config = settings(process.env);
  provision(config)
    .then((result) => {
      if (result.dryRun) return;
      console.log("");
      console.log("Point the measurement worker at it - infrastructure/terraform.tfvars:");
      console.log("");
      console.log(`depth_service_adapter     = "runpod"`);
      console.log(`depth_service_endpoint_id = "${result.endpointId}"`);
      console.log(`# depth_service_token comes from TF_VAR_depth_service_token, never a file`);
      console.log(`measurement_enabled       = true`);
      console.log("");
      console.log("No job has been sent. The first one is what bills.");
    })
    .catch((error) => {
      console.error(error.message);
      process.exit(1);
    });
}

export { settings };
