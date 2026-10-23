/**
 * The timing log is only useful if the stretches it draws are the ones that happened. These
 * drive it with run-phase states on a fake clock and pin four claims:
 *
 * 1. Each run phase opens and closes exactly one span, and waking/loading/inferring fold into
 *    the single open request.
 * 2. The service's own numbers are placed inside that request, ending when the response arrived.
 * 3. Only this run's artifacts are timed; anything else passes through untouched.
 * 4. Totals count top-level spans once, and idle stretches are reported, not hidden.
 */

import { beforeEach, describe, expect, it } from "vitest";
import type { InferManifest } from "./contract";
import { getRunPhase, type RunPhaseState } from "./run-phase";
import {
  followRunPhase,
  getTimeline,
  noteManifest,
  notePhoneUpload,
  putSpan,
  resetTimeline,
  summarise,
  timeArtifact,
  timelineText,
} from "./run-timeline";

function phase(kind: RunPhaseState["kind"], patch: Partial<RunPhaseState> = {}): RunPhaseState {
  return { ...getRunPhase(), kind, ...patch };
}

function manifest(patch: { wall: number; gpu: number; urls?: string[] }): InferManifest {
  return {
    frames: { count: 112, requestedCount: 112, width: 280, height: 504, capped: false },
    timing: { gpuSeconds: patch.gpu, wallSeconds: patch.wall, modelLoadSeconds: 34.2 },
    vram: { deviceName: "NVIDIA RTX PRO 6000" },
    artifacts: (patch.urls ?? ["/artifact/r/scene.glb"]).map((url) => ({ kind: "glb", name: "scene.glb", sizeBytes: 1, sha256: "", url })),
  } as unknown as InferManifest;
}

function span(key: string) {
  return getTimeline().find((s) => s.key === key);
}

beforeEach(() => {
  resetTimeline();
  followRunPhase(phase("idle"), 0);
});

describe("run phases", () => {
  it("opens one span per stretch and folds the server phases into the open request", () => {
    followRunPhase(phase("reading"), 1_000);
    followRunPhase(phase("uploading", { upload: { sentBytes: 0, totalBytes: 9_000_000 } }), 1_400);
    followRunPhase(phase("waking"), 4_400);
    followRunPhase(phase("loadingModel"), 10_400);
    followRunPhase(phase("inferring"), 40_400);
    followRunPhase(phase("fetching"), 50_400);
    followRunPhase(phase("done"), 60_400);

    expect(span("read-frames")).toMatchObject({ startMs: 1_000, endMs: 1_400 });
    expect(span("upload-frames")).toMatchObject({ startMs: 1_400, endMs: 4_400, bytes: 9_000_000 });
    expect(span("server-wait")).toMatchObject({ startMs: 4_400, endMs: 50_400 });
    expect(span("server-wait")?.note).toBe("poll saw: nothing answering 6.0 s · model loading 30.0 s · busy 10.0 s");
    expect(span("after-gpu")).toMatchObject({ startMs: 50_400, endMs: 60_400 });
    expect(getTimeline().filter((s) => s.key === "server-wait")).toHaveLength(1);
  });

  it("keeps the clip's spans across a new run and drops the previous run's", () => {
    putSpan({ key: "extract", label: "extract", group: "mac", startMs: 0, endMs: 500 });
    followRunPhase(phase("reading"), 1_000);
    followRunPhase(phase("done"), 2_000);
    followRunPhase(phase("reading"), 9_000);
    expect(span("extract")).toBeDefined();
    expect(span("read-frames")).toMatchObject({ startMs: 9_000, endMs: null });
  });

  it("marks the span a failure happened in", () => {
    followRunPhase(phase("reading"), 0);
    followRunPhase(phase("uploading", { upload: { sentBytes: 0, totalBytes: 1 } }), 100);
    followRunPhase(phase("failed", { failedIn: "uploading", message: "network error" }), 900);
    expect(span("upload-frames")).toMatchObject({ endMs: 900, note: "failed: network error" });
  });
});

