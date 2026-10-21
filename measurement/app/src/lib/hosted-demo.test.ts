import { describe, expect, it, vi } from "vitest";
import type { InferManifest } from "./contract";
import { demoUrl, parseCaptureList, withSlash } from "./hosted";
import {
  SHOWN_PHASE,
  createHostedDemo,
  displayEvents,
  fetchHostedRun,
  newestCapture,
  parseRun,
  type HostedRun,
} from "./hosted-demo";
import type { PhoneCapture, PhoneEvent } from "./phone-link";

/**
 * The demo site follows a server it cannot control, so what it chooses to show is the whole
 * behaviour: which capture, when to ask for its run, and what to do with what comes back. Every
 * case here is a poll the API can really produce, driven one tick at a time with no timers.
 */

const BASE = "https://greenvapi.example/v1/demo/";
const T0 = "2026-10-24T13:00:00.000Z";

function event(phase: string, tMs: number, detail?: Record<string, unknown>): PhoneEvent {
  return { phase, at: new Date(Date.parse(T0) + tMs).toISOString(), tMs, ...(detail ? { detail } : {}) };
}

function capture(id: string, phases: string[], startOffsetMs = 0): PhoneCapture {
  const start = Date.parse(T0) + startOffsetMs;
  return {
    id,
    state: phases.at(-1) ?? "created",
    capturedAt: new Date(start).toISOString(),
    durationMs: 12_000,
    settings: null,
    applied: null,
    orbit: null,
    truth: null,
    sampling: null,
    video: null,
    events: phases.map((phase, i) => ({ phase, at: new Date(start + i * 1000).toISOString(), tMs: i * 1000 })),
  };
}

const UP_TO_EXTRACTED = ["created", "uploading", "received", "extracting", "extracted"];
const FETCHED = [...UP_TO_EXTRACTED, "uploading-frames", "waking", "inferring", "inferred", "fetched"];

/** The manifest exactly as the server sends it: snake_case, absolute signed artifact links. */
function wireManifest(frames = 3) {
  return {
    schema_version: "1",
    run_id: "run-1",
    model_repository_id: "depth-anything/DA3NESTED-GIANT-LARGE-1.1",
    model_revision: "b2359bdf726fb44ef62acca04d629dcf158053e7",
    depth_mode: "metric",
    linear_unit: "metre",
    params: { fps: 10, process_res: 504, process_res_method: "upper_bound_resize", ref_view_strategy: "middle", max_frames: 112 },
    frames: { count: frames, requested_count: frames, width: 504, height: 896, capped: false, effective_fps: 10 },
    timing: { gpu_seconds: 4.2, wall_seconds: 8.1 },
    vram: { peak_bytes: 1, current_bytes: 1, total_bytes: 2, device_name: "NVIDIA RTX PRO 6000" },
    artifacts: [
      {
        kind: "npz",
        name: "result.npz",
        size_bytes: 1000,
        sha256: "00",
        url: "https://acct.r2.cloudflarestorage.com/b/demo/c1/depth/run-1/result.npz?X-Amz-Signature=ab",
      },
    ],
    transient: true,
    expires_after_days: 3,
  };
}

function frameUrls(n: number, signature = "s1"): string[] {
  return Array.from(
    { length: n },
    (_, i) => `https://acct.r2.cloudflarestorage.com/b/demo/c1/sampled-frames/frame-${String(i + 1).padStart(4, "0")}.jpg?X-Amz-Signature=${signature}`,
  );
}

