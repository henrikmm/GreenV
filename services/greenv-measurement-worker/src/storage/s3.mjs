// Object storage on any S3-compatible endpoint, R2 included.
//
// The SDK is imported lazily so a `local` deployment — which is every test and the whole compose
// stack — never pays for loading it, and a missing optional dependency fails with an instruction
// rather than at import time.

import { createHash } from "node:crypto";

const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");

async function client({ endpoint, region }) {
  let sdk;
  try {
    sdk = await import("@aws-sdk/client-s3");
  } catch {
    throw new Error(
      "the s3 object-storage adapter needs @aws-sdk/client-s3 — run `npm install` in " +
        "services/greenv-measurement-worker, or set GREENV_OBJECT_STORAGE_ADAPTER=local",
    );
  }
  return { sdk, s3: new sdk.S3Client({ region, ...(endpoint ? { endpoint, forcePathStyle: true } : {}) }) };
}

export function s3Storage({ bucket, endpoint, region }) {
  let ready = null;
  const connect = () => (ready ??= client({ endpoint, region }));

  const body = async (key) => {
    const { sdk, s3 } = await connect();
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
      const { sdk, s3 } = await connect();
      await s3.send(new sdk.PutObjectCommand({ Bucket: bucket, Key: key, Body: bytes }));
      return { objectKey: key, sha256: digest(bytes), bytes: bytes.length };
    },

    async exists(key) {
      const { sdk, s3 } = await connect();
      try {
        await s3.send(new sdk.HeadObjectCommand({ Bucket: bucket, Key: key }));
        return true;
      } catch {
        return false;
      }
    },
  };
}
