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

const bucketStorage = (bucket) =>
  s3Storage({ bucket: "captures", endpoint: "https://acc.r2.cloudflarestorage.com", region: "auto", connect: bucket.connect });

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

  // The client is opened once and reused, with the endpoint and region the config carried. A
  // deployment that opened one per object would pay the SDK's handshake on every frame.
  assert.deepEqual(bucket.opened, [{ endpoint: "https://acc.r2.cloudflarestorage.com", region: "auto" }]);
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
  const { sdk, s3 } = await openClient({ endpoint: "https://acc.r2.cloudflarestorage.com", region: "auto" });
  try {
    assert.ok(s3 instanceof sdk.S3Client);
    for (const command of ["GetObjectCommand", "PutObjectCommand", "HeadObjectCommand"]) {
      assert.equal(typeof sdk[command], "function", command);
    }
    // Constructing a client sends nothing and resolves no credentials, so this test costs
    // nothing and reaches no endpoint.
    assert.equal(await s3.config.region(), "auto");
  } finally {
    s3.destroy();
  }
});
