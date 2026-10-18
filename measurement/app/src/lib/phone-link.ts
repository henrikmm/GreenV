/**
 * The studio's half of the phone demo: list what the phone sent, load one as the clip, and tell
 * the phone what is happening to it.
 *
 * A capture becomes the *active* one when it is loaded, and stays active until another clip is
 * loaded by any route. While it is active, the phases the studio already tracks — extraction,
 * the run's upload, waking, model load, inference, the artifact fetch — are reported to the
 * gateway, which is where the phone reads them. Nothing here starts work on its own unless the
 * operator turns on Auto; this module only ever reports what the studio was asked to do.
 *
 * Reporting is best-effort. A phase that fails to reach the gateway is logged and dropped: the
 * phone showing a stale phase is better than a run aborting because a status write failed.
 */

import { useSyncExternalStore } from "react";
import { setNodeParams } from "../graph/graph-store";
import { GPU_FRAME_CEILING, GPU_LABEL } from "./cloud-control";
import { activeGpu } from "./deploy-store";
import type { VideoSource } from "./infer-client";
import { localApiHeaders } from "./local-api";
import { getRunPhase, subscribeRunPhase, type PhaseKind } from "./run-phase";

export interface PhoneEvent {
  phase: string;
  at: string;
  tMs: number;
  detail?: Record<string, unknown>;
}

export interface PhoneProbe {
  codec: string | null;
  storedWidth: number | null;
  storedHeight: number | null;
  rotation: number;
  avgFps: number | null;
  videoBitRate: number | null;
  hdr: boolean;
  colorTransfer: string | null;
  durationS: number | null;
}

export interface PhoneCapture {
  id: string;
  state: string;
  capturedAt: string | null;
  durationMs: number | null;
  settings: Record<string, unknown> | null;
  applied: Record<string, unknown> | null;
  orbit: { degrees?: number; peakDegreesPerSecond?: number; tooFastEvents?: number } | null;
  truth: { truthM: number; object: string | null } | null;
  /** How the phone asked for this capture to be sampled; absent on builds before 2026-10-17. */
  sampling: { fps?: number; maxFrames?: number } | null;
  video: { bytes: number; transferMs: number; megabytesPerSecond: number | null; probe: PhoneProbe | null } | null;
  events: PhoneEvent[];
}

export interface PhoneInfo {
  enabled: boolean;
  port: number;
  code: string | null;
  urls: string[];
  inbox: string;
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/phone${path}`, {
    ...init,
    headers: localApiHeaders({ ...(init.body ? { "content-type": "application/json" } : {}) }),
  });
  const body = (await response.json().catch(() => ({}))) as { detail?: string };
  if (!response.ok) throw new Error(body.detail ?? `HTTP ${response.status}`);
  return body as T;
}

export const phoneInfo = () => call<PhoneInfo>("/info");

export const listPhoneCaptures = async () => (await call<{ captures: PhoneCapture[] }>("/captures")).captures;

export const discardPhoneCapture = (id: string) => call(`/captures/${id}`, { method: "DELETE" });

export const setPhoneTruth = (id: string, truthM: number | null, object?: string) =>
  call(`/captures/${id}/truth`, { method: "POST", body: JSON.stringify({ truthM, object }) });

// ---------------------------------------------------------------------------------------------
// The active capture

let activeId: string | null = null;
const listeners = new Set<() => void>();

function setActive(id: string | null): void {
  if (activeId === id) return;
  activeId = id;
  for (const listener of listeners) listener();
}

export function getActivePhoneCapture(): string | null {
  return activeId;
}

export function useActivePhoneCapture(): string | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => activeId,
    () => activeId,
  );
}

/** Called by `loadClip`: a clip dropped by hand is not the phone's, so nothing is reported for it. */
export function clearActivePhoneCapture(): void {
  setActive(null);
}

/**
 * Point Frame Source at a phone capture, exactly as `loadClip` does for a dropped file — same
 * parameters, so the graph, the cache key and the run registry treat it like any other clip.
 */
export async function loadPhoneCapture(
  id: string,
  sampling?: PhoneCapture["sampling"],
): Promise<VideoSource & { plan: string | null }> {
  const source = await call<VideoSource & { captureId: string }>(`/captures/${id}/load`, { method: "POST" });
  // The phone's sampling wins for its own capture, clamped to what the card in use can hold.
  let plan: string | null = null;
  if (sampling?.fps || sampling?.maxFrames) {
    const gpu = activeGpu();
    const ceiling = GPU_FRAME_CEILING[gpu];
    const asked = sampling.maxFrames ?? 112;
    const maxFrames = Math.min(asked, ceiling);
    setNodeParams("frame-source", { ...(sampling.fps ? { fps: sampling.fps } : {}), maxFrames });
    setNodeParams("da3-depth", { maxFrames });
    plan =
      `${sampling.fps ?? "studio"} fps, ≤ ${maxFrames} frames` +
      (maxFrames < asked ? ` (asked ${asked}; the ${GPU_LABEL[gpu]} holds ${ceiling})` : "");
  }
  setNodeParams("frame-source", {
    videoPath: source.path,
    videoName: source.name,
    videoSha256: source.sha256,
    durationS: source.durationS,
    nativeFps: source.nativeFps,
    width: source.width,
    height: source.height,
  });
  setActive(id);
  return { ...source, plan };
}

/** Report one phase for the active capture. Never throws. */
export function reportPhonePhase(phase: string, detail?: Record<string, unknown>): void {
  const id = activeId;
  if (!id) return;
  void call(`/captures/${id}/phase`, { method: "POST", body: JSON.stringify({ phase, detail }) }).catch((error) => {
    console.warn(`[phone] could not report ${phase} for ${id}:`, error);
  });
}

// ---------------------------------------------------------------------------------------------
// Following the run

/** Run phases that have a phone-side name. `reading` is local and too quick to be worth a line. */
const RUN_TO_PHONE: Partial<Record<PhaseKind, string>> = {
  uploading: "uploading-frames",
  waking: "waking",
  loadingModel: "loading-model",
  inferring: "inferring",
  failed: "failed",
};

/**
 * The run phase is read off a poll of the service, and while an instance wakes it can read
 * "loading model", then "nothing answering" again, then "loading model". The phone shows only
 * forward progress: a phase is reported once, and never one that comes before it.
 */
const ORDER = ["uploading-frames", "waking", "loading-model", "inferring"];
let lastKind: PhaseKind = getRunPhase().kind;
let furthest = -1;
subscribeRunPhase(() => {
  const s = getRunPhase();
  if (s.kind === lastKind) return;
  lastKind = s.kind;
  if (s.kind === "reading") furthest = -1;
  const phase = RUN_TO_PHONE[s.kind];
  if (!phase) return;
  const rank = ORDER.indexOf(phase);
  if (rank !== -1) {
    if (rank <= furthest) return;
    furthest = rank;
  }
  if (s.kind === "uploading") reportPhonePhase(phase, { bytes: s.upload.totalBytes });
  else if (s.kind === "inferring") reportPhonePhase(phase, s.gpu?.deviceName ? { gpu: s.gpu.deviceName } : undefined);
  else if (s.kind === "failed") reportPhonePhase(phase, { error: s.message ?? "failed", failedIn: s.failedIn });
  else reportPhonePhase(phase);
});
