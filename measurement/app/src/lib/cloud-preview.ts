/**
 * The preview cloud: a thinned copy of DA3's export, on screen before the full result arrives.
 *
 * The service thins `scene.glb` to ~150k points the moment the forward pass returns and
 * publishes it while it is still writing the npz (`server/preview.py`, GET /preview/{token}).
 * This module polls for it during the open request, downloads ~2.4 MB instead of ~16 MB plus a
 * 60–105 MB npz, and hands the viewer an object to show until the full cloud replaces it. On a
 * slow venue network that is the difference the audience sees.
 *
 * The preview is for looking at only. Nothing measures it: Depth 2D, the floor fit and every
 * reading still wait for the full run, exactly as before. It is the same file format in the
 * same frame as the full cloud, so it lands where the full cloud will.
 *
 * A service built before the preview existed answers 404 to the poll; that reads as
 * "unsupported" and the run continues untouched.
 */

import { useSyncExternalStore } from "react";
import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import type { InferManifest } from "./contract";
import { getCloud } from "./cloud-store";
import { artifactUrl, getPreviewStatus, type PreviewStatus } from "./infer-client";
import { localApiHeaders } from "./local-api";
import { getRunPhase, isRunActive, subscribeRunPhase } from "./run-phase";
import { getTimeline, putSpan, timeSpan } from "./run-timeline";

export interface CloudPreview {
  object: THREE.Group;
  points: number;
  sourcePoints: number | null;
  bytes: number;
  bbox: { min: [number, number, number]; max: [number, number, number] };
  extent: number;
  /** Wall-clock time it went on screen, for the Demo panel's own clock. */
  shownAt: number;
}

export type PreviewState =
  | { status: "idle" }
  | { status: "waiting"; token: string }
  | { status: "loading"; token: string }
  | { status: "shown"; token: string; preview: CloudPreview }
  | { status: "unsupported" | "failed"; token: string; error: string };

let state: PreviewState = { status: "idle" };
const listeners = new Set<() => void>();

function set(next: PreviewState): void {
  if (state.status === "shown" && (next.status !== "shown" || next.preview !== state.preview)) {
    disposePreview(state.preview.object);
  }
  state = next;
  for (const listener of listeners) listener();
}

export function getPreview(): PreviewState {
  return state;
}

export function useCloudPreview(): PreviewState {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => state,
    () => state,
  );
}

/** Off the viewer, and the geometry released. */
export function clearPreview(): void {
  stopPolling();
  set({ status: "idle" });
}

/** A token the service accepts (`^[A-Za-z0-9_-]{8,64}$`), unique per press of Run. */
export function newPreviewToken(): string {
  return crypto.randomUUID().replace(/-/g, "");
}

// ---------------------------------------------------------------------------------------------
// Polling while the request is open

let timer: number | null = null;

function stopPolling(): void {
  if (timer !== null) window.clearTimeout(timer);
  timer = null;
}

/**
 * 400 ms against a real service: it is an in-memory read on an instance the open request is
 * already keeping awake, so it adds no lifetime and no machine. Once a second was too coarse
 * after 2026-10-23, when the service began answering ~1 s after the preview was ready: the poll
 * often saw the preview no earlier than the full result.
 */
function pollInterval(): number {
  return getCloud().baseUrl === null ? 250 : 400;
}

/**
 * Open the connection to the bucket before there is anything to fetch from it.
 *
 * On 2026-10-23 the 2.5 MB preview came down at 1.5 MB/s while the 16 MB GLB after it ran at
 * 7.5 MB/s: the preview was paying for DNS, TLS and the first round trip to Cloud Storage. A
 * `preconnect` made when Run is pressed spends that during the GPU wait instead. Anonymous, to
 * match `fetch` of a signed link, which sends no credentials.
 */
function preconnect(origin: string): void {
  if (typeof document === "undefined" || document.querySelector(`link[rel="preconnect"][href="${origin}"]`)) return;
  const link = document.createElement("link");
  link.rel = "preconnect";
  link.href = origin;
  link.crossOrigin = "anonymous";
  document.head.appendChild(link);
}

/** Start following the preview for the run being sent with `token`. */
export function watchPreview(token: string): void {
  stopPolling();
  // The deployed service publishes to Cloud Storage; the mock serves from this origin.
  if (getCloud().baseUrl !== null) preconnect("https://storage.googleapis.com");
  set({ status: "waiting", token });
  const tick = async () => {
    timer = null;
    if (!isCurrent(token, "waiting")) return;
    // Nothing to ask while the frames are still going up: the service has not seen the token,
    // and every early poll is a 404 in the console that reads like a fault.
    const kind = getRunPhase().kind;
    if (kind === "reading" || kind === "uploading") {
      timer = window.setTimeout(tick, pollInterval());
      return;
    }
    let status: PreviewStatus | null;
    try {
      status = await getPreviewStatus(token);
    } catch {
      // A failed read is not an answer; try again while the run is still open.
      status = { state: "pending", runId: "" };
    }
    if (!isCurrent(token, "waiting")) return;
    if (status === null) {
      set({ status: "unsupported", token, error: "this service has no preview route" });
      return;
    }
    if (status.state === "ready" && status.url) {
      void load(token, status.url, status);
      return;
    }
    if (status.state === "failed") {
      set({ status: "failed", token, error: status.error ?? "the service could not build a preview" });
      return;
    }
    if (isRunActive()) timer = window.setTimeout(tick, pollInterval());
  };
  timer = window.setTimeout(tick, pollInterval());
}

