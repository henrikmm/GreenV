/**
 * Where a run's seconds went, stage by stage — the timing log behind Setup's Run section.
 *
 * The phase readout answers "what is it doing now". This answers the question asked after a
 * slow run: which stretch was slow. On the cloud demo on 2026-10-23 a 51 s capture spent 7 s on
 * the GPU (`cap-20261023-012557-k5en`); the rest was upload, publishing and download, and nothing
 * on screen said so. Every span here is either two observed timestamps on this machine, or a
 * duration the GPU service measured itself and returned in the manifest — those are marked
 * `derived` and placed so they end when the response arrived, because the service reports how
 * long, never when.
 *
 * Timestamps are wall-clock (`Date.now()`), not `performance.now()`, so a phone capture's own
 * events — written by the intake on this same Mac — line up with the browser's.
 *
 * The log is a record, not a control: it never starts, retries or delays anything, and it touches
 * no network. It runs whether or not the panel is open, so opening it after a slow run still
 * shows that run.
 */

import { useSyncExternalStore } from "react";
import type { InferManifest } from "./contract";
import { getRunPhase, subscribeRunPhase, type PhaseKind, type RunPhaseState } from "./run-phase";

/** Which part of the chain a stretch belongs to. The totals line sums top-level spans by this. */
export type SpanGroup = "network" | "mac" | "gpu" | "browser";

export const GROUP_LABEL: Record<SpanGroup, string> = {
  network: "network",
  mac: "this Mac",
  gpu: "GPU service",
  browser: "browser",
};

export interface TimelineSpan {
  key: string;
  label: string;
  group: SpanGroup;
  startMs: number;
  /** Null while it is still running. */
  endMs: number | null;
  /** A breakdown of another span; drawn indented and left out of the totals. */
  parent?: string;
  bytes?: number;
  note?: string;
  /** A duration reported by the GPU service rather than two timestamps observed here. */
  derived?: boolean;
}

interface State {
  spans: TimelineSpan[];
  /** Artifact URLs of the run being timed, so a saved run opened later is not logged as this one. */
  artifacts: Set<string>;
}

let state: State = { spans: [], artifacts: new Set() };
const listeners = new Set<() => void>();

function commit(next: State): void {
  state = next;
  for (const listener of listeners) listener();
}

export function getTimeline(): TimelineSpan[] {
  return state.spans;
}

export function useTimeline(): TimelineSpan[] {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => state.spans,
    () => state.spans,
  );
}

/** A new clip: nothing logged so far describes it. */
export function resetTimeline(): void {
  commit({ spans: [], artifacts: new Set() });
}

/** Add a span, replacing any with the same key — a re-extract replaces the extract. */
export function putSpan(span: TimelineSpan): void {
  commit({ ...state, spans: [...state.spans.filter((s) => s.key !== span.key), span] });
}

function patchSpan(key: string, patch: Partial<TimelineSpan>): void {
  if (!state.spans.some((s) => s.key === key)) return;
  commit({ ...state, spans: state.spans.map((s) => (s.key === key ? { ...s, ...patch } : s)) });
}

function dropSpans(keep: (span: TimelineSpan) => boolean): void {
  commit({ ...state, spans: state.spans.filter(keep) });
}

/** Time an awaited step. The span stays open on screen while it runs and closes either way. */
export async function timeSpan<T>(
  span: Omit<TimelineSpan, "startMs" | "endMs">,
  work: () => Promise<T>,
  after?: (result: T) => Partial<TimelineSpan>,
): Promise<T> {
  putSpan({ ...span, startMs: Date.now(), endMs: null });
  try {
    const result = await work();
    patchSpan(span.key, { endMs: Date.now(), ...after?.(result) });
    return result;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    // A build the graph restarted is not a failure of the stretch being timed, but it is work
    // that was paid for and thrown away, so it stays on the log under its own name.
    const cancelled = (error instanceof Error && error.name === "AbortError") || /abort/i.test(message);
    patchSpan(span.key, { endMs: Date.now(), note: cancelled ? "cancelled: a newer build replaced it" : `failed: ${message}` });
    throw error;
  }
}

