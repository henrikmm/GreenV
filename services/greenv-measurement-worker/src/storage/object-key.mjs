// One rule for object keys, shared by both adapters.
//
// A key arrives inside a queue message, so it is input rather than configuration. The rule used
// to live inside the filesystem adapter alone, which meant a crafted key was refused under
// `docker compose` and handed straight to the bucket in the cloud — the same message doing two
// different things depending on GREENV_OBJECT_STORAGE_ADAPTER. A rule a deployment variable can
// switch off is not a rule, so it lives here and both adapters call it.

// `..` as a whole segment. `..stale.json` and `frame..1.jpg` are ordinary names and stay allowed:
// the frame extractor chooses the file names, and this is not a spelling policy.
const TRAVERSAL = /(^|\/)\.\.(\/|$)/;

export function assertObjectKey(key) {
  if (typeof key !== "string" || key === "") {
    throw new Error("object key must be a non-empty string");
  }
  // A leading `/` escapes a filesystem root outright. On a bucket it addresses an object under an
  // empty first prefix, which no service in this repository writes and `CaptureObjectKeys` cannot
  // produce, so it is a forged key either way.
  if (key.startsWith("/") || TRAVERSAL.test(key)) {
    throw new Error(`object key escapes the storage root: ${key}`);
  }
  return key;
}