/** A fake API whose answers the test changes between ticks. */
function harness() {
  const api = {
    captures: [] as PhoneCapture[],
    runs: new Map<string, HostedRun>(),
    listFails: false,
  };
  const followed: string[] = [];
  const shown: { id: string; manifest: InferManifest; frames: string[] }[] = [];
  let showResult: () => Promise<void> = () => Promise.resolve();
  let clock = 0;
  const fetchRun = vi.fn(async (id: string) => api.runs.get(id) ?? { frames: [], manifest: null, gpu: null, gpuSeconds: null });
  const demo = createHostedDemo({
    listCaptures: async () => {
      if (api.listFails) throw new Error("demo API captures?limit=20: 502");
      return api.captures;
    },
    fetchRun,
    follow: (c) => followed.push(c.id),
    show: (c, manifest, frames) => {
      shown.push({ id: c.id, manifest, frames });
      clock += 1500; // the npz download and the cloud build
      return showResult();
    },
    now: () => clock,
  });
  return {
    api,
    demo,
    followed,
    shown,
    fetchRun,
    failShow: (message: string) => {
      showResult = () => Promise.reject(new Error(message));
    },
  };
}

describe("hosted capture list", () => {
  it("joins routes onto a base with or without its trailing slash", () => {
    expect(withSlash("https://h/v1/demo")).toBe("https://h/v1/demo/");
    expect(demoUrl("https://h/v1/demo", "captures?limit=20")).toBe("https://h/v1/demo/captures?limit=20");
    expect(demoUrl(BASE, "/health")).toBe(`${BASE}health`);
    expect(() => demoUrl("", "health")).toThrow(/VITE_DEMO_API_BASE/);
  });

  it("accepts a bare array and the local intake's { captures } envelope alike", () => {
    const c = capture("c1", ["created"]);
    expect(parseCaptureList([c]).map((x) => x.id)).toEqual(["c1"]);
    expect(parseCaptureList({ captures: [c] }).map((x) => x.id)).toEqual(["c1"]);
    // A capture with no phases yet still renders, with an empty timeline rather than a crash.
    expect(parseCaptureList([{ id: "c2" }])[0].events).toEqual([]);
  });

  it("refuses a body that is neither, instead of rendering it as 'no captures'", () => {
    expect(() => parseCaptureList({ items: [] })).toThrow(/neither an array/);
  });

  it("follows the capture created last, whatever order the ids sort in", () => {
    const older = capture("zzz", ["created"], 0);
    const newer = capture("aaa", ["created"], 60_000);
    expect(newestCapture([older, newer])?.id).toBe("aaa");
    expect(newestCapture([])).toBeNull();
  });

  it("falls back to the API's own order when no capture carries a time", () => {
    const a = { ...capture("a", []), capturedAt: null };
    const b = { ...capture("b", []), capturedAt: null };
    expect(newestCapture([a, b])?.id).toBe("a");
  });
});

describe("hosted run read", () => {
  it("asks the run route of the capture, and turns the wire manifest into the app's", async () => {
    const urls: string[] = [];
    const fetchImpl = (async (url: string) => {
      urls.push(url);
      return new Response(JSON.stringify({ frames: frameUrls(3), manifest: wireManifest(), gpu: "NVIDIA RTX PRO 6000", gpuSeconds: 4.2 }));
    }) as unknown as typeof fetch;

    const run = await fetchHostedRun(BASE, "c 1", fetchImpl);

    expect(urls).toEqual([`${BASE}captures/c%201/run`]);
    expect(run.frames).toHaveLength(3);
    expect(run.manifest?.runId).toBe("run-1");
    expect(run.manifest?.frames.effectiveFps).toBe(10);
    expect(run.manifest?.artifacts[0].sizeBytes).toBe(1000);
    expect(run.gpuSeconds).toBe(4.2);
  });

  it("reads a run with nothing yet as empty, not as an error", () => {
    expect(parseRun({ frames: [], manifest: null })).toEqual({ frames: [], manifest: null, gpu: null, gpuSeconds: null });
  });

  it("says which route failed and how", async () => {
    const fetchImpl = (async () => new Response("no such capture", { status: 404 })) as unknown as typeof fetch;
    await expect(fetchHostedRun(BASE, "c1", fetchImpl)).rejects.toThrow(/captures\/c1\/run: 404/);
  });
});