// ---------------------------------------------------------------------------------------------
// The phone's half

interface PhoneEventLike {
  phase: string;
  at: string;
}

/**
 * The phone's upload, read off the intake's own events. The intake runs on this Mac, so its
 * clock is this clock. Absent when the capture has not reached `received`.
 */
export function notePhoneUpload(events: PhoneEventLike[], bytes: number | null): void {
  const start = events.find((e) => e.phase === "uploading");
  const end = events.find((e) => e.phase === "received");
  if (!start || !end) return;
  putSpan({
    key: "phone-upload",
    label: "phone → Mac: video upload",
    group: "network",
    startMs: Date.parse(start.at),
    endMs: Date.parse(end.at),
    bytes: bytes ?? undefined,
  });
}

// ---------------------------------------------------------------------------------------------
// The run itself

/** Run phases that open a span of their own. Waking, loading and inferring fold into one. */
const RUN_SPAN: Partial<Record<PhaseKind, { key: string; label: string; group: SpanGroup }>> = {
  reading: { key: "read-frames", label: "read frames off disk", group: "mac" },
  uploading: { key: "upload-frames", label: "Mac → GPU service: frame upload", group: "network" },
  waking: { key: "server-wait", label: "GPU service: request open", group: "gpu" },
  loadingModel: { key: "server-wait", label: "GPU service: request open", group: "gpu" },
  inferring: { key: "server-wait", label: "GPU service: request open", group: "gpu" },
  fetching: { key: "after-gpu", label: "artifacts down + views built", group: "browser" },
};

/** Spans that belong to one press of Run, cleared by the next. Clip spans survive it. */
const RUN_KEYS = new Set(["read-frames", "upload-frames", "server-wait", "after-gpu"]);

/**
 * What the telemetry poll saw while the request was open, in seconds per phase. Poll-grained:
 * the poll runs every 2 s against a real service, so each figure is good to about that.
 */
let serverPhases: Partial<Record<PhaseKind, number>> = {};
let serverPhase: { kind: PhaseKind; since: number } | null = null;

function closeServerPhase(now: number): void {
  if (!serverPhase) return;
  serverPhases[serverPhase.kind] = (serverPhases[serverPhase.kind] ?? 0) + (now - serverPhase.since);
  serverPhase = null;
}

function serverPhaseNote(): string | undefined {
  const parts = (["waking", "loadingModel", "inferring"] as const)
    .filter((k) => serverPhases[k] !== undefined)
    .map((k) => `${POLL_LABEL[k]} ${(serverPhases[k]! / 1000).toFixed(1)} s`);
  return parts.length ? `poll saw: ${parts.join(" · ")}` : undefined;
}

const POLL_LABEL = { waking: "nothing answering", loadingModel: "model loading", inferring: "busy" } as const;

let lastKind: PhaseKind = getRunPhase().kind;
let openKey: string | null = null;

