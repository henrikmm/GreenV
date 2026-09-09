#!/usr/bin/env node
// Create (or bring into line) the RunPod template this handler runs from.
//
// You do not normally run this. `terraform apply` does, through the external data source in
// `infrastructure/runpod.tf`, so the whole deployment stays one command.
//
// It exists because `decentralized-infrastructure/runpod` has `runpod_endpoint` and no template
// resource — confirmed against the provider binary's own schema, not its docs — and the template
// is where the image, the container disk and the bucket credentials live. Terraform owns the
// endpoint; this owns the template it is built from.
//
// Nothing about the endpoint is decided here — not the GPU, not the worker counts, not the
// timeouts. Those live in `infrastructure/runpod.tf` and its variables, in one place, because two
// records of the same number are two records that will disagree.
//
//   node provision.mjs           # by hand, printing what it did
//   node provision.mjs --json    # what Terraform runs: one JSON object on stdout, logs on stderr
//
// It is idempotent by name: it reads what exists, creates what does not, and patches what drifted
// rather than silently keeping it. Creating a template costs nothing, and neither does the
// endpoint Terraform builds from it — a GPU is billed for a worker's lifetime, and `workers_min`
// is 0. The first *job* is what bills, `AGENTS.md` asks for agreement before each one, and this
// script never sends one.

import { pathToFileURL } from "node:url";

const API = process.env.RUNPOD_API_BASE ?? "https://rest.runpod.io/v1";

export const settings = (env, query = {}) => {
  // Terraform's own spellings are accepted as fallbacks, so `terraform apply` needs no second set
  // of exports: TF_VAR_runpod_api_key is already in the shell for the provider, and the R2 pair is
  // already there for the workers.
  const first = (...names) => {
    for (const name of names) {
      const value = (env[name] ?? "").trim();
      if (value) return value;
    }
    return null;
  };
  const required = (...names) => {
    const value = first(...names);
    if (!value) throw new Error(`${names[0]} is required`);
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
    apiKey: required("RUNPOD_API_KEY", "TF_VAR_runpod_api_key"),
    // Terraform finds the template by this id, not by this name, so the name is only what a human
    // reads in the RunPod console - and what makes a second run recognise its own work.
    name: env.RUNPOD_TEMPLATE_NAME?.trim() || "greenv-depth",
    // Terraform passes this on stdin so the image is pinned in `terraform.tfvars` beside the other
    // three, rather than defaulted in two places that will drift. The literal below is only for a
    // hand run, and it names a tag where Terraform names a digest - which is the difference
    // between "whatever is newest" and "the one that was tested".
    image: query.image?.trim() || env.GREENV_DEPTH_IMAGE?.trim() || "ghcr.io/matomomitsu/greenv-depth-runpod:pipeline",
    // The bucket credentials the handler reads through the ordinary AWS chain. Nothing else about
    // the bucket belongs here: the worker names bucket, endpoint and region in every job, so one
    // template serves any bucket the caller can address.
    accessKey: required("GREENV_AWS_ACCESS_KEY", "TF_VAR_r2_access_key_id"),
    secretKey: required("GREENV_AWS_SECRET_KEY", "TF_VAR_r2_secret_access_key"),
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

async function readJsonStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString("utf8").trim();
  return text ? JSON.parse(text) : {};
}

// `pathToFileURL`, not a string compare against `pathname`: on Windows argv[1] is `C:\path\file`
// and the URL's pathname is `/C:/path/file`, so the usual POSIX idiom never matches and this file
// exits 0 having done nothing. Terraform then reports "unexpected end of JSON input", which names
// the symptom and not this line. Observed 9 Sep 2026 running the apply on Windows.
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  await (async () => {
  // Terraform's external data source reads stdout as one JSON object of strings and treats
  // anything else there as a failure, so in this mode every human-readable line goes to stderr.
  const asJson = process.argv.includes("--json");
  // The external data source writes its query to stdin as one JSON object. A hand run has no
  // stdin to read, so it is not waited for.
  const query = asJson ? await readJsonStdin() : {};
  // `settings` throws for a missing credential, and it throws synchronously - outside the promise
  // chain below, where it would reach the terminal as a stack trace instead of the one line it is.
  Promise.resolve()
    .then(() =>
      provision(
        settings(process.env, query),
        asJson ? { log: (line) => console.error(line), write: async () => {} } : {},
      ),
    )
    .then((result) => {
      if (asJson) {
        process.stdout.write(JSON.stringify({ template_id: result.templateId }));
        return;
      }
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
  })();
}
