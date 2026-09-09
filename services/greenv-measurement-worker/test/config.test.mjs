import test from "node:test";
import assert from "node:assert/strict";
import { loadConfig } from "../src/config.mjs";

test("the defaults describe a local stack with no GPU and no spend", () => {
  const config = loadConfig({});
  assert.equal(config.storage.adapter, "local");
  assert.equal(config.infer.baseUrl, "http://127.0.0.1:5173/api");
  assert.equal(config.measurement.allowMock, false, "a mock packet is never accepted silently");
  assert.equal(config.infer.maxFrames, 112, "matches the extractor's cap and stays under the L4 ceiling");
});

// terrain alone reads 0.000 m on a plant taped at 0.980 m, because Cityscapes puts vertically
// growing vegetation in a different class. Roçada is about the vertical kind.
test("the class policy is explicit and overridable", () => {
  assert.equal(loadConfig({}).measurement.classes, "terrain,vegetation");
  assert.equal(loadConfig({ GREENV_MEASUREMENT_CLASSES: "terrain" }).measurement.classes, "terrain");
});

test("a misconfigured deployment fails at startup rather than at the first message", () => {
  assert.throws(() => loadConfig({ GREENV_OBJECT_STORAGE_ADAPTER: "gcs" }), /must be "local" or "s3"/);
  assert.throws(() => loadConfig({ GREENV_OBJECT_STORAGE_ADAPTER: "s3" }), /GREENV_S3_BUCKET is required/);
  assert.throws(() => loadConfig({ GREENV_INFER_PROCESS_RES: "big" }), /must be a number/);
  assert.throws(() => loadConfig({ GREENV_MEASUREMENT_ALLOW_MOCK: "yes" }), /must be "true" or "false"/);
});