/** Exported for the tests, which drive it with run-phase states and no network. */
export function followRunPhase(s: RunPhaseState, now = Date.now()): void {
  if (s.kind === lastKind) {
    if (s.kind === "uploading" && openKey === "upload-frames") patchSpan("upload-frames", { bytes: s.upload.totalBytes });
    return;
  }
  const previous = lastKind;
  lastKind = s.kind;

  if (s.kind === "reading" && (previous === "idle" || previous === "done" || previous === "failed")) {
    dropSpans((span) => !RUN_KEYS.has(span.key) && !RUN_KEYS.has(span.parent ?? ""));
    commit({ ...state, artifacts: new Set() });
    serverPhases = {};
    serverPhase = null;
    openKey = null;
  }

  const isServer = s.kind === "waking" || s.kind === "loadingModel" || s.kind === "inferring";
  if (isServer) {
    closeServerPhase(now);
    serverPhase = { kind: s.kind, since: now };
  } else {
    closeServerPhase(now);
  }

  const next = RUN_SPAN[s.kind];
  if (openKey && next?.key !== openKey) {
    patchSpan(openKey, { endMs: now, ...(openKey === "server-wait" ? { note: serverPhaseNote() } : {}) });
    openKey = null;
  }
  if (next && next.key !== openKey) {
    putSpan({ ...next, startMs: now, endMs: null, ...(s.kind === "uploading" ? { bytes: s.upload.totalBytes } : {}) });
    openKey = next.key;
  }
  if (s.kind === "failed" && s.failedIn) {
    const key = RUN_SPAN[s.failedIn]?.key;
    if (key) patchSpan(key, { note: `failed: ${s.message ?? "unknown"}` });
  }
  if (isServer && openKey === "server-wait") patchSpan("server-wait", { note: serverPhaseNote() });
}

subscribeRunPhase(() => {
  const before = lastKind;
  followRunPhase(getRunPhase());
  const after = getRunPhase().kind;
  // One line in the console per finished run, so a run can be read back without the panel open.
  if (before !== after && (after === "done" || after === "failed")) {
    console.info(`[verge timing]\n${timelineText(state.spans)}`);
  }
});

/**
 * The service's own clock, placed under the request that carried it.
 *
 * `wall_seconds` starts after the frames are written to the instance's disk and ends when the
 * manifest is built, so it covers the model load (when this request paid for it), the forward
 * pass with DA3's own exports, the npz the service writes, and publishing to the bucket.
 * `gpu_seconds` is the `model.inference` call alone, exports included. Everything else in the
 * open request — routing, a cold start, receiving the multipart body, the response — is outside
 * both, and is what the first breakdown row holds.
 *
 * `resolve` turns manifest paths into the URLs the browser fetches, so the downloads that follow
 * can be recognised as this run's.
 */
const SERVICE_ROWS = new Set(["server-outside", "server-load", "server-other", "server-forward"]);

export function noteManifest(manifest: InferManifest, resolve: (url: string) => string, now = Date.now()): void {
  const wallMs = Math.round(manifest.timing.wallSeconds * 1000);
  const gpuMs = Math.round(manifest.timing.gpuSeconds * 1000);
  const wait = state.spans.find((s) => s.key === "server-wait");
  const waitStart = wait?.startMs ?? now - wallMs;
  // A real service's clock fits inside the request that carried it. The local mock replays a
  // recorded manifest, whose 200 s does not fit a 6 s request, so the rows are clamped and say so.
  //
  // Order inside the wall: a model load (only when this request paid for one, which the poll
  // saw as "model loading"), then the forward pass, then our npz write and the bucket publish.
  // On 2026-10-23 a warm run put 8.8 s after an 11.5 s forward pass, and the preview — published
  // between the two — arrived 12.9 s into the request, which fixes that order.
  const wallStart = Math.max(waitStart, now - wallMs);
  const misfit = now - wallMs < waitStart ? `reported ${manifest.timing.wallSeconds.toFixed(1)} s, longer than the request` : null;
  const loadedHere = serverPhases.loadingModel !== undefined && manifest.timing.modelLoadSeconds !== undefined;
  const loadMs = loadedHere ? Math.min(Math.round(manifest.timing.modelLoadSeconds! * 1000), Math.max(0, now - wallStart - gpuMs)) : 0;
  const forwardStart = Math.min(now, wallStart + loadMs);
  const forwardEnd = Math.min(now, forwardStart + gpuMs);
  const children: TimelineSpan[] = [
    {
      key: "server-outside",
      parent: "server-wait",
      label: "outside the service's clock: routing, cold start, frame receive, response",
      group: "gpu",
      startMs: waitStart,
      endMs: wallStart,
      derived: true,
    },
    ...(loadMs > 0
      ? [
          {
            key: "server-load",
            parent: "server-wait",
            label: "service: model load",
            group: "gpu" as const,
            startMs: wallStart,
            endMs: forwardStart,
            derived: true,
          },
        ]
      : []),
    {
      key: "server-forward",
      parent: "server-wait",
      label: "service: forward pass + DA3 exports",
      group: "gpu",
      startMs: forwardStart,
      endMs: forwardEnd,
      derived: true,
      note: `${manifest.frames.count} frames · ${manifest.vram.deviceName ?? "GPU"}`,
    },
    {
      key: "server-other",
      parent: "server-wait",
      label: "service: npz write + bucket publish",
      group: "gpu",
      startMs: forwardEnd,
      endMs: now,
      derived: true,
      note: misfit ?? undefined,
    },
  ];
  const urls = new Set<string>();
  for (const artifact of manifest.artifacts) {
    urls.add(artifact.url);
    try {
      urls.add(resolve(artifact.url));
    } catch {
      // An unresolvable artifact is reported where it is fetched; here it is simply not timed.
    }
  }
  commit({
    // Only this function's own rows are replaced; the preview's rows hang off the same request.
    spans: [...state.spans.filter((s) => !SERVICE_ROWS.has(s.key)), ...children],
    artifacts: urls,
  });
}

