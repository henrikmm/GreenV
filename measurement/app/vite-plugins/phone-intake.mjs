// Phone intake: the one door a phone on the local network may walk through.
//
// The local API (local-api.mjs) answers loopback only, and that stays true. This is a SECOND
// listener, on its own port, that accepts exactly three things — a health check, a capture's
// metadata and that capture's video — and refuses everything else. Nothing here can reach the
// cloud, spend money or read a run; it writes into an inbox that the studio, on loopback, reads.
//
// It is opt-in. Without VERGE_PHONE_INTAKE the plugin starts nothing, because binding 0.0.0.0
// makes the port readable by anyone sharing the network — the same reason compose.lan.yaml is
// never merged unless named. Start it with:
//
//   VERGE_PHONE_INTAKE=1 npm run dev --prefix app
//
// The wire contract (docs/NEXT-DEMO.md at the GreenV root, "Phone ↔ gateway contract") is what
// a cloud gateway implements later; the phone only changes its base URL.
//
// Every capture keeps a timeline of what happened to it and when, measured on this computer's
// clock only. The phone's own clock appears in the metadata it sent, never subtracted from ours:
// two clocks a few hundred milliseconds apart would invent a network time.

import { createHash, randomInt, timingSafeEqual } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { networkInterfaces, tmpdir } from "node:os";
import { join } from "node:path";
import { Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const run = promisify(execFile);

export const CONTRACT_VERSION = 1;
export const META_SCHEMA = "verge-phone-capture/1";
export const DEFAULT_PORT = 8790;
/** 4K at 30 fps for 30 s is ~150 MB on the phones tried; this leaves room without inviting a film. */
export const MAX_VIDEO_BYTES = 400 * 1024 * 1024;
const MAX_META_BYTES = 64 * 1024;
/** Captures are scratch: a visitor's face is in them. Three days covers an evening's comparisons. */
export const INBOX_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000;

const VIDEO_TYPES = new Map([
  ["video/mp4", "mp4"],
  ["video/quicktime", "mov"],
  ["video/webm", "webm"],
]);

/** Phases the studio may report back, in the order a capture normally walks them. */
export const PHASES = [
  "created",
  "uploading",
  "received",
  "loaded",
  "extracting",
  "extracted",
  "uploading-frames",
  "waking",
  "loading-model",
  "inferring",
  "inferred",
  "fetched",
  "measured",
  "failed",
];

export function inboxRoot() {
  return process.env.VERGE_PHONE_INBOX || join(tmpdir(), "verge-phone-inbox");
}

const ID_PATTERN = /^cap-\d{8}-\d{6}-[a-z0-9]{4}$/;

export function newCaptureId(now = new Date()) {
  const pad = (n) => String(n).padStart(2, "0");
  const stamp =
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-` +
    `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  const suffix = Array.from({ length: 4 }, () => "abcdefghijklmnopqrstuvwxyz0123456789"[randomInt(36)]).join("");
  return `cap-${stamp}-${suffix}`;
}

export function isCaptureId(id) {
  return typeof id === "string" && ID_PATTERN.test(id);
}

/**
 * The pairing code survives restarts, so a phone configured once keeps working. It is a
 * speed bump against a stranger on the same Wi-Fi, not authentication.
 */
export async function pairingCode(root = inboxRoot()) {
  if (process.env.VERGE_PHONE_CODE) return process.env.VERGE_PHONE_CODE;
  const file = join(root, ".pairing-code");
  try {
    const existing = (await readFile(file, "utf8")).trim();
    if (/^\d{6}$/.test(existing)) return existing;
  } catch {
    // first start
  }
  const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
  await mkdir(root, { recursive: true });
  await writeFile(file, code + "\n", { mode: 0o600 });
  return code;
}

export function lanAddresses() {
  const out = [];
  for (const [name, entries] of Object.entries(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family === "IPv4" && !entry.internal) out.push({ name, address: entry.address });
    }
  }
  return out;
}

