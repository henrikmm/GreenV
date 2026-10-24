/**
 * The demo site's half of a capture: follow what the phone sent, show its frames, and put its
 * point cloud on screen — with every step done by the server and only read here.
 *
 * The local studio drives the pipeline itself (load, extract, run; `phone-auto.ts`). On the demo
 * site the GreenV video API does all of that, so this module only polls two public reads:
 *
 * - `GET captures?limit=50` every two seconds. The newest capture becomes the active one, the
 *   same way loading a capture makes it active locally, so the Demo panel reads it unchanged —
 *   unless someone picked an older one from the panel's history, which then stays on screen
 *   until they go back to live (`select`).
 * - `GET captures/<id>/run` once the server has extracted frames, until the manifest arrives.
 *   The frames become the panel's thumbnails; the manifest becomes a depth field and is handed
 *   to DA3 Depth's output, which is where a live run's result goes, so Run Source, the point
 *   cloud and both viewers pick it up through the graph exactly as they would a run made here.
 * - `GET examples` once, and `GET examples/<id>/run` when one is chosen: recorded runs kept in
 *   the bucket so the stand has something to show while no phone is capturing, or when a live
 *   capture fails. An example stays on screen until "ao vivo" is chosen or a new capture arrives.
 *
 * ## What "fetched" means here, and the stage that replaces it
 *
 * The server writes `fetched` when the artifacts are published and signed — before any browser
 * has downloaded them. A 60–100 MB npz then still has to come down and become a cloud, which is
 * measured here as `shown`: the server's `fetched` time plus how long this page took from seeing
 * the manifest to having the cloud built. Adding a local duration to a server timestamp keeps the
 * figure free of clock skew between the two machines; the up-to-two-second poll gap before the
 * manifest is seen is not counted.
 */

import { useSyncExternalStore } from "react";
import { getGraph, publishNodeOutputs, runAuto, setNodeParams } from "../graph/graph-store";
import { FIXTURE_RUN_ID, POINT_CLOUD_ID } from "../graph/nodes";
import { depthFieldFromRun } from "../measurement/depth-field";
import type { InferManifest } from "./contract";
import { DEMO_API_BASE, HOSTED, demoDeleteCapture, demoGet } from "./hosted";
import { artifactUrl, manifestFromWire } from "./infer-client";
import { FRAME_SOURCE_ID } from "./load-clip";
import { followPhoneCapture, listPhoneCaptures, type PhoneCapture, type PhoneEvent } from "./phone-link";
import { update } from "./session-store";

export const POLL_MS = 2000;

/** The page's own phase: the cloud is built and on screen. Never written to the server. */
export const SHOWN_PHASE = "shown";

/** Phases at or after which the server has frames to hand out. */
const AFTER_EXTRACTION = new Set([
  "extracted",
  "sampled",
  "uploading-frames",
  "waking",
  "loading-model",
  "inferring",
  "inferred",
  "fetched",
  "measured",
]);

export interface HostedRun {
  /** Signed frame URLs, in the order they were sent to the model. Empty before `extracted`. */
  frames: string[];
  /** Null until the server has published the artifacts (`fetched`). */
  manifest: InferManifest | null;
  gpu: string | null;
  gpuSeconds: number | null;
}

export function parseRun(body: unknown): HostedRun {
  const b = (body ?? {}) as { frames?: unknown; manifest?: unknown; gpu?: unknown; gpuSeconds?: unknown };
  return {
    frames: Array.isArray(b.frames) ? b.frames.filter((f): f is string => typeof f === "string") : [],
    manifest: b.manifest ? manifestFromWire(b.manifest) : null,
    gpu: typeof b.gpu === "string" ? b.gpu : null,
    gpuSeconds: typeof b.gpuSeconds === "number" ? b.gpuSeconds : null,
  };
}

export async function fetchHostedRun(base: string, id: string, fetchImpl: typeof fetch = fetch): Promise<HostedRun> {
  return parseRun(await demoGet(base, `captures/${encodeURIComponent(id)}/run`, fetchImpl));
}