/** True when `url` is an artifact of the run being timed. */
export function isTimedArtifact(url: string): boolean {
  return state.artifacts.has(url);
}

/**
 * Time a download and the parse that follows it, when the URL is this run's. Anything else —
 * a saved run, a fixture — passes straight through untimed.
 */
export async function timeArtifact<T>(
  url: string,
  name: string,
  download: () => Promise<ArrayBuffer>,
  parse: (buffer: ArrayBuffer) => Promise<T> | T,
): Promise<T> {
  if (!isTimedArtifact(url)) return parse(await download());
  // Each attempt keeps its own row: a second download of the same file is a finding, not noise.
  const attempt = state.spans.filter((s) => s.key === `download-${name}` || s.key.startsWith(`download-${name}#`)).length + 1;
  const suffix = attempt > 1 ? `#${attempt}` : "";
  const again = attempt > 1 ? ` (attempt ${attempt})` : "";
  const buffer = await timeSpan(
    { key: `download-${name}${suffix}`, parent: "after-gpu", label: `download ${name}${again}`, group: "network" },
    download,
    (b) => ({ bytes: b.byteLength }),
  );
  return timeSpan(
    { key: `parse-${name}${suffix}`, parent: "after-gpu", label: `parse ${name}${again}`, group: "browser" },
    async () => parse(buffer),
  );
}

// ---------------------------------------------------------------------------------------------
// Reading it

export interface TimelineRow {
  span: TimelineSpan;
  /** Milliseconds from the first span's start. */
  offsetMs: number;
  durationMs: number | null;
  /** Megabytes per second, when the span moved a known number of bytes. */
  rate: number | null;
  /** Nothing logged was running for this long before the span began. */
  idleBeforeMs: number;
}

export interface TimelineSummary {
  rows: TimelineRow[];
  /** First start to last end, idle stretches included. */
  totalMs: number | null;
  /** Top-level spans only, so a breakdown is not counted twice. */
  byGroup: Partial<Record<SpanGroup, number>>;
  idleMs: number;
  /**
   * When each cloud reached the screen, from the first logged stretch: the preview's parse
   * finishing, and the full run's views settling. Null when that cloud has not arrived.
   */
  previewOnScreenMs: number | null;
  fullOnScreenMs: number | null;
}