/** Refuses anything that is not the metadata shape the phone sends; keeps unknown fields. */
export function validateMeta(meta) {
  if (!meta || typeof meta !== "object" || Array.isArray(meta)) return "metadata must be a JSON object";
  if (meta.schema !== META_SCHEMA) return `metadata.schema must be "${META_SCHEMA}"`;
  if (typeof meta.capturedAt !== "string" || Number.isNaN(Date.parse(meta.capturedAt))) {
    return "metadata.capturedAt must be an ISO-8601 timestamp";
  }
  if (meta.settings !== undefined && (typeof meta.settings !== "object" || meta.settings === null)) {
    return "metadata.settings must be an object";
  }
  return null;
}

// ---------------------------------------------------------------------------------------------
// The inbox. One directory per capture: meta.json, video.<ext>, timeline.json.

function captureDir(root, id) {
  return join(root, id);
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

async function writeJson(path, value) {
  const tmp = `${path}.tmp`;
  await writeFile(tmp, JSON.stringify(value, null, 2));
  await rename(tmp, path);
}

export async function createCapture(meta, { root = inboxRoot(), now = new Date() } = {}) {
  const id = newCaptureId(now);
  const dir = captureDir(root, id);
  await mkdir(dir, { recursive: true });
  // The four fields every GreenV artifact carries. A demo capture is on no road, and says so
  // rather than leaving the fields absent.
  const stored = {
    rodovia: null,
    sentido: null,
    km: null,
    purpose: "demo",
    ...meta,
    capturado_em: meta.capturedAt,
    receivedBy: { contract: CONTRACT_VERSION, createdAt: now.toISOString() },
  };
  await writeJson(join(dir, "meta.json"), stored);
  await writeJson(join(dir, "timeline.json"), {
    id,
    createdAt: now.toISOString(),
    events: [{ phase: "created", at: now.toISOString(), tMs: 0 }],
  });
  return { id, dir };
}

/** Append one phase to a capture's timeline. `tMs` is milliseconds since the capture was created. */
export async function recordPhase(id, phase, detail, { root = inboxRoot(), now = new Date() } = {}) {
  if (!isCaptureId(id)) throw new Error(`not a capture id: ${id}`);
  if (!PHASES.includes(phase)) throw new Error(`unknown phase: ${phase}`);
  const path = join(captureDir(root, id), "timeline.json");
  const timeline = await readJson(path);
  const tMs = now.getTime() - Date.parse(timeline.createdAt);
  timeline.events.push(detail === undefined ? { phase, at: now.toISOString(), tMs } : { phase, at: now.toISOString(), tMs, detail });
  await writeJson(path, timeline);
  return timeline;
}

export async function readCapture(id, { root = inboxRoot() } = {}) {
  if (!isCaptureId(id)) return null;
  const dir = captureDir(root, id);
  try {
    const [meta, timeline] = await Promise.all([readJson(join(dir, "meta.json")), readJson(join(dir, "timeline.json"))]);
    let video = null;
    try {
      video = await readJson(join(dir, "video.json"));
    } catch {
      // not uploaded yet
    }
    const last = timeline.events[timeline.events.length - 1];
    return { id, state: last.phase, meta, video, timeline };
  } catch {
    return null;
  }
}

export async function listCaptures({ root = inboxRoot() } = {}) {
  let names = [];
  try {
    names = await readdir(root);
  } catch {
    return [];
  }
  const captures = await Promise.all(names.filter(isCaptureId).map((id) => readCapture(id, { root })));
  return captures.filter(Boolean).sort((a, b) => b.id.localeCompare(a.id));
}

export async function discardCapture(id, { root = inboxRoot() } = {}) {
  if (!isCaptureId(id)) throw new Error(`not a capture id: ${id}`);
  await rm(captureDir(root, id), { recursive: true, force: true });
}

export async function pruneInbox({ root = inboxRoot(), now = Date.now(), maxAgeMs = INBOX_MAX_AGE_MS } = {}) {
  const removed = [];
  let names = [];
  try {
    names = await readdir(root);
  } catch {
    return removed;
  }
  for (const id of names.filter(isCaptureId)) {
    try {
      const info = await stat(captureDir(root, id));
      if (now - info.mtimeMs > maxAgeMs) {
        await rm(captureDir(root, id), { recursive: true, force: true });
        removed.push(id);
      }
    } catch {
      // vanished meanwhile
    }
  }
  return removed;
}

/**
 * What the file actually is, as opposed to what the phone was asked for. The camera plugin
 * never reports the format it was granted, so this is the only record of resolution, codec,
 * frame rate, rotation and transfer curve (an HDR transfer here means washed-out frames later).
 */
export async function describeVideo(path) {
  const { stdout } = await run("ffprobe", [
    "-v", "error",
    "-select_streams", "v:0",
    "-show_entries",
    "stream=codec_name,profile,pix_fmt,width,height,avg_frame_rate,r_frame_rate,nb_frames,bit_rate,color_transfer,color_primaries:stream_side_data=rotation:stream_tags=rotate:format=duration,bit_rate,size",
    "-of", "json",
    path,
  ]);
  const probed = JSON.parse(stdout);
  const s = probed.streams?.[0] ?? {};
  const rate = (r) => {
    const [n, d] = String(r ?? "0/1").split("/").map(Number);
    return d > 0 ? Math.round((n / d) * 100) / 100 : null;
  };
  const sideRotation = s.side_data_list?.find((d) => d.rotation !== undefined)?.rotation;
  const rotation = Number(sideRotation ?? s.tags?.rotate ?? 0);
  return {
    codec: s.codec_name ?? null,
    profile: s.profile ?? null,
    pixFmt: s.pix_fmt ?? null,
    storedWidth: s.width ?? null,
    storedHeight: s.height ?? null,
    rotation,
    avgFps: rate(s.avg_frame_rate),
    frames: s.nb_frames ? Number(s.nb_frames) : null,
    videoBitRate: s.bit_rate ? Number(s.bit_rate) : null,
    colorTransfer: s.color_transfer ?? null,
    colorPrimaries: s.color_primaries ?? null,
    hdr: ["smpte2084", "arib-std-b67"].includes(s.color_transfer),
    durationS: probed.format?.duration ? Number(probed.format.duration) : null,
  };
}

/** Streams the body to disk, hashing as it goes. Never buffers a whole video in memory. */
export async function receiveVideo(id, body, { contentType, contentLength, root = inboxRoot(), now = () => new Date() }) {
  const ext = VIDEO_TYPES.get(String(contentType ?? "").split(";")[0].trim());
  if (!ext) throw Object.assign(new Error(`unsupported content-type ${contentType}`), { status: 415 });
  if (!Number.isFinite(contentLength) || contentLength <= 0) {
    throw Object.assign(new Error("content-length is required"), { status: 411 });
  }
  if (contentLength > MAX_VIDEO_BYTES) {
    throw Object.assign(new Error(`video is ${contentLength} bytes, over the ${MAX_VIDEO_BYTES} limit`), { status: 413 });
  }
  const dir = captureDir(root, id);
  const startedAt = now();
  await recordPhase(id, "uploading", { bytesExpected: contentLength }, { root, now: startedAt });

  const hash = createHash("sha256");
  let bytes = 0;
  let firstByteAt = null;
  const meter = new Transform({
    transform(chunk, _enc, done) {
      if (firstByteAt === null) firstByteAt = now();
      bytes += chunk.length;
      if (bytes > MAX_VIDEO_BYTES) return done(Object.assign(new Error("video over the limit"), { status: 413 }));
      hash.update(chunk);
      done(null, chunk);
    },
  });
  const partial = join(dir, `video.${ext}.part`);
  await pipeline(body, meter, createWriteStream(partial));
  if (bytes !== contentLength) {
    await rm(partial, { force: true });
    throw Object.assign(new Error(`received ${bytes} of ${contentLength} bytes`), { status: 400 });
  }
  const finishedAt = now();
  const file = join(dir, `video.${ext}`);
  await rename(partial, file);

  const transferMs = finishedAt.getTime() - (firstByteAt ?? startedAt).getTime();
  const video = {
    file: `video.${ext}`,
    contentType: String(contentType).split(";")[0].trim(),
    bytes,
    sha256: hash.digest("hex"),
    transferMs,
    megabytesPerSecond: transferMs > 0 ? Math.round((bytes / 1e6 / (transferMs / 1000)) * 100) / 100 : null,
    probe: null,
  };
  try {
    video.probe = await describeVideo(file);
  } catch (error) {
    video.probeError = String(error?.message ?? error);
  }
  await writeJson(join(dir, "video.json"), video);
  await recordPhase(id, "received", { bytes, transferMs, megabytesPerSecond: video.megabytesPerSecond }, { root, now: finishedAt });
  return video;
}

/** A one-line summary for the dev server log — the numbers the optimisation pass will want. */
export function describeArrival(id, video, meta) {
  const mb = (video.bytes / 1e6).toFixed(1);
  const p = video.probe ?? {};
  const asked = meta?.settings ? `${meta.settings.resolution ?? "?"}@${meta.settings.fps ?? "?"}` : "?";
  const got = p.storedWidth ? `${p.storedWidth}x${p.storedHeight} r${p.rotation} ${p.avgFps}fps ${p.codec}${p.hdr ? " HDR" : ""}` : "unprobed";
  const rate = p.videoBitRate ? ` ${(p.videoBitRate / 1e6).toFixed(1)} Mbit/s` : "";
  const dur = p.durationS ? ` ${p.durationS.toFixed(1)} s` : "";
  return `[phone] ${id} ${mb} MB in ${(video.transferMs / 1000).toFixed(1)} s (${video.megabytesPerSecond ?? "?"} MB/s) · asked ${asked} · got ${got}${dur}${rate}`;
}

/** Merge fields into a capture's stored metadata (the tape truth, for one). */
export async function updateMeta(id, patch, { root = inboxRoot() } = {}) {
  if (!isCaptureId(id)) throw new Error(`not a capture id: ${id}`);
  const path = join(captureDir(root, id), "meta.json");
  const meta = { ...(await readJson(path)), ...patch };
  await writeJson(path, meta);
  return meta;
}

/** A capture as the studio lists it: everything an operator compares two captures by. */
export function studioView(capture) {
  const { meta, video, timeline } = capture;
  return {
    id: capture.id,
    state: capture.state,
    capturedAt: meta.capturedAt ?? null,
    durationMs: meta.durationMs ?? null,
    settings: meta.settings ?? null,
    applied: meta.applied ?? null,
    orbit: meta.orbit ?? null,
    truth: meta.truth ?? null,
    sampling: meta.sampling ?? null,
    video: video
      ? { bytes: video.bytes, transferMs: video.transferMs, megabytesPerSecond: video.megabytesPerSecond, probe: video.probe }
      : null,
    events: timeline.events,
  };
}

/**
 * The studio's side of the inbox, served on the loopback-only local API under /api/phone/.
 * Returns `{ status, body }`, or null when the path is not one of these routes.
 *
 * - GET  /api/phone/info                 — whether the intake runs, its address and pairing code
 * - GET  /api/phone/captures             — every capture in the inbox, newest first
 * - POST /api/phone/captures/{id}/load   — the video as a clip source (path, digest, probe)
 * - POST /api/phone/captures/{id}/phase  — `{phase, detail?}`, which the phone then reads back
 * - POST /api/phone/captures/{id}/truth  — `{truthM, object?}`, the tape for this capture
 * - DELETE /api/phone/captures/{id}
 */
export async function studioPhoneRoute({ method, pathname, readBody, probe, root = inboxRoot() }) {
  if (pathname === "/api/phone/info" && method === "GET") {
    const enabled = Boolean(process.env.VERGE_PHONE_INTAKE);
    const port = Number(process.env.VERGE_PHONE_PORT) || DEFAULT_PORT;
    return {
      status: 200,
      body: {
        enabled,
        port,
        code: enabled ? await pairingCode(root) : null,
        urls: enabled ? lanAddresses().map((a) => `http://${a.address}:${port}`) : [],
        inbox: root,
      },
    };
  }
  if (pathname === "/api/phone/captures" && method === "GET") {
    return { status: 200, body: { captures: (await listCaptures({ root })).map(studioView) } };
  }
  const route = /^\/api\/phone\/captures\/([^/]+)(?:\/(load|phase|truth))?$/.exec(pathname);
  if (!route) return null;
  const [, id, action] = route;
  const capture = await readCapture(id, { root });
  if (!capture) return { status: 404, body: { detail: "no such capture" } };

  if (!action && method === "DELETE") {
    await discardCapture(id, { root });
    return { status: 200, body: { discarded: true } };
  }
  if (action === "load" && method === "POST") {
    if (!capture.video) return { status: 409, body: { detail: "the video has not arrived yet" } };
    const path = join(captureDir(root, id), capture.video.file);
    const probed = await probe(path);
    await recordPhase(id, "loaded", undefined, { root });
    return {
      status: 200,
      body: {
        path,
        name: `${id}.${capture.video.file.split(".").pop()}`,
        sizeBytes: capture.video.bytes,
        sha256: capture.video.sha256,
        ...probed,
        captureId: id,
      },
    };
  }
  if (action === "phase" && method === "POST") {
    const { phase, detail } = await readBody();
    if (!PHASES.includes(phase)) return { status: 400, body: { detail: `unknown phase ${phase}` } };
    const timeline = await recordPhase(id, phase, detail, { root });
    return { status: 200, body: { events: timeline.events } };
  }
  if (action === "truth" && method === "POST") {
    const { truthM, object } = await readBody();
    if (truthM !== null && !(Number.isFinite(truthM) && truthM > 0 && truthM < 20)) {
      return { status: 400, body: { detail: "truthM must be metres, between 0 and 20, or null" } };
    }
    const meta = await updateMeta(id, { truth: truthM === null ? null : { truthM, object: object ?? null, recordedAt: new Date().toISOString() } }, { root });
    return { status: 200, body: { truth: meta.truth } };
  }
  return { status: 405, body: { detail: `${method} is not allowed here` } };
}

// ---------------------------------------------------------------------------------------------
// HTTP

function send(res, status, body) {
  res.statusCode = status;
  res.setHeader("content-type", "application/json");
  res.setHeader("cache-control", "no-store");
  res.end(JSON.stringify(body));
}

function authorised(req, code) {
  const header = String(req.headers.authorization ?? "");
  const presented = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  const a = Buffer.from(presented);
  const b = Buffer.from(code);
  return a.length === b.length && timingSafeEqual(a, b);
}

async function readSmallJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_META_BYTES) throw Object.assign(new Error("metadata too large"), { status: 413 });
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw Object.assign(new Error("metadata is not JSON"), { status: 400 });
  }
}

