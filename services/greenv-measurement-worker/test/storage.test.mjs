import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { localStorage } from "../src/storage/local.mjs";
import { openClient, s3Storage } from "../src/storage/s3.mjs";

// The keys a crafted queue message would carry. Both adapters answer the same way to all three.
const ESCAPES = ["../outside.json", "a/../../outside.json", "/etc/passwd"];

test("objects round-trip through the filesystem adapter", async () => {
  const storage = localStorage({ root: await mkdtemp(join(tmpdir(), "store-")) });
  const put = await storage.put("a/b/thing.json", Buffer.from('{"ok":true}'));

  assert.equal(put.bytes, 11);
  assert.match(put.sha256, /^[0-9a-f]{64}$/);
  assert.deepEqual(await storage.getJson("a/b/thing.json"), { ok: true });
  assert.equal(await storage.exists("a/b/thing.json"), true);
  assert.equal(await storage.exists("a/b/missing.json"), false);
});

// A key arrives inside a queue message, so it is input, not configuration. Without this a
// crafted key would read or overwrite anything the worker's user can reach.
test("a key cannot escape the storage root", async () => {
  const root = await mkdtemp(join(tmpdir(), "store-"));
  const storage = localStorage({ root });

  for (const key of ESCAPES) {
    await assert.rejects(storage.get(key), /escapes the storage root/, key);
    await assert.rejects(storage.put(key, Buffer.from("x")), /escapes the storage root/, key);
  }
  assert.rejects(storage.get(""), /non-empty string/);
});

// A hand-built bucket. The adapter reaches the network through its `connect` seam and through
// nothing else, so these three command shapes and a Map are its whole surface — and `sent`
// records every request, which is how a refusal is shown to have happened before the wire.
function fakeBucket(objects = {}) {
  const store = new Map(Object.entries(objects));
  const sent = [];
  const opened = [];
  const command = (name) =>
    class {
      constructor(input) {
        this.name = name;
        this.input = input;
      }
    };
  const sdk = {
    GetObjectCommand: command("GetObject"),
    PutObjectCommand: command("PutObject"),
    HeadObjectCommand: command("HeadObject"),
  };
  const s3 = {
    async send(request) {
      sent.push(request);
      const { Bucket, Key, Body } = request.input;
      assert.equal(Bucket, "captures");
      if (request.name === "PutObject") {
        store.set(Key, Buffer.from(Body));
        return {};
      }
      if (!store.has(Key)) {
        const error = new Error(`no such key: ${Key}`);
        error.name = "NoSuchKey";
        throw error;
      }
      if (request.name === "HeadObject") return { ContentLength: store.get(Key).length };
      return { Body: { transformToByteArray: async () => new Uint8Array(store.get(Key)) } };
    },
  };
  return {
    store,
    sent,
    opened,
    connect: async (settings) => {
      opened.push(settings);
      return { sdk, s3 };
    },
  };
}

// What `loadConfig` hands the adapter for the deployed shape: R2, virtual-hosted, a scoped key
// pair injected as a secret.
const R2 = {
  bucket: "captures",
  endpoint: "https://acc.r2.cloudflarestorage.com",
  region: "auto",
  pathStyleAccess: false,
  accessKey: "r2-access-key",
  secretKey: "r2-secret-key",
};

const bucketStorage = (bucket) => s3Storage({ ...R2, connect: bucket.connect });

test("objects round-trip through the s3 adapter", async () => {
  const bucket = fakeBucket();
  const storage = bucketStorage(bucket);
  const put = await storage.put("a/b/thing.json", Buffer.from('{"ok":true}'));

  assert.equal(put.objectKey, "a/b/thing.json");
  assert.equal(put.bytes, 11);
  assert.match(put.sha256, /^[0-9a-f]{64}$/);
  assert.deepEqual(await storage.getJson("a/b/thing.json"), { ok: true });
  assert.deepEqual(await storage.get("a/b/thing.json"), Buffer.from('{"ok":true}'));
  assert.equal(await storage.exists("a/b/thing.json"), true);
  assert.equal(await storage.exists("a/b/missing.json"), false);

  // The client is opened once and reused, and every setting the config carried reaches it. A
  // deployment that opened one per object would pay the SDK's handshake on every frame; one that
  // dropped a setting on the way would sign against the wrong host or with the wrong key.
  const { bucket: _, ...settings } = R2;
  assert.deepEqual(bucket.opened, [settings]);
});