describe("the service's clock", () => {
  it("places wall and forward pass inside the request, ending at the response", () => {
    followRunPhase(phase("reading"), 0);
    followRunPhase(phase("waking"), 1_000);
    noteManifest(manifest({ wall: 12, gpu: 4 }), (u) => `https://svc${u}`, 25_000);

    expect(span("server-outside")).toMatchObject({ parent: "server-wait", startMs: 1_000, endMs: 13_000, derived: true });
    expect(span("server-forward")).toMatchObject({ startMs: 13_000, endMs: 17_000 });
    expect(span("server-other")).toMatchObject({ startMs: 17_000, endMs: 25_000 });
    expect(span("server-load")).toBeUndefined();
  });

  it("puts a model load first only when the poll saw one", () => {
    followRunPhase(phase("reading"), 0);
    followRunPhase(phase("waking"), 1_000);
    followRunPhase(phase("loadingModel"), 2_000);
    followRunPhase(phase("inferring"), 30_000);
    noteManifest(manifest({ wall: 45, gpu: 4 }), (u) => u, 50_000);
    // wall starts at 5 000; the manifest says this instance loaded in 34.2 s
    expect(span("server-load")).toMatchObject({ startMs: 5_000, endMs: 39_200 });
    expect(span("server-forward")).toMatchObject({ startMs: 39_200, endMs: 43_200 });
    expect(span("server-other")).toMatchObject({ startMs: 43_200, endMs: 50_000 });
  });

  it("keeps the preview's rows when the manifest lands", () => {
    followRunPhase(phase("reading"), 0);
    followRunPhase(phase("waking"), 1_000);
    putSpan({ key: "preview-shown", parent: "server-wait", label: "preview", group: "browser", startMs: 9_000, endMs: 9_500 });
    noteManifest(manifest({ wall: 12, gpu: 4 }), (u) => u, 25_000);
    noteManifest(manifest({ wall: 12, gpu: 4 }), (u) => u, 25_000);
    expect(span("preview-shown")).toBeDefined();
    expect(getTimeline().filter((s) => s.key === "server-forward")).toHaveLength(1);
  });

  it("never starts the breakdown before the request opened", () => {
    followRunPhase(phase("reading"), 0);
    followRunPhase(phase("waking"), 10_000);
    noteManifest(manifest({ wall: 30, gpu: 4 }), (u) => u, 20_000);
    expect(span("server-outside")).toMatchObject({ startMs: 10_000, endMs: 10_000 });
    expect(span("server-forward")).toMatchObject({ startMs: 10_000, endMs: 14_000 });
    expect(span("server-other")).toMatchObject({ startMs: 14_000, endMs: 20_000 });
    expect(span("server-other")?.note).toBe("reported 30.0 s, longer than the request");
  });

  it("clamps a forward pass longer than the request, as the mock reports", () => {
    followRunPhase(phase("reading"), 0);
    followRunPhase(phase("waking"), 1_000);
    noteManifest(manifest({ wall: 203, gpu: 202 }), (u) => u, 7_000);
    expect(span("server-forward")).toMatchObject({ startMs: 1_000, endMs: 7_000 });
    expect(span("server-other")).toMatchObject({ startMs: 7_000, endMs: 7_000 });
  });
});

describe("artifacts", () => {
  it("times this run's downloads and passes anything else through", async () => {
    noteManifest(manifest({ wall: 1, gpu: 1, urls: ["/artifact/r/scene.glb"] }), (u) => `https://svc${u}`, 0);
    const buffer = new ArrayBuffer(2_000_000);

    await timeArtifact("https://svc/artifact/r/scene.glb", "scene.glb", async () => buffer, (b) => b.byteLength);
    expect(span("download-scene.glb")).toMatchObject({ parent: "after-gpu", bytes: 2_000_000, group: "network" });
    expect(span("parse-scene.glb")?.endMs).not.toBeNull();

    const abort = Object.assign(new Error("signal is aborted without reason"), { name: "AbortError" });
    await expect(
      timeArtifact("https://svc/artifact/r/scene.glb", "scene.glb", async () => buffer, () => {
        throw abort;
      }),
    ).rejects.toBe(abort);
    expect(span("download-scene.glb#2")?.label).toBe("download scene.glb (attempt 2)");
    expect(span("parse-scene.glb#2")?.note).toBe("cancelled: a newer build replaced it");
    expect(span("parse-scene.glb")?.note).toBeUndefined();

    resetTimeline();
    const parsed = await timeArtifact("/fixtures/roadside/scene.glb", "scene.glb", async () => buffer, (b) => b.byteLength);
    expect(parsed).toBe(2_000_000);
    expect(getTimeline()).toHaveLength(0);
  });
});

describe("reading it", () => {
  it("counts top-level spans once and reports idle gaps", () => {
    notePhoneUpload(
      [
        { phase: "uploading", at: new Date(0).toISOString() },
        { phase: "received", at: new Date(8_000).toISOString() },
      ],
      35_000_000,
    );
    putSpan({ key: "extract", label: "extract frames", group: "mac", startMs: 10_000, endMs: 12_000 });
    putSpan({ key: "server-wait", label: "request", group: "gpu", startMs: 12_000, endMs: 30_000 });
    putSpan({ key: "server-forward", parent: "server-wait", label: "forward", group: "gpu", startMs: 26_000, endMs: 30_000, derived: true });

    const summary = summarise(getTimeline(), 30_000);
    expect(summary.totalMs).toBe(30_000);
    expect(summary.byGroup).toEqual({ network: 8_000, mac: 2_000, gpu: 18_000 });
    expect(summary.idleMs).toBe(2_000);
    expect(summary.rows.map((r) => r.span.key)).toEqual(["phone-upload", "extract", "server-wait", "server-forward"]);
    expect(summary.rows[0].rate).toBeCloseTo(4.375);
    expect(summary.rows[1].idleBeforeMs).toBe(2_000);

    const text = timelineText(getTimeline(), 30_000);
    expect(text).toContain("phone → Mac: video upload · 35.0 MB · 4.4 MB/s");
    expect(text).toContain("↳ forward [service clock]");
    expect(text.split("\n").at(-1)).toBe("total 30.0 s · network 8.0 s · this Mac 2.0 s · GPU service 18.0 s · idle 2.0 s");
    expect(summary.previewOnScreenMs).toBeNull();
  });

  it("reports when the preview and the full cloud reached the screen", () => {
    putSpan({ key: "server-wait", label: "request", group: "gpu", startMs: 0, endMs: 20_000 });
    putSpan({ key: "preview-shown", parent: "server-wait", label: "preview", group: "browser", startMs: 11_000, endMs: 11_500 });
    putSpan({ key: "after-gpu", label: "views", group: "browser", startMs: 20_000, endMs: 31_000 });
    const summary = summarise(getTimeline(), 31_000);
    expect(summary.previewOnScreenMs).toBe(11_500);
    expect(summary.fullOnScreenMs).toBe(31_000);
    expect(timelineText(getTimeline(), 31_000).split("\n").at(-1)).toBe("preview on screen +11.5 s · full cloud on screen +31.0 s");
  });
});