describe("following the newest capture", () => {
  it("makes the newest capture active at once, and asks for no run before frames exist", async () => {
    const h = harness();
    h.api.captures = [capture("c1", ["created", "uploading"])];

    await h.demo.tick();

    expect(h.followed).toEqual(["c1"]);
    expect(h.demo.getState().activeId).toBe("c1");
    expect(h.fetchRun).not.toHaveBeenCalled();
  });

  it("shows the frames once extracted, and keeps the first links rather than reloading them", async () => {
    const h = harness();
    h.api.captures = [capture("c1", UP_TO_EXTRACTED)];
    h.api.runs.set("c1", { frames: frameUrls(3, "first"), manifest: null, gpu: null, gpuSeconds: null });

    await h.demo.tick();
    expect(h.demo.getState().frames).toEqual(frameUrls(3, "first"));

    // Every answer re-signs the links; the thumbnails must not swap and reload every 2 s.
    h.api.runs.set("c1", { frames: frameUrls(3, "second"), manifest: null, gpu: null, gpuSeconds: null });
    await h.demo.tick();
    expect(h.demo.getState().frames).toEqual(frameUrls(3, "first"));
    expect(h.followed).toEqual(["c1"]);
  });

  it("builds the cloud once when the manifest arrives, then stops asking", async () => {
    const h = harness();
    h.api.captures = [capture("c1", FETCHED)];
    h.api.runs.set("c1", { frames: frameUrls(3), manifest: parseRun({ manifest: wireManifest() }).manifest, gpu: null, gpuSeconds: null });

    await h.demo.tick();
    await h.demo.idle();

    expect(h.shown).toHaveLength(1);
    expect(h.shown[0].frames).toEqual(frameUrls(3));
    expect(h.demo.getState().shown).toEqual({ ms: 1500, error: null });

    await h.demo.tick();
    await h.demo.tick();
    expect(h.fetchRun).toHaveBeenCalledTimes(1);
    expect(h.shown).toHaveLength(1);
  });

  it("records a cloud that could not be built, with the reason", async () => {
    const h = harness();
    h.failShow("signed artifact fetch 403");
    h.api.captures = [capture("c1", FETCHED)];
    h.api.runs.set("c1", { frames: frameUrls(3), manifest: parseRun({ manifest: wireManifest() }).manifest, gpu: null, gpuSeconds: null });

    await h.demo.tick();
    await h.demo.idle();

    expect(h.demo.getState().shown).toEqual({ ms: 1500, error: "signed artifact fetch 403" });
  });

  it("drops the previous capture's result the moment a newer one arrives", async () => {
    const h = harness();
    h.api.captures = [capture("c1", FETCHED)];
    h.api.runs.set("c1", { frames: frameUrls(3), manifest: parseRun({ manifest: wireManifest() }).manifest, gpu: null, gpuSeconds: null });
    await h.demo.tick();
    await h.demo.idle();

    h.api.captures = [capture("c2", ["created"], 60_000), ...h.api.captures];
    await h.demo.tick();

    const state = h.demo.getState();
    expect(h.followed).toEqual(["c1", "c2"]);
    expect(state.activeId).toBe("c2");
    expect(state.frames).toEqual([]);
    expect(state.manifest).toBeNull();
    expect(state.shown).toBeNull();
  });

  it("reads a failed capture's run once, for whatever frames it got to", async () => {
    const h = harness();
    h.api.captures = [capture("c1", [...UP_TO_EXTRACTED, "uploading-frames", "failed"])];
    h.api.runs.set("c1", { frames: frameUrls(2), manifest: null, gpu: null, gpuSeconds: null });

    await h.demo.tick();
    await h.demo.tick();

    expect(h.fetchRun).toHaveBeenCalledTimes(1);
    expect(h.demo.getState().frames).toHaveLength(2);
  });

  it("keeps what it has when a poll fails, and clears the error on the next good one", async () => {
    const h = harness();
    h.api.captures = [capture("c1", ["created"])];
    await h.demo.tick();

    h.api.listFails = true;
    await h.demo.tick();
    expect(h.demo.getState().error).toMatch(/502/);
    expect(h.demo.getState().activeId).toBe("c1");

    h.api.listFails = false;
    await h.demo.tick();
    expect(h.demo.getState().error).toBeNull();
  });
});

