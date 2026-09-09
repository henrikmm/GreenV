#!/usr/bin/env node
// Create (or bring into line) the RunPod template this handler runs from.
//
// Terraform owns the endpoint, through `decentralized-infrastructure/runpod`. It does not own the
// template, because that provider has a data source for templates and no resource — and the
// template is where the image, the container disk and the bucket credentials live. So the split is
// not a preference: this script creates the template and writes its id into
// `infrastructure/runpod.auto.tfvars`, which Terraform loads by itself. Run this, then apply.
//
// Nothing about the endpoint is decided here — not the GPU, not the worker counts, not the
// timeouts. Those live in `infrastructure/runpod.tf` and its variables, in one place, because two
// records of the same number are two records that will disagree.
//
//   RUNPOD_API_KEY=... GREENV_AWS_ACCESS_KEY=... GREENV_AWS_SECRET_KEY=... node provision.mjs
//
// It is idempotent by name: it reads what exists, creates what does not, and patches what drifted
// rather than silently keeping it. Creating a template costs nothing, and neither does the
// endpoint Terraform builds from it — a GPU is billed for a worker's lifetime, and `workers_min`
// is 0. The first *job* is what bills, `AGENTS.md` asks for agreement before each one, and this
// script never sends one.

const API = process.env.RUNPOD_API_BASE ?? "https://rest.runpod.io/v1";

export const settings = (env) => {
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
    // Terraform finds the template by this id, not by this name, so the name is only what a human
    // reads in the RunPod console - and what makes a second run recognise its own work.
    name: env.RUNPOD_TEMPLATE_NAME?.trim() || "greenv-depth",
    image: env.GREENV_DEPTH_IMAGE?.trim() || "ghcr.io/matomomitsu/greenv-depth-runpod:pipeline",
    // The bucket credentials the handler reads through the ordinary AWS chain. Nothing else about
    // the bucket belongs here: the worker names bucket, endpoint and region in every job, so one
    // template serves any bucket the caller can address.
    accessKey: required("GREENV_AWS_ACCESS_KEY"),
    secretKey: required("GREENV_AWS_SECRET_KEY"),
    // The image is 15.3 GB unpacked; 50 GB is RunPod's own default and leaves room for the ~110 MB
    // a run materialises. Not checked against a real worker.
    containerDiskInGb: number("RUNPOD_CONTAINER_DISK_GB", 50),
    // Overrides the handler's ceiling table. Needed on any GPU other than an L4 or larger, and the
    // operator owns the number: it is a measurement, not a preference.
    maxFrames: env.GREENV_DEPTH_MAX_FRAMES?.trim() || null,
    tfvarsPath: env.GREENV_TFVARS_PATH?.trim() || "../../infrastructure/runpod.auto.tfvars",
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

/** What an existing template would have to change to match, or null when it already does. */
export function drift(existing, desired) {
  const differences = {};
  for (const [key, value] of Object.entries(desired)) {
    const current = existing[key];
    const same = Array.isArray(value)
      ? Array.isArray(current) && value.length === current.length && value.every((v, i) => v === current[i])
      : key === "env"
        ? // RunPod adds variables of its own to a running worker; only the ones we set are ours to
          // compare, and a missing one of those is drift.
          Object.entries(value).every(([k, v]) => (current ?? {})[k] === v)
        : current === value;
    if (!same) differences[key] = { from: current, to: value };
  }
  return Object.keys(differences).length ? differences : null;
}

export async function provision(config, { fetchImpl = fetch, log = console.log, write = writeTfvars } = {}) {
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
      // The key travels in the header and never in the message: this output gets pasted.
      throw new Error(`${init.method ?? "GET"} ${path} failed with ${response.status}: ${text.slice(0, 400)}`);
    }
    return text ? JSON.parse(text) : null;
  };

  const wanted = templateBody(config);

  if (config.dryRun) {
    log(JSON.stringify({ dryRun: true, template: redact(wanted) }, null, 2));
    return { dryRun: true };
  }

  const existing = await call("/templates");
  const list = Array.isArray(existing) ? existing : (existing?.data ?? []);
  let template = list.find((item) => item.name === config.name);

  if (!template) {
    template = await call("/templates", { method: "POST", body: JSON.stringify(wanted) });
    log(`template created: ${template.id}`);
  } else {
    const changes = drift(template, wanted);
    if (changes) {
      template = await call(`/templates/${template.id}`, { method: "PATCH", body: JSON.stringify(wanted) });
      log(`template updated: ${template.id} (${Object.keys(changes).join(", ")})`);
    } else {
      log(`template unchanged: ${template.id}`);
    }
  }

  await write(config.tfvarsPath, template.id);
  return { templateId: template.id, tfvarsPath: config.tfvarsPath };
}

/**
 * Hand the id to Terraform through a file it loads by itself.
 *
 * `*.auto.tfvars` is read on every plan and apply with no flag, and it is gitignored like every
 * other tfvars here. The alternative was printing a line for a person to paste, which is a step
 * that gets skipped exactly once and then debugged for an hour.
 */
async function writeTfvars(path, templateId) {
  const { writeFile, mkdir } = await import("node:fs/promises");
  const { dirname, resolve } = await import("node:path");
  await mkdir(dirname(resolve(path)), { recursive: true });
  await writeFile(
    path,
    [
      "# Written by services/greenv-depth-runpod/provision.mjs. Terraform loads *.auto.tfvars on",
      "# its own, so this needs no flag and nothing pasted. Re-run that script to refresh it.",
      `depth_template_id = "${templateId}"`,
      "",
    ].join("\n"),
  );
}

/** Secrets never reach the log, not even in a dry run someone pastes into a chat. */
function redact(body) {
  return { ...body, env: Object.fromEntries(Object.keys(body.env ?? {}).map((key) => [key, "<set>"])) };
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  provision(settings(process.env))
    .then((result) => {
      if (result.dryRun) return;
      console.log("");
      console.log(`wrote ${result.tfvarsPath}`);
      console.log("");
      console.log("Terraform builds the endpoint from that template. What is still yours:");
      console.log("");
      console.log('  depth_service_adapter = "runpod"   # infrastructure/terraform.tfvars');
      console.log("  measurement_enabled   = true");
      console.log("  export TF_VAR_runpod_api_key=...        # never a file");
      console.log("  export TF_VAR_depth_service_token=...   # the same RunPod key, for the worker");
      console.log("");
      console.log("No job has been sent. The first one is what bills.");
    })
    .catch((error) => {
      console.error(error.message);
      process.exit(1);
    });
}
