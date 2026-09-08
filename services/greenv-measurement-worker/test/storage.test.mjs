import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { localStorage } from "../src/storage/local.mjs";

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

  for (const key of ["../outside.json", "a/../../outside.json", "/etc/passwd"]) {
    await assert.rejects(storage.get(key), /escapes the storage root/, key);
    await assert.rejects(storage.put(key, Buffer.from("x")), /escapes the storage root/, key);
  }
  assert.rejects(storage.get(""), /non-empty string/);
});
