// Object storage on any S3-compatible endpoint, R2 included.
//
// Keys are the same provider-neutral strings the filesystem adapter takes and go through the same
// refusal (./object-key.mjs) before anything is sent. This is the adapter a deployment runs, so it
// is the last place that should have got the looser rule.

import { createHash } from "node:crypto";
import { assertObjectKey } from "./object-key.mjs";

const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");

// The SDK stays a lazy import now that it is a declared dependency, for a reason the dependency
// does not remove: storage/index.mjs imports this module unconditionally, so a static import
// would load the AWS SDK into every `local` process — the whole compose stack, every test, every
// HTTP-only deployment — none of which ever construct a client. Measured in node:22-bookworm-slim
// on 9 Sep 2026 after `npm ci --omit=dev`: `import("@aws-sdk/client-s3")` costs 95-118 ms across
// three runs, against 10-17 ms to load this whole storage module and open the local adapter, and
// @aws-sdk plus @smithy are 11.1 MB of the worker's 12.1 MB of production node_modules
// (`du -sb`). A deployment that does address a bucket pays that once, on its first object.
export async function openClient({ endpoint, region, pathStyleAccess, accessKey, secretKey }) {
  let sdk;
  try {
    sdk = await import("@aws-sdk/client-s3");
  } catch (cause) {
    // @aws-sdk/client-s3 is in `dependencies`, so reaching here means a broken install rather
    // than a forgotten optional extra: `npm ci` did not run, or ran against a stale lockfile.
    throw new Error(
      "the s3 object-storage adapter could not load @aws-sdk/client-s3 — run `npm ci` in " +
        "services/greenv-measurement-worker, or set GREENV_OBJECT_STORAGE_ADAPTER=local",
      { cause },
    );
  }
  return {
    sdk,
    s3: new sdk.S3Client({
      region,
      forcePathStyle: Boolean(pathStyleAccess),
      ...(endpoint ? { endpoint } : {}),
      // Two credential paths, both real. R2 has no instance metadata and no role to assume, so
      // the deployment injects a scoped key pair (GREENV_AWS_ACCESS_KEY / GREENV_AWS_SECRET_KEY,
      // the names both Java services already read) and it has to be handed to the client
      // explicitly. With the pair absent the key is omitted rather than passed empty, so the
      // SDK's own chain still resolves — which is what a developer with ~/.aws/credentials and a
      // real AWS deployment with a task role both rely on. An empty string is not "unset" to the
      // SDK: it would sign with a blank key and fail at the endpoint instead of here.
      ...(accessKey && secretKey ? { credentials: { accessKeyId: accessKey, secretAccessKey: secretKey } } : {}),
    }),
  };
}

// `connect` is the seam a test fills. Every request below reaches the network through it and
// through nothing else, so a double is a `{ sdk, s3 }` pair of plain objects.
export function s3Storage({ bucket, endpoint, region, pathStyleAccess, accessKey, secretKey, connect = openClient }) {
  let ready = null;
  const open = () => (ready ??= connect({ endpoint, region, pathStyleAccess, accessKey, secretKey }));

  const body = async (key) => {
    assertObjectKey(key);
    const { sdk, s3 } = await open();
    const response = await s3.send(new sdk.GetObjectCommand({ Bucket: bucket, Key: key }));
    return Buffer.from(await response.Body.transformToByteArray());
  };

  return {
    kind: "s3",
    bucket,

    get: body,

    async getJson(key) {
      return JSON.parse((await body(key)).toString("utf8"));
    },

    async put(key, bytes) {
      assertObjectKey(key);
      const { sdk, s3 } = await open();
      await s3.send(new sdk.PutObjectCommand({ Bucket: bucket, Key: key, Body: bytes }));
      return { objectKey: key, sha256: digest(bytes), bytes: bytes.length };
    },

    /**
     * Writes only if the key is free, and says which happened.
     *
     * <p>`If-None-Match: *` makes the store decide, in one request: exactly one of two callers
     * racing for the same key gets the write and the other gets 412. Reading first and then
     * writing would let both through, and a claim that both callers win is not a claim — it is
     * two GPU runs for one window.
     */
    async putIfAbsent(key, bytes) {
      assertObjectKey(key);
      const { sdk, s3 } = await open();
      try {
        await s3.send(new sdk.PutObjectCommand({
          Bucket: bucket,
          Key: key,
          Body: bytes,
          IfNoneMatch: "*",
        }));
        return true;
      } catch (error) {
        const status = error?.$metadata?.httpStatusCode;
        if (status === 412 || status === 409 || error?.name === "PreconditionFailed") return false;
        throw error;
      }
    },

    async exists(key) {
      try {
        // Inside the try, so a refused key answers `false` here exactly as it does on the
        // filesystem adapter. The two adapters disagreeing about a key is the bug this file
        // just fixed; introducing a fresh disagreement in `exists` would be the same bug.
        assertObjectKey(key);
        const { sdk, s3 } = await open();
        await s3.send(new sdk.HeadObjectCommand({ Bucket: bucket, Key: key }));
        return true;
      } catch {
        return false;
      }
    },
  };
}