/**
 * The manifest is back and the preview never showed — the poll missed it, or it raced the
 * response. Load it from the manifest if it is there: it is still the smallest thing that can
 * put a cloud on screen, and it is downloading beside the full GLB rather than behind it.
 */
export function adoptPreviewArtifact(manifest: InferManifest): void {
  if (state.status === "shown" || state.status === "loading") return;
  const artifact = manifest.artifacts.find((a) => a.kind === "cloud_preview");
  if (!artifact) return;
  stopPolling();
  const token = state.status === "idle" ? `manifest-${manifest.runId}` : state.token;
  void load(token, artifact.url, null);
}

/**
 * Show a preview from a link obtained some other way — the seam for a deployment where the page
 * cannot poll the GPU service itself (the hosted demo, which only reads the API). `key` names the
 * run it belongs to; a second call with the same key while one is loading or shown is ignored.
 */
export function showPreviewFromUrl(key: string, url: string): void {
  if ((state.status === "loading" || state.status === "shown") && state.token === key) return;
  stopPolling();
  void load(key, url, null);
}

function isCurrent(token: string, status: PreviewState["status"]): boolean {
  return state.status === status && "token" in state && state.token === token;
}

async function load(token: string, url: string, status: PreviewStatus | null): Promise<void> {
  set({ status: "loading", token });
  const seenAt = Date.now();
  const opened = getTimeline().find((s) => s.key === "server-wait")?.startMs;
  if (status && opened !== undefined) {
    // Observed: from the request opening to this page seeing the preview. The service's own
    // seconds ride along as the note.
    putSpan({
      key: "preview-ready",
      parent: "server-wait",
      label: "preview: poll saw it ready",
      group: "gpu",
      startMs: opened,
      endMs: seenAt,
      note:
        `service: ready ${(status.readyAfterSeconds ?? 0).toFixed(1)} s after the request was accepted · ` +
        `build ${(status.buildSeconds ?? 0).toFixed(2)} s · publish ${(status.publishSeconds ?? 0).toFixed(2)} s` +
        (status.points ? ` · ${Math.round(status.points / 1000)}k of ${Math.round((status.sourcePoints ?? 0) / 1000)}k points` : ""),
    });
  }
  try {
    const resolved = artifactUrl(url);
    const buffer = await timeSpan(
      { key: "preview-download", parent: "server-wait", label: "preview: download", group: "network" },
      async () => {
        const response = await fetch(resolved, resolved.startsWith("/api/") ? { headers: localApiHeaders({}) } : {});
        if (!response.ok) throw new Error(`preview download ${response.status}`);
        return response.arrayBuffer();
      },
      (b) => ({ bytes: b.byteLength }),
    );
    if (!isCurrent(token, "loading")) return;
    const preview = await timeSpan(
      { key: "preview-shown", parent: "server-wait", label: "preview: parse + on screen", group: "browser" },
      () => build(buffer, status),
    );
    if (!isCurrent(token, "loading")) {
      disposePreview(preview.object);
      return;
    }
    set({ status: "shown", token, preview });
  } catch (error) {
    if (isCurrent(token, "loading")) {
      set({ status: "failed", token, error: error instanceof Error ? error.message : String(error) });
    }
  }
}

async function build(buffer: ArrayBuffer, status: PreviewStatus | null): Promise<CloudPreview> {
  const gltf = await new GLTFLoader().parseAsync(buffer, "");
  const object = gltf.scene;
  object.name = "cloud-preview";
  const bounds = new THREE.Box3().setFromObject(object);
  const extent = bounds.getSize(new THREE.Vector3()).length() || 1;
  let points = 0;
  object.traverse((child) => {
    if (child instanceof THREE.Points) {
      points += child.geometry.getAttribute("position")?.count ?? 0;
      const previous = child.material;
      // Larger than the full cloud's points (extent / 800 there): a sixth of the points at
      // the same size reads as a sparse spray rather than a surface.
      child.material = new THREE.PointsMaterial({ size: extent / 450, vertexColors: true, sizeAttenuation: true });
      for (const item of Array.isArray(previous) ? previous : [previous]) item.dispose();
    } else if (child instanceof THREE.Line || child instanceof THREE.LineSegments || child instanceof THREE.Mesh) {
      // The camera frustums travel in the file; the preview shows the scene, not the rig.
      child.visible = false;
    }
  });
  return {
    object,
    points,
    sourcePoints: status?.sourcePoints ?? null,
    bytes: buffer.byteLength,
    bbox: { min: bounds.min.toArray(), max: bounds.max.toArray() },
    extent,
    shownAt: Date.now(),
  };
}

function disposePreview(root: THREE.Object3D): void {
  root.removeFromParent();
  root.traverse((node) => {
    const drawable = node as Partial<THREE.Mesh>;
    drawable.geometry?.dispose();
    const material = drawable.material;
    if (!material) return;
    for (const item of Array.isArray(material) ? material : [material]) item.dispose();
  });
}

// A new press of Run starts from nothing; a finished run has its full cloud on the wire, so the
// preview steps aside. A failed run keeps whatever preview it got — it is all there is to see.
let lastKind = getRunPhase().kind;
subscribeRunPhase(() => {
  const kind = getRunPhase().kind;
  if (kind === lastKind) return;
  const previous = lastKind;
  lastKind = kind;
  if (kind === "reading" && (previous === "idle" || previous === "done" || previous === "failed")) clearPreview();
  if (kind === "done") clearPreview();
  if (kind === "failed") stopPolling();
});
