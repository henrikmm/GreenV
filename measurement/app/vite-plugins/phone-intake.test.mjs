/**
 * The phone intake is the only listener this studio opens beyond loopback, so what it refuses
 * matters as much as what it accepts: a wrong code, a route outside the contract, a body that
 * lies about its length.
 */

import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  META_SCHEMA,
  createIntakeHandler,
  isCaptureId,
  listCaptures,
  newCaptureId,
  pruneInbox,
  recordPhase,
  studioPhoneRoute,
  validateMeta,
} from "./phone-intake.mjs";

const CODE = "123456";
let root;
let server;
let base;

const meta = () => ({
  schema: META_SCHEMA,
  capturedAt: "2026-10-17T20:00:00.000Z",
  durationMs: 12000,
  settings: { resolution: "1080p", fps: 30 },
});

const auth = { authorization: `Bearer ${CODE}` };

beforeAll(async () => {
  root = await mkdtemp(join(tmpdir(), "phone-intake-test-"));
  server = createServer(createIntakeHandler({ code: CODE, root }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

afterAll(async () => {
  await new Promise((resolve) => server.close(resolve));
  await rm(root, { recursive: true, force: true });
});

describe("phone intake", () => {
  it("answers health without a code and says whether the code was right", async () => {
    const open = await (await fetch(`${base}/v1/health`)).json();
    expect(open).toMatchObject({ service: "verge-phone-intake", contract: 1, authorised: false });
    const paired = await (await fetch(`${base}/v1/health`, { headers: auth })).json();
    expect(paired.authorised).toBe(true);
  });

  it("refuses a capture without the pairing code", async () => {
    const res = await fetch(`${base}/v1/captures`, { method: "POST", body: JSON.stringify(meta()) });
    expect(res.status).toBe(401);
  });

  it("refuses routes outside the contract even with the code", async () => {
    const res = await fetch(`${base}/api/cloud/deploy`, { method: "POST", headers: auth });
    expect(res.status).toBe(404);
  });

  it("refuses metadata that is not the capture schema", async () => {
    const res = await fetch(`${base}/v1/captures`, {
      method: "POST",
      headers: auth,
      body: JSON.stringify({ ...meta(), schema: "something-else" }),
    });
    expect(res.status).toBe(400);
  });

  it("walks announce → upload → status, and keeps the four GreenV fields", async () => {
    const created = await fetch(`${base}/v1/captures`, { method: "POST", headers: auth, body: JSON.stringify(meta()) });
    expect(created.status).toBe(201);
    const { id, uploadPath } = await created.json();
    expect(isCaptureId(id)).toBe(true);

    const bytes = Buffer.alloc(4096, 7);
    const put = await fetch(`${base}${uploadPath}`, {
      method: "PUT",
      headers: { ...auth, "content-type": "video/mp4", "content-length": String(bytes.length) },
      body: bytes,
    });
    expect(put.status).toBe(200);
    expect((await put.json()).bytes).toBe(4096);

    const again = await fetch(`${base}${uploadPath}`, {
      method: "PUT",
      headers: { ...auth, "content-type": "video/mp4", "content-length": String(bytes.length) },
      body: bytes,
    });
    expect(again.status).toBe(409);

    const status = await (await fetch(`${base}/v1/captures/${id}`, { headers: auth })).json();
    expect(status.state).toBe("received");
    expect(status.phases.map((p) => p.phase)).toEqual(["created", "uploading", "received"]);

    const stored = JSON.parse(await readFile(join(root, id, "meta.json"), "utf8"));
    expect(stored).toMatchObject({ rodovia: null, sentido: null, km: null, purpose: "demo", capturado_em: meta().capturedAt });

    await recordPhase(id, "extracting", { fps: 5 }, { root });
    const [listed] = (await listCaptures({ root })).filter((c) => c.id === id);
    expect(listed.state).toBe("extracting");
  });

  it("refuses a video type it does not know", async () => {
    const { uploadPath } = await (await fetch(`${base}/v1/captures`, { method: "POST", headers: auth, body: JSON.stringify(meta()) })).json();
    const res = await fetch(`${base}${uploadPath}`, {
      method: "PUT",
      headers: { ...auth, "content-type": "image/png", "content-length": "4" },
      body: Buffer.from("abcd"),
    });
    expect(res.status).toBe(415);
  });

  it("refuses an unknown phase name", async () => {
    const { id } = await (await fetch(`${base}/v1/captures`, { method: "POST", headers: auth, body: JSON.stringify(meta()) })).json();
    await expect(recordPhase(id, "teleported", undefined, { root })).rejects.toThrow(/unknown phase/);
  });
});

describe("the studio's side of the inbox", () => {
  const route = (method, pathname, body) =>
    studioPhoneRoute({ method, pathname, root, readBody: async () => body, probe: async () => ({ durationS: 1, nativeFps: 30, width: 1080, height: 1920 }) });

  async function arrived() {
    const { id, uploadPath } = await (await fetch(`${base}/v1/captures`, { method: "POST", headers: auth, body: JSON.stringify(meta()) })).json();
    const bytes = Buffer.alloc(64, 1);
    await fetch(`${base}${uploadPath}`, { method: "PUT", headers: { ...auth, "content-type": "video/mp4", "content-length": "64" }, body: bytes });
    return id;
  }

  it("loads an arrived capture as a clip source and records that it did", async () => {
    const id = await arrived();
    const loaded = await route("POST", `/api/phone/captures/${id}/load`);
    expect(loaded.status).toBe(200);
    expect(loaded.body).toMatchObject({ captureId: id, sizeBytes: 64, width: 1080, height: 1920 });
    expect(loaded.body.path.startsWith(join(root, id))).toBe(true);
    const listed = await route("GET", "/api/phone/captures");
    expect(listed.body.captures.find((c) => c.id === id).state).toBe("loaded");
  });

  it("refuses to load a capture whose video has not arrived", async () => {
    const { id } = await (await fetch(`${base}/v1/captures`, { method: "POST", headers: auth, body: JSON.stringify(meta()) })).json();
    expect((await route("POST", `/api/phone/captures/${id}/load`)).status).toBe(409);
  });

  it("stores the tape in metres and refuses centimetres by mistake", async () => {
    const id = await arrived();
    expect((await route("POST", `/api/phone/captures/${id}/truth`, { truthM: 45 })).status).toBe(400);
    const saved = await route("POST", `/api/phone/captures/${id}/truth`, { truthM: 0.45, object: "PC case" });
    expect(saved.body.truth).toMatchObject({ truthM: 0.45, object: "PC case" });
  });

  it("records a phase the phone then reads back, and refuses unknown ones", async () => {
    const id = await arrived();
    expect((await route("POST", `/api/phone/captures/${id}/phase`, { phase: "inferred", detail: { gpuSeconds: 7.5 } })).status).toBe(200);
    expect((await route("POST", `/api/phone/captures/${id}/phase`, { phase: "teleported" })).status).toBe(400);
    const status = await (await fetch(`${base}/v1/captures/${id}`, { headers: auth })).json();
    expect(status.state).toBe("inferred");
    expect(status.phases.at(-1).detail).toEqual({ gpuSeconds: 7.5 });
  });

  it("is not a route for paths outside /api/phone", async () => {
    expect(await route("GET", "/api/phone/elsewhere")).toBeNull();
    // A traversal never matches the route at all, so it falls through to the API's own 404.
    expect(await route("GET", "/api/phone/captures/../../etc")).toBeNull();
    expect((await route("GET", "/api/phone/captures/cap-20261017-000000-zzzz")).status).toBe(404);
  });
});

describe("inbox housekeeping", () => {
  it("only ever touches directories shaped like capture ids", () => {
    expect(isCaptureId(newCaptureId())).toBe(true);
    expect(isCaptureId("../etc")).toBe(false);
    expect(isCaptureId("cap-20261017-200000-ab")).toBe(false);
  });

  it("prunes captures older than the window and keeps fresh ones", async () => {
    const before = (await listCaptures({ root })).length;
    expect(await pruneInbox({ root, now: Date.now() })).toEqual([]);
    const removed = await pruneInbox({ root, now: Date.now() + 4 * 24 * 60 * 60 * 1000 });
    expect(removed.length).toBe(before);
  });

  it("validates the capture timestamp", () => {
    expect(validateMeta({ ...meta(), capturedAt: "yesterday" })).toMatch(/capturedAt/);
    expect(validateMeta(meta())).toBeNull();
  });
});