/** A recorded run the API serves from `demo/examples/` (index.json), shown on request. */
export interface HostedExample {
  id: string;
  title: string;
  detail: string | null;
  frames: number;
  gpu: string | null;
  gpuSeconds: number | null;
  /** The operator's tape reading, where one was taken. */
  truth: string | null;
}

const text = (v: unknown): string | null => (typeof v === "string" && v.trim() ? v : null);

export function parseExamples(body: unknown): HostedExample[] {
  const list = (body as { examples?: unknown } | null)?.examples;
  if (!Array.isArray(list)) return [];
  return list.flatMap((raw): HostedExample[] => {
    const e = (raw ?? {}) as Record<string, unknown>;
    const id = text(e.id);
    if (!id) return [];
    return [
      {
        id,
        title: text(e.title) ?? id,
        detail: text(e.detail),
        frames: typeof e.frames === "number" ? e.frames : 0,
        gpu: text(e.gpu),
        gpuSeconds: typeof e.gpuSeconds === "number" ? e.gpuSeconds : null,
        truth: text(e.truth),
      },
    ];
  });
}

export async function fetchExamples(base: string, fetchImpl: typeof fetch = fetch): Promise<HostedExample[]> {
  return parseExamples(await demoGet(base, "examples", fetchImpl));
}

export async function fetchExampleRun(base: string, id: string, fetchImpl: typeof fetch = fetch): Promise<HostedRun> {
  return parseRun(await demoGet(base, `examples/${encodeURIComponent(id)}/run`, fetchImpl));
}

/** The active id an example takes, so it can never collide with a capture's. */
export function exampleActiveId(id: string): string {
  return `example:${id}`;
}

export function startedAt(capture: PhoneCapture): number {
  const t = Date.parse(capture.events[0]?.at ?? capture.capturedAt ?? "");
  return Number.isFinite(t) ? t : Number.NEGATIVE_INFINITY;
}

/**
 * The capture to follow: the one created last. Ids are not assumed to sort by time, so the
 * first event decides; on a tie, or with no times at all, the API's own order (newest first)
 * does.
 */
export function newestCapture(captures: readonly PhoneCapture[]): PhoneCapture | null {
  let best: PhoneCapture | null = null;
  for (const capture of captures) {
    if (!best || startedAt(capture) > startedAt(best)) best = capture;
  }
  return best;
}

export function lastPhase(capture: PhoneCapture): string | null {
  return capture.events.at(-1)?.phase ?? null;
}

export function hasFrames(capture: PhoneCapture): boolean {
  return capture.events.some((e) => AFTER_EXTRACTION.has(e.phase));
}

export interface HostedShown {
  /** From seeing the manifest to the cloud being built, measured on this page. */
  ms: number;
  error: string | null;
}

/**
 * The capture's timeline as the Demo panel should read it: the server's phases, then this
 * page's `shown` — or a `failed` naming the download, when the cloud could not be built.
 */
export function displayEvents(capture: PhoneCapture, shown: HostedShown | null): PhoneEvent[] {
  const fetched = capture.events.find((e) => e.phase === "fetched");
  if (!shown || !fetched) return capture.events;
  const tMs = fetched.tMs + shown.ms;
  // An unparseable server time must not throw out of a render: keep the server's own string.
  const fetchedAt = Date.parse(fetched.at);
  const at = Number.isFinite(fetchedAt) ? new Date(fetchedAt + shown.ms).toISOString() : fetched.at;
  return [
    ...capture.events,
    shown.error === null
      ? { phase: SHOWN_PHASE, at, tMs, detail: { ms: shown.ms } }
      : { phase: "failed", at, tMs, detail: { error: shown.error, failedIn: "loading" } },
  ];
}