describe("browsing the history", () => {
  function finished(h: ReturnType<typeof harness>, id: string, offsetMs: number): PhoneCapture {
    h.api.runs.set(id, { frames: frameUrls(3), manifest: parseRun({ manifest: wireManifest() }).manifest, gpu: null, gpuSeconds: null });
    return capture(id, FETCHED, offsetMs);
  }

  it("shows an older capture when one is picked, and builds its cloud", async () => {
    const h = harness();
    h.api.captures = [finished(h, "new", 60_000), finished(h, "old", 0)];
    await h.demo.tick();
    await h.demo.idle();

    await h.demo.select("old");
    await h.demo.idle();

    const state = h.demo.getState();
    expect(state.activeId).toBe("old");
    expect(state.pinnedId).toBe("old");
    expect(h.followed).toEqual(["new", "old"]);
    expect(h.shown.map((s) => s.id)).toEqual(["new", "old"]);
  });

  it("stays on the picked capture while newer ones arrive, until live is chosen again", async () => {
    const h = harness();
    h.api.captures = [finished(h, "old", 0)];
    await h.demo.tick();
    await h.demo.select("old");

    h.api.captures = [capture("newer", ["created"], 120_000), ...h.api.captures];
    await h.demo.tick();
    expect(h.demo.getState().activeId).toBe("old");

    await h.demo.select(null);
    expect(h.demo.getState().activeId).toBe("newer");
    expect(h.demo.getState().pinnedId).toBeNull();
  });

  it("goes back to live when the picked capture leaves the server's list", async () => {
    const h = harness();
    h.api.captures = [finished(h, "new", 60_000), finished(h, "old", 0)];
    await h.demo.tick();
    await h.demo.select("old");

    h.api.captures = [h.api.captures[0]];
    await h.demo.tick();

    expect(h.demo.getState().pinnedId).toBeNull();
    expect(h.demo.getState().activeId).toBe("new");
  });

  it("does not rebuild the capture already on screen when it is picked again", async () => {
    const h = harness();
    h.api.captures = [finished(h, "c1", 0)];
    await h.demo.tick();
    await h.demo.idle();

    await h.demo.select("c1");
    await h.demo.idle();

    expect(h.followed).toEqual(["c1"]);
    expect(h.shown).toHaveLength(1);
  });
});

describe("the Demo panel's timeline", () => {
  const server = [event("created", 0), event("inferred", 20_000), event("fetched", 24_000)];
  const c = { ...capture("c1", []), events: server };

  it("is the server's own until the cloud is on screen", () => {
    expect(displayEvents(c, null)).toEqual(server);
  });

  it("ends on the page's shown, timed as the server's fetched plus the local build", () => {
    const events = displayEvents(c, { ms: 9_000, error: null });
    const shown = events.at(-1)!;
    expect(shown.phase).toBe(SHOWN_PHASE);
    expect(shown.tMs).toBe(33_000);
    expect(Date.parse(shown.at) - Date.parse(server[2].at)).toBe(9_000);
  });

  it("ends on failed, naming the download, when the cloud could not be built", () => {
    const failed = displayEvents(c, { ms: 2_000, error: "signed artifact fetch 403" }).at(-1)!;
    expect(failed.phase).toBe("failed");
    expect(failed.detail).toEqual({ error: "signed artifact fetch 403", failedIn: "loading" });
  });

  it("keeps the server's time string rather than throwing when it cannot be parsed", () => {
    const odd = { ...c, events: [...server.slice(0, 2), { ...server[2], at: "not a time" }] };
    const shown = displayEvents(odd, { ms: 1_000, error: null }).at(-1)!;
    expect(shown.at).toBe("not a time");
    expect(shown.tMs).toBe(25_000);
  });

  it("adds nothing before the server has published", () => {
    const early = { ...c, events: server.slice(0, 2) };
    expect(displayEvents(early, { ms: 1, error: null })).toEqual(early.events);
  });
});
