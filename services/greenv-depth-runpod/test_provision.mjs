// The provisioner, against a double that answers the way RunPod's REST API documents.
//
// Nothing is created here and no key is needed. The point is that a second run changes nothing,
// that a changed setting is noticed rather than silently kept, that the template id reaches
// Terraform through a file rather than through a person, and that a secret never reaches the
// output someone will paste into a chat.

import assert from "node:assert/strict";
import test from "node:test";

import { drift, provision, settings, templateBody } from "./provision.mjs";

const ENV = {
  RUNPOD_API_KEY: "runpod-test-key",
  GREENV_AWS_ACCESS_KEY: "r2-access",
  GREENV_AWS_SECRET_KEY: "r2-secret",
};

/** A RunPod double holding whatever has been created so far. */
function runpod({ templates = [] } = {}) {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const path = String(url).replace("https://rest.runpod.io/v1", "");
    const method = init.method ?? "GET";
    calls.push({ path, method, body: init.body ? JSON.parse(init.body) : null });
    const ok = (body) => ({ ok: true, status: 200, text: async () => JSON.stringify(body) });

    if (path === "/templates" && method === "GET") return ok(templates);
    if (path === "/templates" && method === "POST") {
      const created = { id: "tpl-1", ...JSON.parse(init.body) };
      templates.push(created);
      return ok(created);
    }
    if (method === "PATCH") {
      const id = path.split("/").pop();
      const index = templates.findIndex((item) => item.id === id);
      templates[index] = { ...templates[index], ...JSON.parse(init.body) };
      return ok(templates[index]);
    }
    return { ok: false, status: 404, text: async () => "not found" };
  };
  return { fetchImpl, calls, templates };
}

const silent = () => {};
/** Terraform's side of the handover, captured instead of written. */
const capture = () => {
  const written = {};
  return { written, write: async (path, id) => { written[path] = id; } };
};

test("creates the template on a fresh account and hands its id to Terraform", async () => {
  const api = runpod();
  const tfvars = capture();
  const result = await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent, write: tfvars.write });

  assert.equal(result.templateId, "tpl-1");
  const template = api.templates[0];
  assert.equal(template.isServerless, true, "a pod template would never receive a job");
  assert.equal(template.env.AWS_ACCESS_KEY_ID, "r2-access");
  assert.equal(template.containerDiskInGb, 50);
  assert.deepEqual(
    tfvars.written,
    { "../../infrastructure/runpod.auto.tfvars": "tpl-1" },
    "Terraform reads the id from a file it loads itself, not from a line someone pastes",
  );
});

test("a second run changes nothing", async () => {
  const api = runpod();
  const tfvars = capture();
  await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent, write: tfvars.write });
  const afterFirst = api.calls.length;

  const again = await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent, write: tfvars.write });

  assert.equal(again.templateId, "tpl-1");
  const writes = api.calls.slice(afterFirst).filter((call) => call.method !== "GET");
  assert.deepEqual(writes, [], "nothing was written the second time");
});

test("a changed setting is applied rather than left drifting", async () => {
  const api = runpod();
  const tfvars = capture();
  await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent, write: tfvars.write });

  await provision(settings({ ...ENV, GREENV_DEPTH_IMAGE: "ghcr.io/example/depth:next" }), {
    fetchImpl: api.fetchImpl,
    log: silent,
    write: tfvars.write,
  });

  assert.equal(api.templates[0].imageName, "ghcr.io/example/depth:next");
  assert.equal(api.calls.filter((call) => call.method === "PATCH").length, 1);
});

test("a dry run sends nothing and prints no secret", async () => {
  const api = runpod();
  let printed = "";

  const tfvars = capture();
  const result = await provision(settings({ ...ENV, RUNPOD_DRY_RUN: "true" }), {
    fetchImpl: api.fetchImpl,
    write: tfvars.write,
    log: (line) => {
      printed += line;
    },
  });

  assert.deepEqual(result, { dryRun: true });
  assert.deepEqual(api.calls, []);
  assert.deepEqual(tfvars.written, {}, "a dry run does not rewrite Terraform's input either");
  assert.match(printed, /"AWS_ACCESS_KEY_ID": "<set>"/);
  assert.doesNotMatch(printed, /r2-access|r2-secret|runpod-test-key/);
});

test("a missing credential is named before anything is sent", () => {
  assert.throws(() => settings({}), /RUNPOD_API_KEY is required/);
  assert.throws(() => settings({ RUNPOD_API_KEY: "k" }), /GREENV_AWS_ACCESS_KEY is required/);
  assert.throws(
    () => settings({ ...ENV, RUNPOD_CONTAINER_DISK_GB: "big" }),
    /RUNPOD_CONTAINER_DISK_GB must be a number/,
  );
});

test("drift compares an env map by the keys we set, not by equality", () => {
  const desired = templateBody(settings(ENV));
  // RunPod returns more than it was given; extra keys are its business, not drift.
  const asStored = { ...desired, id: "tpl-1", earned: 0, env: { ...desired.env, RUNPOD_POD_ID: "x" } };
  assert.equal(drift(asStored, desired), null);
  assert.ok(drift({ ...asStored, containerDiskInGb: 20 }, desired));
});

test("the template says nothing about the endpoint", () => {
  // The GPU, the worker counts and the timeouts live in infrastructure/runpod.tf. Two records of
  // one number are two records that will disagree.
  const body = templateBody(settings(ENV));
  for (const key of ["gpuTypeIds", "workersMin", "workersMax", "idleTimeout", "executionTimeoutMs"]) {
    assert.equal(body[key], undefined, `${key} belongs to Terraform, not to this script`);
  }
});