export interface HostedDemoState {
  captures: PhoneCapture[];
  activeId: string | null;
  /** A capture chosen from the history, shown instead of the newest. Null follows live. */
  pinnedId: string | null;
  /** The recorded examples the API offers; empty until (and unless) it answers. */
  examples: HostedExample[];
  /** The example on screen instead of any capture. Null shows captures. */
  exampleId: string | null;
  /** The active capture's frame URLs, kept from the first answer that had them. */
  frames: string[];
  manifest: InferManifest | null;
  shown: HostedShown | null;
  /** The last read that failed, cleared by the next one that succeeds. */
  error: string | null;
}

export interface HostedDemoDeps {
  listCaptures: () => Promise<PhoneCapture[]>;
  fetchRun: (id: string) => Promise<HostedRun>;
  /** A new capture is now the active one: point the app at it, dropping the previous result. */
  follow: (capture: PhoneCapture) => void;
  /** Build the cloud for a finished run. Resolves once it is on screen. */
  show: (target: { id: string }, manifest: InferManifest, frames: string[]) => Promise<void>;
  /** Deletes a capture at the API with the code the operator typed. Rejects with the reason. */
  deleteCapture: (id: string, code: string) => Promise<void>;
  listExamples?: () => Promise<HostedExample[]>;
  fetchExampleRun?: (id: string) => Promise<HostedRun>;
  /** An example is now the active one: point the app at it, as `follow` does for a capture. */
  followExample?: (example: HostedExample) => void;
  now?: () => number;
}

const INITIAL: HostedDemoState = {
  captures: [],
  activeId: null,
  pinnedId: null,
  examples: [],
  exampleId: null,
  frames: [],
  manifest: null,
  shown: null,
  error: null,
};

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * The poller, without timers or globals, so its choices can be driven step by step in a test.
 * `tick` is one poll; the loop that repeats it lives in `startHostedDemo`.
 */
