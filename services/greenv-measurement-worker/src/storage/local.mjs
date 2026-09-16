// Object storage backed by a filesystem tree.
//
// This is what `docker compose` runs and what the tests use. Keys are provider-neutral strings
// with `/` separators, exactly as the Java services write them, so the same key addresses the
// same object whichever adapter is configured.

import { createHash } from "node:crypto";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";
import { assertObjectKey } from "./object-key.mjs";

const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");

export function localStorage({ root }) {
  const base = resolve(root);

  // A key is data from a queue message, so it is never trusted to stay inside the root.
  // `../` in a key would otherwise read or overwrite anything this process can reach.
  const pathFor = (key) => {
    assertObjectKey(key);
    // The shared rule is the contract both adapters keep; resolve() has the last word here
    // because only it knows what this platform counts as a separator — on Windows that includes
    // `\`, which the shared rule reads as an ordinary character in a name.
    const target = resolve(base, key);
    if (target !== base && !target.startsWith(base + sep)) {
      throw new Error(`object key escapes the storage root: ${key}`);
    }
    return target;
  };

  return {
    kind: "local",
    root: base,

    async get(key) {
      return readFile(pathFor(key));
    },

    async getJson(key) {
      return JSON.parse(await readFile(pathFor(key), "utf8"));
    },

    async put(key, bytes) {
      const path = pathFor(key);
      await mkdir(dirname(path), { recursive: true });
      await writeFile(path, bytes);
      return { objectKey: key, sha256: digest(bytes), bytes: bytes.length };
    },

    /**
     * Writes only if nothing is there, and says which happened.
     *
     * <p>`wx` is the whole point: the check and the write are one operation, so two callers
     * racing for the same key cannot both be told they won. A check followed by a write would
     * let both through, which for a claim is worse than useless.
     */
    async putIfAbsent(key, bytes) {
      const path = pathFor(key);
      await mkdir(dirname(path), { recursive: true });
      try {
        await writeFile(path, bytes, { flag: "wx" });
        return true;
      } catch (error) {
        if (error.code === "EEXIST") return false;
        throw error;
      }
    },

    async exists(key) {
      try {
        await readFile(pathFor(key));
        return true;
      } catch {
        return false;
      }
    },
  };
}