// The same test the filesystem adapter gets, for the same reason: a key is data from a queue
// message. Until this ran, `../` was refused under compose and forwarded to R2 in the cloud.
test("a key cannot escape the storage root on s3 either", async () => {
  const bucket = fakeBucket();
  const storage = bucketStorage(bucket);

  for (const key of ESCAPES) {
    await assert.rejects(storage.get(key), /escapes the storage root/, key);
    await assert.rejects(storage.getJson(key), /escapes the storage root/, key);
    await assert.rejects(storage.put(key, Buffer.from("x")), /escapes the storage root/, key);
    assert.equal(await storage.exists(key), false, key);
  }
  await assert.rejects(storage.get(""), /non-empty string/);

  // Refused before the wire, not after: no request was built and the client was never opened.
  assert.deepEqual(bucket.sent, []);
  assert.deepEqual(bucket.opened, []);
});

// The defect the doubles above cannot see. package.json declared one dependency, amqplib, so a
// container built from the Dockerfile had no SDK at all and every segment failed on its first
// frame. Loading the real module is the only assertion that catches that coming back.
test("the s3 adapter's SDK is a declared dependency, not an assumption", async () => {
  const { sdk, s3 } = await openClient(R2);
  try {
    assert.ok(s3 instanceof sdk.S3Client);
    for (const command of ["GetObjectCommand", "PutObjectCommand", "HeadObjectCommand"]) {
      assert.equal(typeof sdk[command], "function", command);
    }
  } finally {
    s3.destroy();
  }
});

// The deployment supplies R2 through GREENV_AWS_ACCESS_KEY / GREENV_AWS_SECRET_KEY and says
// GREENV_S3_PATH_STYLE_ACCESS=false, exactly as both Java services read them. This adapter used
// to ignore all three: no credentials at all, so the SDK reached for a chain that has nothing in
// it on Container Apps, and forcePathStyle hardcoded to true whenever an endpoint was set.
// Constructing a client and resolving a static key pair sends nothing, so nothing here is billed
// and no bucket is reached.
test("the deployment's key pair and path-style flag reach the real client", async () => {
  const { s3 } = await openClient(R2);
  try {
    assert.equal(await s3.config.region(), "auto");
    assert.equal(s3.config.forcePathStyle, false);
    assert.equal((await s3.config.endpoint()).hostname, "acc.r2.cloudflarestorage.com");

    const credentials = await s3.config.credentials();
    assert.equal(credentials.accessKeyId, "r2-access-key");
    assert.equal(credentials.secretAccessKey, "r2-secret-key");
  } finally {
    s3.destroy();
  }

  const { s3: pathStyle } = await openClient({ ...R2, pathStyleAccess: true });
  try {
    assert.equal(pathStyle.config.forcePathStyle, true, "the flag is read, not inferred from the endpoint");
  } finally {
    pathStyle.destroy();
  }
});

// The other half of the same decision: an unset pair must leave the SDK's own chain intact, which
// is what a developer with ~/.aws/credentials and a real AWS deployment with a task role both
// use. Passing empty strings would look like "configured" to the SDK and fail at the endpoint
// instead of here. The environment variables below are the first stop in that chain, so this
// resolves offline and reaches nothing.
test("with no key pair the SDK's own credential chain is left intact", async () => {
  const previous = { ...process.env };
  process.env.AWS_ACCESS_KEY_ID = "chain-key";
  process.env.AWS_SECRET_ACCESS_KEY = "chain-secret";
  try {
    const { s3 } = await openClient({ ...R2, accessKey: null, secretKey: null });
    try {
      const credentials = await s3.config.credentials();
      assert.equal(credentials.accessKeyId, "chain-key");
      assert.equal(credentials.secretAccessKey, "chain-secret");
    } finally {
      s3.destroy();
    }
  } finally {
    process.env = previous;
  }
});

test("a conditional write is won by exactly one caller", async () => {
  const storage = localStorage({ root: await mkdtemp(join(tmpdir(), "claim-")) })

  const first = await storage.putIfAbsent("a/b/claim.json", Buffer.from('{"at":"first"}'))
  const second = await storage.putIfAbsent("a/b/claim.json", Buffer.from('{"at":"second"}'))

  assert.equal(first, true)
  assert.equal(second, false, "the second caller is told it lost, not silently overwritten")
  assert.deepEqual(await storage.getJson("a/b/claim.json"), { at: "first" })

  // A plain put is still a plain put: taking a claim over is deliberate and goes through it.
  await storage.put("a/b/claim.json", Buffer.from('{"at":"third"}'))
  assert.deepEqual(await storage.getJson("a/b/claim.json"), { at: "third" })
})