export function createHostedDemo(deps: HostedDemoDeps) {
  const now = deps.now ?? (() => performance.now());
  let state: HostedDemoState = INITIAL;
  const listeners = new Set<() => void>();
  let showing: Promise<void> = Promise.resolve();
  /** A failed capture's run is read once, for whatever frames it got to, and then left alone. */
  let readAfterFailure = false;
  let examplesAsked = false;
  /** The newest capture when an example was chosen: a different one means a phone just sent. */
  let newestAtExample: string | null = null;

  function set(patch: Partial<HostedDemoState>): void {
    state = { ...state, ...patch };
    for (const listener of listeners) listener();
  }

  function wantsRun(capture: PhoneCapture): boolean {
    if (state.manifest || !hasFrames(capture)) return false;
    return !(lastPhase(capture) === "failed" && readAfterFailure);
  }

  /** Builds the cloud for whatever is active under `id`, timing it as the page's `shown`. */
  function present(id: string, manifest: InferManifest, frames: string[]): void {
    set({ manifest });
    const started = now();
    showing = deps.show({ id }, manifest, frames).then(
      () => {
        if (state.activeId === id) set({ shown: { ms: Math.round(now() - started), error: null } });
      },
      (e) => {
        if (state.activeId === id) set({ shown: { ms: Math.round(now() - started), error: message(e) } });
      },
    );
  }

  function askForExamples(): void {
    if (examplesAsked || !deps.listExamples) return;
    examplesAsked = true;
    deps.listExamples().then(
      (examples) => set({ examples }),
      // An API without the route, or a slow answer, leaves the list empty; ask again next poll.
      () => {
        examplesAsked = false;
      },
    );
  }

  async function tick(): Promise<void> {
    askForExamples();
    let captures: PhoneCapture[];
    try {
      captures = await deps.listCaptures();
    } catch (e) {
      set({ error: message(e) });
      return;
    }
    set({ captures, error: null });

    if (state.exampleId) {
      if ((newestCapture(captures)?.id ?? null) === newestAtExample) return;
      // A phone has just sent something: that is what the audience is waiting for.
      set({ exampleId: null, pinnedId: null });
    }

    const pinned = state.pinnedId ? (captures.find((c) => c.id === state.pinnedId) ?? null) : null;
    // A pinned capture the server no longer lists (cleaned up after three days, or pushed past
    // the list's limit) cannot be shown any more; going back to live beats an empty panel.
    if (state.pinnedId && !pinned) set({ pinnedId: null });
    const target = pinned ?? newestCapture(captures);
    if (target && target.id !== state.activeId) {
      set({ activeId: target.id, frames: [], manifest: null, shown: null });
      readAfterFailure = false;
      deps.follow(target);
    }

    const active = captures.find((c) => c.id === state.activeId);
    if (!active || !wantsRun(active)) return;

    let run: HostedRun;
    try {
      run = await deps.fetchRun(active.id);
    } catch (e) {
      set({ error: message(e) });
      return;
    }
    // A newer capture may have taken over while this read was in flight.
    if (state.activeId !== active.id) return;
    if (lastPhase(active) === "failed") readAfterFailure = true;

    // Kept from the first answer: every answer re-signs the links, and swapping them would
    // reload every thumbnail on screen every two seconds.
    if (state.frames.length === 0 && run.frames.length > 0) set({ frames: run.frames });

    if (run.manifest && !state.manifest) {
      present(active.id, run.manifest, run.frames.length > 0 ? run.frames : state.frames);
    }
  }

  /**
   * Put a recorded example on screen, or `null` to go back to the captures. Its frames and cloud
   * replace the capture's the way choosing from the history does.
   */
  async function showExample(id: string | null): Promise<void> {
    if (id === null) {
      if (state.exampleId === null) return;
      set({ exampleId: null });
      return tick();
    }
    const example = state.examples.find((e) => e.id === id);
    if (!example || !deps.fetchExampleRun || id === state.exampleId) return;
    newestAtExample = newestCapture(state.captures)?.id ?? null;
    const activeId = exampleActiveId(id);
    set({ exampleId: id, pinnedId: null, activeId, frames: [], manifest: null, shown: null, error: null });
    deps.followExample?.(example);
    let run: HostedRun;
    try {
      run = await deps.fetchExampleRun(id);
    } catch (e) {
      if (state.activeId === activeId) set({ shown: { ms: 0, error: message(e) } });
      return;
    }
    if (state.activeId !== activeId) return;
    set({ frames: run.frames });
    if (!run.manifest) {
      set({ shown: { ms: 0, error: "o exemplo não tem nuvem publicada" } });
      return;
    }
    present(activeId, run.manifest, run.frames);
  }

  /**
   * Show one capture from the history, or `null` to follow the newest again. Polls at once, so
   * a click answers now rather than at the next two-second tick.
   */
  function select(id: string | null): Promise<void> {
    if (id === state.pinnedId && state.exampleId === null) return Promise.resolve();
    set({ pinnedId: id, exampleId: null });
    return tick();
  }

  /**
   * Deletes a capture, then polls so the list and the screen catch up at once. Deleting the one
   * on screen moves to the newest, as a capture cleaned up by the server does. Rejects, with the
   * API's reason, when the delete was refused; nothing changes here then.
   */
  async function remove(id: string, code: string): Promise<void> {
    await deps.deleteCapture(id, code);
    set({
      captures: state.captures.filter((c) => c.id !== id),
      ...(state.pinnedId === id ? { pinnedId: null } : {}),
    });
    await tick();
  }

  return {
    tick,
    select,
    remove,
    showExample,
    getState: (): HostedDemoState => state,
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    /** Settles once the cloud started by the last tick is built, or has failed to be. */
    idle: (): Promise<void> => showing,
  };
}

export type HostedDemo = ReturnType<typeof createHostedDemo>;

// ---------------------------------------------------------------------------------------------
// The app's instance

/**
 * A new identity on Frame Source restamps DA3 Depth and everything after it, so the previous
 * visitor's cloud goes stale the moment a new capture arrives instead of standing in for it —
 * the same thing loading a capture does locally. No path: nothing here will ever run ffmpeg.
 */