/** A capture as the phone sees it: no paths, no hashes it did not ask for. */
function publicView(capture) {
  return {
    id: capture.id,
    state: capture.state,
    phases: capture.timeline.events,
    video: capture.video ? { bytes: capture.video.bytes, transferMs: capture.video.transferMs } : null,
    result: capture.meta.result ?? null,
  };
}

export function createIntakeHandler({ code, root = inboxRoot(), log = () => {} }) {
  return async (req, res) => {
    const url = new URL(req.url ?? "/", "http://intake");
    try {
      if (req.method === "GET" && url.pathname === "/v1/health") {
        return send(res, 200, { service: "verge-phone-intake", contract: CONTRACT_VERSION, authorised: authorised(req, code) });
      }
      if (!authorised(req, code)) return send(res, 401, { detail: "missing or wrong pairing code" });

      if (req.method === "POST" && url.pathname === "/v1/captures") {
        const meta = await readSmallJson(req);
        const problem = validateMeta(meta);
        if (problem) return send(res, 400, { detail: problem });
        const { id } = await createCapture(meta, { root });
        log(`[phone] ${id} announced · ${meta.settings?.resolution ?? "?"}@${meta.settings?.fps ?? "?"} · ${meta.durationMs ?? "?"} ms recorded`);
        return send(res, 201, { id, uploadPath: `/v1/captures/${id}/video` });
      }

      const videoRoute = /^\/v1\/captures\/([^/]+)\/video$/.exec(url.pathname);
      if (req.method === "PUT" && videoRoute) {
        const id = videoRoute[1];
        const capture = await readCapture(id, { root });
        if (!capture) return send(res, 404, { detail: "no such capture" });
        if (capture.video) return send(res, 409, { detail: "video already received" });
        const video = await receiveVideo(id, req, {
          contentType: req.headers["content-type"],
          contentLength: Number(req.headers["content-length"]),
          root,
        });
        log(describeArrival(id, video, capture.meta));
        return send(res, 200, { id, bytes: video.bytes, sha256: video.sha256, transferMs: video.transferMs });
      }

      const statusRoute = /^\/v1\/captures\/([^/]+)$/.exec(url.pathname);
      if (req.method === "GET" && statusRoute) {
        const capture = await readCapture(statusRoute[1], { root });
        if (!capture) return send(res, 404, { detail: "no such capture" });
        return send(res, 200, publicView(capture));
      }

      return send(res, 404, { detail: "not part of the phone contract" });
    } catch (error) {
      const status = Number(error?.status) || 500;
      log(`[phone] ${req.method} ${url.pathname} failed: ${error?.message ?? error}`);
      if (!res.headersSent) send(res, status, { detail: String(error?.message ?? error) });
      else res.destroy();
    }
  };
}

