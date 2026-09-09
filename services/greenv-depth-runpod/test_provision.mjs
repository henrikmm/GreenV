// The provisioner, against a double that answers the way RunPod's REST API documents.
//
// No endpoint is created here and no key is needed: the point is that a second run changes
// nothing, that a changed setting is noticed rather than silently kept, and that a secret never
// reaches the output someone will paste into a chat.

import assert from "node:assert/strict";
import test from "node:test";

import { drift, endpointBody, provision, settings, templateBody } from "./provision.mjs";

const ENV = {
  RUNPOD_API_KEY: "runpod-test-key",
  GREENV_AWS_ACCESS_KEY: "r2-access",
  GREENV_AWS_SECRET_KEY: "r2-secret",
};

/** A RunPod double holding whatever has been created so far. */
function runpod({ templates = [], endpoints = [] } = {}) {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const path = String(url).replace("https://rest.runpod.io/v1", "");
    const method = init.method ?? "GET";
    calls.push({ path, method, body: init.body ? JSON.parse(init.body) : null });
    const ok = (body) => ({ ok: true, status: 200, text: async () => JSON.stringify(body) });

    if (path === "/templates" && method === "GET") return ok(templates);
    if (path === "/endpoints" && method === "GET") return ok(endpoints);
    if (path === "/templates" && method === "POST") {
      const created = { id: "tpl-1", ...JSON.parse(init.body) };
      templates.push(created);
      return ok(created);
    }
    if (path === "/endpoints" && method === "POST") {
      const created = { id: "ep-1", ...JSON.parse(init.body) };
      endpoints.push(created);
      return ok(created);
    }
    if (method === "PATCH") {
      const id = path.split("/").pop();
      const list = path.startsWith("/templates") ? templates : endpoints;
      const index = list.findIndex((item) => item.id === id);
      list[index] = { ...list[index], ...JSON.parse(init.body) };
      return ok(list[index]);
    }
    return { ok: false, status: 404, text: async () => "not found" };
  };
  return { fetchImpl, calls, templates, endpoints };
}

const silent = () => {};

test("creates the template and the endpoint on a fresh account", async () => {
  const api = runpod();
  const result = await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent });

  assert.deepEqual(result, { templateId: "tpl-1", endpointId: "ep-1" });
  const template = api.templates[0];
  assert.equal(template.isServerless, true, "a pod template would never receive a job");
  assert.equal(template.env.AWS_ACCESS_KEY_ID, "r2-access");
  const endpoint = api.endpoints[0];
  assert.equal(endpoint.workersMin, 0, "an idle endpoint must cost nothing");
  assert.equal(endpoint.workersMax, 1);
  assert.deepEqual(endpoint.gpuTypeIds, ["NVIDIA L4"]);
  assert.equal(endpoint.executionTimeoutMs, 900_000);
});

test("a second run changes nothing", async () => {
  const api = runpod();
  await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent });
  const afterFirst = api.calls.length;

  const again = await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent });

  assert.deepEqual(again, { templateId: "tpl-1", endpointId: "ep-1" });
  const writes = api.calls.slice(afterFirst).filter((call) => call.method !== "GET");
  assert.deepEqual(writes, [], "nothing was written the second time");
});

test("a changed setting is applied rather than left drifting", async () => {
  const api = runpod();
  await provision(settings(ENV), { fetchImpl: api.fetchImpl, log: silent });

  await provision(settings({ ...ENV, RUNPOD_IDLE_TIMEOUT_SECONDS: "300" }), {
    fetchImpl: api.fetchImpl,
    log: silent,
  });

  assert.equal(api.endpoints[0].idleTimeout, 300);
  assert.equal(
    api.calls.filter((call) => call.method === "PATCH").length,
    1,
    "only the endpoint changed, so only the endpoint was patched",
  );
});

test("a dry run sends nothing and prints no secret", async () => {
  const api = runpod();
  let printed = "";

  const result = await provision(settings({ ...ENV, RUNPOD_DRY_RUN: "true" }), {
    fetchImpl: api.fetchImpl,
    log: (line) => {
      printed += line;
    },
  });

  assert.deepEqual(result, { dryRun: true });
  assert.deepEqual(api.calls, []);
  assert.match(printed, /"AWS_ACCESS_KEY_ID": "<set>"/);
  assert.doesNotMatch(printed, /r2-access|r2-secret|runpod-test-key/);
});

test("a missing credential is named before anything is sent", () => {
  assert.throws(() => settings({}), /RUNPOD_API_KEY is required/);
  assert.throws(() => settings({ RUNPOD_API_KEY: "k" }), /GREENV_AWS_ACCESS_KEY is required/);
  assert.throws(
    () => settings({ ...ENV, RUNPOD_IDLE_TIMEOUT_SECONDS: "soon" }),
    /RUNPOD_IDLE_TIMEOUT_SECONDS must be a number/,
  );
});

test("drift compares an env map by the keys we set, not by equality", () => {
  const desired = templateBody(settings(ENV));
  // RunPod returns more than it was given; extra keys are its business, not drift.
  const asStored = { ...desired, id: "tpl-1", earned: 0, env: { ...desired.env, RUNPOD_POD_ID: "x" } };
  assert.equal(drift(asStored, desired), null);
  assert.ok(drift({ ...asStored, containerDiskInGb: 20 }, desired));
});

test("the endpoint always names the template it was built from", () => {
  const body = endpointBody(settings(ENV), "tpl-42");
  assert.equal(body.templateId, "tpl-42");
  assert.equal(body.computeType, "GPU");
});