function followInGraph(capture: PhoneCapture): void {
  followPhoneCapture(capture.id);
  setNodeParams(FRAME_SOURCE_ID, {
    videoPath: "",
    videoName: capture.id,
    videoSha256: `demo:${capture.id}`,
    durationS: (capture.durationMs ?? 0) / 1000,
  });
  update({ lastRun: null });
  void runAuto();
}

/** As `followInGraph`, for a recorded example: a new identity, so the last cloud goes stale. */
function followExampleInGraph(example: HostedExample): void {
  setNodeParams(FRAME_SOURCE_ID, {
    videoPath: "",
    videoName: example.title,
    videoSha256: exampleActiveId(example.id),
    durationS: 0,
  });
  update({ lastRun: null });
  void runAuto();
}

async function showInGraph(_target: { id: string }, manifest: InferManifest, frames: string[]): Promise<void> {
  const field = depthFieldFromRun(manifest, { rgbUrls: frames });
  const preview = manifest.artifacts.find((a) => a.kind === "depth_preview");
  publishNodeOutputs("da3-depth", {
    depth: {
      type: "depth_field",
      value: field,
      // As a live run does it (`da3-depth.ts`): the preview or nothing. Never a frame link — the
      // node card's plain <img> would load it without CORS, and a bucket answer cached that way
      // can then fail the CORS reads of the same link that colour the cloud and draw Depth 2D.
      thumbnailUrl: preview?.url ? artifactUrl(preview.url) : undefined,
      summary: `${manifest.frames.count}f · ${manifest.timing.gpuSeconds.toFixed(1)}s GPU${manifest.mock ? " · MOCK" : ""}`,
    },
  });
  update({ lastRun: manifest });
  setNodeParams(FIXTURE_RUN_ID, { source: "live" });
  const report = await runAuto();
  // Measurement nodes may legitimately fail on a scene with no floor; only the cloud itself
  // decides whether this capture is on screen.
  const failed = report.failed.find((id) => id === FIXTURE_RUN_ID || id === POINT_CLOUD_ID);
  if (failed) throw new Error(getGraph().runtime[failed]?.error ?? "the point cloud could not be built");
}

let instance: HostedDemo | null = null;
let polling = false;

export function hostedDemo(): HostedDemo {
  instance ??= createHostedDemo({
    listCaptures: listPhoneCaptures,
    fetchRun: (id) => fetchHostedRun(DEMO_API_BASE, id),
    follow: followInGraph,
    show: showInGraph,
    deleteCapture: (id, code) => demoDeleteCapture(DEMO_API_BASE, id, code),
    listExamples: () => fetchExamples(DEMO_API_BASE),
    fetchExampleRun: (id) => fetchExampleRun(DEMO_API_BASE, id),
    followExample: followExampleInGraph,
  });
  return instance;
}

/** Start the two-second poll once, and only in a hosted build. */
export function startHostedDemo(): void {
  if (polling || !HOSTED || typeof window === "undefined") return;
  polling = true;
  const demo = hostedDemo();
  const loop = async () => {
    try {
      await demo.tick();
    } catch (error) {
      // `tick` catches its reads; this is for anything else, which must not end the poll.
      console.warn("[hosted] poll failed:", error);
    } finally {
      window.setTimeout(loop, POLL_MS);
    }
  };
  void loop();
}

export function selectHostedCapture(id: string | null): void {
  void hostedDemo().select(id);
}

export function showHostedExample(id: string | null): void {
  void hostedDemo().showExample(id);
}

export function deleteHostedCapture(id: string, code: string): Promise<void> {
  return hostedDemo().remove(id, code);
}

export function useHostedDemo(): HostedDemoState {
  startHostedDemo();
  const demo = hostedDemo();
  return useSyncExternalStore(demo.subscribe, demo.getState, demo.getState);
}
