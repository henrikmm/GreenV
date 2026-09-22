/**
 * Importing a reconstruction this app did not compute.
 *
 * Graded against the real roadside pair in `fixtures/roadside/` rather than a synthetic zip,
 * because the thing being tested is whether a FILE SOMEBODY ELSE WROTE can be read — and a
 * fixture built by the same code that parses it would answer a different question. Those two
 * payloads are committed on purpose (root AGENTS.md exempts them), so this runs on a fresh clone
 * and in CI.
 *
 * The npz is 608 KB holding (4, 224, 392) float32 depth; its shapes are the numbers asserted
 * below and they come from the file, not from this file.
 */

import { mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { readNpzMeta } from "./npz-meta.mjs";
import {
  assertGlb,
  describeImportedRun,
  describeNpz,
  importedManifest,
  importedRunId,
  sha256,
} from "./imported-run.mjs";

const FIXTURE = new URL("../../fixtures/roadside/", import.meta.url);
const GLB_PATH = fileURLToPath(new URL("scene.glb", FIXTURE));
const NPZ_PATH = fileURLToPath(new URL("result.npz", FIXTURE));

const NOW = new Date("2026-09-18T11:22:33.000Z");

let glb;
let npz;
let root;
let runs;

beforeAll(async () => {
  [glb, npz] = await Promise.all([readFile(GLB_PATH), readFile(NPZ_PATH)]);
  root = await mkdtemp(join(tmpdir(), "verge-import-"));
  // RUNS_ROOT is resolved once, at import. Point it somewhere disposable BEFORE loading.
  process.env.VERGE_RUNS_ROOT = root;
  runs = await import("./runs.mjs");
});

afterAll(async () => {
  await rm(root, { recursive: true, force: true });
});

/** A jpeg only by name — nothing in the import path decodes a frame, it only relays the bytes. */
const frame = (name) => ({ name, bytes: Buffer.from(`image-${name}`) });

describe("reading an npz without inflating it", () => {
  it("describes every array from the headers alone", () => {
    const arrays = readNpzMeta(npz);
    expect(Object.keys(arrays).sort()).toEqual(["confidence", "depth", "extrinsics", "intrinsics"]);
    expect(arrays.depth).toEqual({ dtype: "<f4", fortranOrder: false, shape: [4, 224, 392] });
    expect(arrays.intrinsics.shape).toEqual([4, 3, 3]);
    expect(arrays.extrinsics.shape).toEqual([4, 3, 4]);
  });

  it("refuses anything that is not a zip, rather than returning nothing", () => {
    expect(() => readNpzMeta(Buffer.from("not an archive, just some text"))).toThrow(/not a zip/);
  });
});

describe("judging an uploaded npz", () => {
  it("reads the frame count and depth-map size off the file", () => {
    const { frameCount, width, height } = describeNpz(npz, "result.npz");
    expect({ frameCount, width, height }).toEqual({ frameCount: 4, width: 392, height: 224 });
  });

  it("names the arrays a bundle is missing, and what it did carry", () => {
    // DA3's own native export: real arrays, wrong ones. This is the mistake worth a clear
    // message, because both files are called .npz and only one can drive a measurement.
    const native = zipOf({ disp: [4, 224, 392], mask: [4, 224, 392] });
    expect(() => describeNpz(native, "result.npz")).toThrow(/no depth, intrinsics, extrinsics/);
    expect(() => describeNpz(native, "result.npz")).toThrow(/carries disp, mask/);
  });

  it("refuses a bundle whose arrays disagree about how many frames there are", () => {
    // The silent failure this project has already paid for: one frame's depth measured with
    // another frame's camera. It raises no error downstream, it just measures the wrong thing.
    const mismatched = zipOf({ depth: [4, 224, 392], intrinsics: [3, 3, 3], extrinsics: [4, 3, 4] });
    expect(() => describeNpz(mismatched, "result.npz")).toThrow(
      /4 depth frames but 3 intrinsics/,
    );
  });

  it("refuses a single-frame bundle, because one view reconstructs nothing", () => {
    const one = zipOf({ depth: [1, 224, 392], intrinsics: [1, 3, 3], extrinsics: [1, 3, 4] });
    expect(() => describeNpz(one, "result.npz")).toThrow(/at least 2 are required/);
  });
});

describe("judging an uploaded glb", () => {
  it("accepts the real export", () => {
    expect(() => assertGlb(glb, "scene.glb")).not.toThrow();
  });

  it("catches a truncated download, which a magic check alone would wave through", () => {
    expect(() => assertGlb(glb.subarray(0, glb.length - 2048), "scene.glb")).toThrow(/truncated/);
  });

  it("rejects a file that is not a glb at all", () => {
    expect(() => assertGlb(Buffer.alloc(64), "scene.glb")).toThrow(/glTF magic/);
  });
});

describe("the manifest an import synthesises", () => {
  const built = () =>
    importedManifest({
      runId: "imported-20260918-112233-abcdef",
      glb,
      npz,
      npzName: "result.npz",
      shape: describeNpz(npz, "result.npz"),
      fps: 4,
      now: NOW,
    });

  it("derives the frame geometry from the npz, not from the importer", () => {
    const manifest = built();
    expect(manifest.frames).toMatchObject({ count: 4, width: 392, height: 224, capped: false });
    // process_res is the long edge of the depth map: 392 here, 504 on the door run's (112,504,280).
    expect(manifest.params.process_res).toBe(392);
    expect(manifest.params.max_frames).toBe(4);
  });

  it("records what was never measured as zero, not as a plausible number", () => {
    const manifest = built();
    expect(manifest.timing).toEqual({ gpu_seconds: 0, wall_seconds: 0, model_load_seconds: null });
    expect(manifest.vram.peak_bytes).toBe(0);
    expect(manifest.vram.device_name).toBe("");
    // The file does not say which model produced it, so neither does the manifest.
    expect(manifest.model_repository_id).toBe("");
    expect(manifest.model_revision).toBe("");
  });

  it("carries the real digests and sizes of the bytes that arrived", () => {
    const byKind = Object.fromEntries(built().artifacts.map((a) => [a.kind, a]));
    expect(byKind.glb).toMatchObject({ name: "scene.glb", size_bytes: glb.length, sha256: sha256(glb) });
    expect(byKind.npz).toMatchObject({ name: "result.npz", size_bytes: npz.length, sha256: sha256(npz) });
  });

  it("records the uploaded file's own arrays as its provenance", () => {
    expect(built().diagnostics.native_npz).toEqual({
      "result.npz": ["confidence", "depth", "extrinsics", "intrinsics"],
    });
  });

  it("is neither transient nor expiring, because the bytes are on this disk", () => {
    expect(built()).toMatchObject({ transient: false, expires_after_days: 0, mock: false });
  });
});

describe("the id an import is given", () => {
  it("reads like the registry's other ids and ends in the npz's own digest", () => {
    const id = importedRunId(sha256(npz), NOW);
    expect(id).toBe(`imported-20260918-112233-${sha256(npz).slice(0, 6)}`);
  });

  it("steps aside for an id already in use, rather than handing out a duplicate", () => {
    // The timestamp is second-resolution, so the file and the clock alone do not make a unique
    // id — importing one pair twice inside a second produces the same string twice. Collapsing
    // the second import onto the first would destroy whatever had been measured against it.
    const base = importedRunId(sha256(npz), NOW);
    expect(importedRunId(sha256(npz), NOW, (id) => id === base)).toBe(`${base}-2`);
    expect(importedRunId(sha256(npz), NOW, (id) => id === base || id === `${base}-2`)).toBe(`${base}-3`);
  });
});

describe("writing an imported run to disk", () => {
  const importPair = (extra = {}) =>
    runs.importRun({ glb, npz, glbName: "scene.glb", npzName: "result.npz", ...extra });

  it("lays out the three files the app reads back, and lists the run", async () => {
    const record = await importPair({ frames: [1, 2, 3, 4].map((n) => frame(`f-${n}.jpg`)) });

    const directory = join(root, record.id);
    expect(existsSync(join(directory, "scene.glb"))).toBe(true);
    expect(existsSync(join(directory, "result.npz"))).toBe(true);
    // Written in the WIRE shape, because `loadRunDepthField` reads it back through
    // `manifestFromWire` exactly like a saved cloud run's.
    const onDisk = JSON.parse(await readFile(join(directory, "manifest.json"), "utf8"));
    expect(onDisk.frames.count).toBe(4);
    expect(onDisk.run_id).toBe(record.id);

    const listed = (await runs.listRuns()).find((item) => item.id === record.id);
    expect(listed).toMatchObject({ source: "import", persisted: true, builtin: false, frameCount: 4 });
    // The index keeps the app's shape, not the wire's — that is what every other record holds.
    expect(listed.manifest.frames.count).toBe(4);
    expect(listed.manifest.params.processRes).toBe(392);
  });

  it("renumbers frames 1..N in npz order, whatever they were called", async () => {
    const record = await importPair({
      frames: ["frame-0207.jpg", "frame-0013.jpg", "frame-0101.jpg", "frame-0004.jpg"].map(frame),
    });
    const written = (await readdir(join(root, record.id, "frames"))).sort();
    expect(written).toEqual(["frame-0001.jpg", "frame-0002.jpg", "frame-0003.jpg", "frame-0004.jpg"]);
    // Lexical order is capture order, so the lowest original name becomes frame 1.
    expect(await readFile(join(root, record.id, "frames", "frame-0001.jpg"), "utf8")).toBe(
      "image-frame-0004.jpg",
    );
  });

  it("imports a bare pair, and says the frames are absent rather than pretending", async () => {
    const record = await importPair();
    expect(record.framesAvailable).toBe(false);
    expect(await readdir(join(root, record.id, "frames"))).toEqual([]);
    // Still openable: artifactBase and framesBase resolve, so the GLB cloud and the floor fit work.
    expect(record.artifactBase).toContain(record.id);
    expect(record.framesBase).toContain("frames");
  });

  it("refuses a partial frame set, which would pair depth with the wrong picture", async () => {
    await expect(importPair({ frames: [frame("a.jpg"), frame("b.jpg")] })).rejects.toThrow(
      /describes 4 frames but 2 images were uploaded/,
    );
  });

  it("gives each import its own measurement-target key", async () => {
    const record = await importPair();
    // Empty would file every import ever made under one key, so two unrelated scenes would
    // offer each other's targets — and metric scale does not transfer between reconstructions.
    expect(record.clipSha256).toBe(`imported:${sha256(npz)}`);
  });

  it("keeps a re-import of the same pair as its own run", async () => {
    // Re-importing is a real thing to do — attaching the frames that were missing the first
    // time — so it must not overwrite the first run's directory, and must not be refused.
    const first = await importPair();
    const second = await importPair({ frames: [1, 2, 3, 4].map((n) => frame(`f-${n}.jpg`)) });
    expect(second.id).not.toBe(first.id);
    expect(existsSync(join(root, first.id, "scene.glb"))).toBe(true);
    expect(first.framesAvailable).toBe(false);
    expect(second.framesAvailable).toBe(true);
  });

  it("refuses a glb that is not one before writing anything", async () => {
    const before = await readdir(root);
    await expect(
      runs.importRun({ glb: Buffer.from("nope"), npz, glbName: "scene.glb", npzName: "result.npz" }),
    ).rejects.toThrow(/too short to be a GLB/);
    expect(await readdir(root)).toEqual(before);
  });
});

describe("the manifest shapes stay inverses of each other", () => {
  it("survives a round trip through the wire shape and back", () => {
    // `toWireManifest` has already shipped two bugs from somebody emitting only the fields their
    // immediate caller needed, and the loss surfaced later, permanently, because that object is
    // copied into the run directory as the run's archive record. `manifestToApp` is its third
    // sibling; this is the assertion that keeps the pair honest.
    const wire = importedManifest({
      runId: "imported-20260918-112233-abcdef",
      glb,
      npz,
      npzName: "result.npz",
      shape: describeNpz(npz, "result.npz"),
      fps: 4,
      now: NOW,
    });
    const app = runs.manifestToApp(wire);
    const back = runs.toWireManifest(app);

    // `imported_at` is import's own annotation and has no home in `InferManifest`, so it does
    // not come back. Everything the app contract defines must.
    const { imported_at: _dropped, ...contract } = wire;
    expect(back).toEqual(contract);
  });
});

describe("describeImportedRun, end to end on the real pair", () => {
  it("validates both files and returns the run they would become", () => {
    const description = describeImportedRun({
      glb,
      npz,
      glbName: "scene.glb",
      npzName: "result.npz",
      fps: 4,
      now: NOW,
    });
    expect(description.runId).toMatch(/^imported-20260918-112233-[0-9a-f]{6}$/);
    expect(description.shape.frameCount).toBe(4);
    expect(description.manifest.frames.effective_fps).toBe(4);
  });
});

/**
 * A zip of npy arrays, built here so a malformed bundle can be described without one existing
 * on disk. Stored (method 0) rather than deflated: the reader accepts both, and an uncompressed
 * member keeps this helper to arithmetic no test has to trust.
 */
function zipOf(shapes) {
  const members = Object.entries(shapes).map(([name, shape]) => {
    const fileName = `${name}.npy`;
    const descriptor = `{'descr': '<f4', 'fortran_order': False, 'shape': (${shape.join(", ")},), }`;
    const padding = 64 - ((10 + descriptor.length + 1) % 64);
    const header = `${descriptor}${" ".repeat(padding)}\n`;
    const prefix = Buffer.alloc(10);
    prefix.write("\x93NUMPY", 0, "latin1");
    prefix[6] = 1;
    prefix[7] = 0;
    prefix.writeUInt16LE(header.length, 8);
    // Header only: nothing in this module reads an array's payload, so the floats are omitted.
    return { fileName, body: Buffer.concat([prefix, Buffer.from(header, "latin1")]) };
  });

  const locals = [];
  const central = [];
  let offset = 0;
  for (const { fileName, body } of members) {
    const name = Buffer.from(fileName, "latin1");
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(body.length, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, body);

    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt32LE(body.length, 20);
    entry.writeUInt32LE(body.length, 24);
    entry.writeUInt16LE(name.length, 28);
    entry.writeUInt32LE(offset, 42);
    central.push(entry, name);
    offset += 30 + name.length + body.length;
  }

  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(members.length, 8);
  end.writeUInt16LE(members.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}