const INTAKE_KEY = Symbol.for("verge.phone-intake.server");

/** Vite plugin. Inert unless VERGE_PHONE_INTAKE is set. */
export function phoneIntake() {
  return {
    name: "verge-phone-intake",
    apply: "serve",
    async configureServer(server) {
      if (!process.env.VERGE_PHONE_INTAKE) return;
      const logger = server.config.logger;
      const root = inboxRoot();
      await mkdir(root, { recursive: true });
      const removed = await pruneInbox({ root }).catch(() => []);
      if (removed.length) logger.info(`[phone] pruned ${removed.length} capture(s) older than three days`);
      const code = await pairingCode(root);
      const port = Number(process.env.VERGE_PHONE_PORT) || DEFAULT_PORT;
      // Vite restarts by building a new server before the old one has finished closing, so the
      // old listener would still hold the port. One intake per process, kept on globalThis
      // because a restart re-imports this module, and closed before the new one listens.
      const previous = globalThis[INTAKE_KEY];
      if (previous) {
        previous.closeAllConnections?.();
        await new Promise((resolve) => previous.close(() => resolve()));
      }
      const intake = createServer(createIntakeHandler({ code, root, log: (line) => logger.info(line, { timestamp: true }) }));
      globalThis[INTAKE_KEY] = intake;
      // A slow phone on weak Wi-Fi must not be cut off mid-video by Node's default request timeout.
      intake.requestTimeout = 10 * 60_000;
      intake.listen(port, "0.0.0.0", () => {
        const where = lanAddresses().map((a) => `http://${a.address}:${port}`).join("  ") || `port ${port}`;
        logger.info(`[phone] intake listening: ${where}  ·  pairing code ${code}  ·  inbox ${root}`);
      });
      intake.on("error", (error) => logger.error(`[phone] intake could not listen on ${port}: ${error.message}`));
      server.httpServer?.once("close", () => {
        if (globalThis[INTAKE_KEY] === intake) globalThis[INTAKE_KEY] = undefined;
        intake.close();
      });
    },
  };
}