/** Ordered rows with offsets, idle gaps and totals. Children follow their parent. */
export function summarise(spans: TimelineSpan[], now = Date.now()): TimelineSummary {
  const top = spans.filter((s) => !s.parent).sort((a, b) => a.startMs - b.startMs);
  if (top.length === 0) return { rows: [], totalMs: null, byGroup: {}, idleMs: 0, previewOnScreenMs: null, fullOnScreenMs: null };
  const origin = Math.min(...spans.map((s) => s.startMs));
  const rows: TimelineRow[] = [];
  const byGroup: Partial<Record<SpanGroup, number>> = {};
  let reach = origin;
  let idleMs = 0;
  const row = (span: TimelineSpan, idleBeforeMs: number): TimelineRow => {
    const durationMs = (span.endMs ?? now) - span.startMs;
    return {
      span,
      offsetMs: span.startMs - origin,
      durationMs: span.endMs === null ? null : durationMs,
      rate: span.bytes && span.endMs !== null && durationMs > 0 ? span.bytes / 1e6 / (durationMs / 1000) : null,
      idleBeforeMs,
    };
  };
  for (const span of top) {
    const idle = Math.max(0, span.startMs - reach);
    idleMs += idle;
    rows.push(row(span, idle));
    for (const child of spans.filter((c) => c.parent === span.key).sort((a, b) => a.startMs - b.startMs)) {
      rows.push(row(child, 0));
    }
    const end = span.endMs ?? now;
    byGroup[span.group] = (byGroup[span.group] ?? 0) + (end - span.startMs);
    reach = Math.max(reach, end);
  }
  const lastEnd = Math.max(...spans.map((s) => s.endMs ?? now));
  const endOf = (key: string) => {
    const end = spans.find((s) => s.key === key)?.endMs;
    return end === undefined || end === null ? null : end - origin;
  };
  const preview = spans.find((s) => s.key === "preview-shown");
  return {
    rows,
    totalMs: lastEnd - origin,
    byGroup,
    idleMs,
    previewOnScreenMs: preview?.note?.startsWith("failed") || preview?.note?.startsWith("cancelled") ? null : endOf("preview-shown"),
    fullOnScreenMs: endOf("after-gpu"),
  };
}

function s(ms: number | null): string {
  return ms === null ? "…" : `${(ms / 1000).toFixed(1)} s`;
}

/** The log as plain text, for pasting into a note or a conversation. */
export function timelineText(spans: TimelineSpan[], now = Date.now()): string {
  const { rows, totalMs, byGroup, idleMs, previewOnScreenMs, fullOnScreenMs } = summarise(spans, now);
  if (rows.length === 0) return "no timings recorded";
  const lines = rows.map((r) => {
    const indent = r.span.parent ? "    ↳ " : "";
    const idle = r.idleBeforeMs >= 500 ? `   (idle ${s(r.idleBeforeMs)} before)` : "";
    const bytes = r.span.bytes ? ` · ${(r.span.bytes / 1e6).toFixed(1)} MB` : "";
    const rate = r.rate !== null ? ` · ${r.rate.toFixed(1)} MB/s` : "";
    const note = r.span.note ? ` · ${r.span.note}` : "";
    const derived = r.span.derived ? " [service clock]" : "";
    return `+${s(r.offsetMs).padStart(7)}  ${s(r.durationMs).padStart(8)}  ${indent}${r.span.label}${bytes}${rate}${note}${derived}${idle}`;
  });
  const groups = (Object.keys(byGroup) as SpanGroup[]).map((g) => `${GROUP_LABEL[g]} ${s(byGroup[g]!)}`);
  const milestones = [
    previewOnScreenMs !== null ? `preview on screen +${s(previewOnScreenMs)}` : null,
    fullOnScreenMs !== null ? `full cloud on screen +${s(fullOnScreenMs)}` : null,
  ].filter(Boolean);
  return [
    ...lines,
    `total ${s(totalMs)} · ${groups.join(" · ")}${idleMs >= 500 ? ` · idle ${s(idleMs)}` : ""}`,
    ...(milestones.length ? [milestones.join(" · ")] : []),
  ].join("\n");
}
