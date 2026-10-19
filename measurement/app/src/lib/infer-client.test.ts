import { describe, expect, it } from "vitest";
import { isLocalApiBase, isSignedStorageUrl } from "./infer-client";

describe("local inference API detection", () => {
  it("protects both the mock root and nested local proxy routes", () => {
    expect(isLocalApiBase("/api")).toBe(true);
    expect(isLocalApiBase("/api/cloud/svc")).toBe(true);
  });

  it("does not attach the local nonce to remote services", () => {
    expect(isLocalApiBase("https://example.run.app")).toBe(false);
    expect(isLocalApiBase("/application")).toBe(false);
  });
});

/**
 * A signed link is fetched whole and without the service token. The demo site's artifacts are
 * R2 presigned links (S3 signature v4); missing them would send a Bearer header the bucket
 * rejects, and chunk a download that has no cap to chunk around.
 */
describe("signed storage link detection", () => {
  it("recognises signed GCS links, as before", () => {
    expect(isSignedStorageUrl("https://storage.googleapis.com/b/o.npz?X-Goog-Algorithm=a&X-Goog-Signature=f")).toBe(true);
  });

  it("recognises S3-style presigned links on any https host, such as R2", () => {
    expect(
      isSignedStorageUrl(
        "https://acct.r2.cloudflarestorage.com/bucket/demo/c1/depth/r/result.npz?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=ab12",
      ),
    ).toBe(true);
  });

  it("does not mistake an unsigned or service-relative path for one", () => {
    expect(isSignedStorageUrl("https://acct.r2.cloudflarestorage.com/bucket/result.npz")).toBe(false);
    expect(isSignedStorageUrl("/artifact/r/result.npz?X-Amz-Signature=ab")).toBe(false);
    expect(isSignedStorageUrl("http://insecure.example/result.npz?X-Amz-Signature=ab")).toBe(false);
  });
});
