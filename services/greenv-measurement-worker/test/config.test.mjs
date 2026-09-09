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

// The names in infrastructure/locals.tf, which are the names greenv-video-api and
// greenv-frame-extractor read at greenv.storage.s3.* against the same bucket. The worker used to
// read GREENV_S3_REGION, which nothing sets, and never read the key pair or the path-style flag
// at all — so a deployment configured correctly for the other two services configured nothing
// here.
test("the s3 settings come from the names the deployment sets", () => {
  const config = loadConfig({
    GREENV_OBJECT_STORAGE_ADAPTER: "s3",
    GREENV_S3_BUCKET: "greenv-captures",
    GREENV_S3_ENDPOINT: "https://acc.r2.cloudflarestorage.com",
    GREENV_S3_PATH_STYLE_ACCESS: "false",
    GREENV_AWS_REGION: "auto",
    GREENV_AWS_ACCESS_KEY: "r2-access-key",
    GREENV_AWS_SECRET_KEY: "r2-secret-key",
  }).storage;

  assert.equal(config.bucket, "greenv-captures");
  assert.equal(config.endpoint, "https://acc.r2.cloudflarestorage.com");
  assert.equal(config.region, "auto");
  assert.equal(config.pathStyleAccess, false);
  assert.equal(config.accessKey, "r2-access-key");
  assert.equal(config.secretKey, "r2-secret-key");
});

// Absent means "use the AWS default chain", which is a legitimate deployment. Half a pair means
// somebody mistyped a secret name, and the chain would hide it until the first frame.
test("a half-configured key pair is refused, an absent one is not", () => {
  const s3 = { GREENV_OBJECT_STORAGE_ADAPTER: "s3", GREENV_S3_BUCKET: "greenv-captures" };

  assert.equal(loadConfig(s3).storage.accessKey, null);
  assert.equal(loadConfig(s3).storage.secretKey, null);
  assert.equal(loadConfig(s3).storage.pathStyleAccess, false, "matches the Java services' default");
  assert.equal(loadConfig(s3).storage.region, "auto", "R2's region, not the services' us-east-1");

  assert.throws(() => loadConfig({ ...s3, GREENV_AWS_ACCESS_KEY: "r2-access-key" }), /must be set together/);
  assert.throws(() => loadConfig({ ...s3, GREENV_AWS_SECRET_KEY: "r2-secret-key" }), /must be set together/);
});
