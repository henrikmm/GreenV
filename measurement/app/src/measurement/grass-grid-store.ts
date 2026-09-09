/**
 * A grass-grid run, while it is running.
 *
 * The measurement itself is a pure generator in `geometry/grass-height-grid.ts`. This drives
 * it one frame at a time and publishes what it has so far, so the overlays can draw a band
 * filling in rather than a spinner. Nothing here computes a measurement — if a number appears
 * on screen that this file invented, that is a bug.
 *
 * ## Why it yields between frames
 *
 * A frame is a full backprojection: erode, cut confidence, reject depth edges, transform,
 * project into road-local space. On the grass run that is roughly 20,000 pixels per frame,
 * and a dozen frames back to back would freeze the tab for long enough that "is it working?"
 * becomes a real question. Handing control back after each frame costs a few milliseconds and
 * buys a picture that visibly advances.
 *
 * ## What it may and may not claim
 *
 * The only proportion published here is `framesDone / frameTotal`, and both are counted — a
 * frame is done when its observations have been folded in, not when a timer says so
 * (docs/DESIGN.md, honesty rule 5). The elapsed clock is elapsed time and is labelled as such.
 * There is no estimate of time remaining, because nothing here can measure one.
 */

import { useSyncExternalStore } from "react";
import {
  measureGrassHeightGridStaged,
  type GrassHeightAssessmentV1,
  type GrassHeightGridInput,
  type GrassHeightProgress,
  type GrassProvenance,
  type Plane,
  type Vec3,
} from "../../../geometry";
import { roadEdgeFromCameraTrack, sideBalance, type RoadEdgeFromTrack } from "./grass-grid";

export type GrassRunStatus = "idle" | "running" | "done" | "failed";

/** Where the band came from. Displayed verbatim, because it is an assumption, not a measurement. */
export interface RoadEdgeProvenance {
  kind: "camera-track";
  offsetM: number;
  lengthM: number;
  keptPoses: number;
  sourcePoses: number;
}

export interface GrassRunState {
  status: GrassRunStatus;
  /** Null until the first frame has been folded in. */
  progress: GrassHeightProgress | null;
  assessment: GrassHeightAssessmentV1 | null;
  provenance: GrassProvenance | null;
  roadEdge: { polyline: Vec3[]; source: RoadEdgeProvenance } | null;
  plane: Plane | null;
  /** Frames offered, and how many of them carried a grass mask. */
  framesOffered: number;
  framesMasked: number;
  /** How the retained points split either side of the band's centre line. */
  balance: { left: number; right: number } | null;
  /** `${alongIndex},${distIndex}` of the cell being inspected, or null. */
  selectedCell: string | null;
  startedAt: number | null;
  finishedAt: number | null;
  error: string | null;
}

const EMPTY: GrassRunState = {
  status: "idle",
  progress: null,
  assessment: null,
  provenance: null,
  roadEdge: null,
  plane: null,
  framesOffered: 0,
  framesMasked: 0,
  balance: null,
  selectedCell: null,
  startedAt: null,
  finishedAt: null,
  error: null,
};

let state: GrassRunState = EMPTY;
const listeners = new Set<() => void>();
/** Bumped on every start, so a superseded run can tell that it is no longer the current one. */
let generation = 0;

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot(): GrassRunState {
  return state;
}

function commit(patch: Partial<GrassRunState>): void {
  state = { ...state, ...patch };
  for (const listener of listeners) listener();
}

export function useGrassRun(): GrassRunState {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

export function getGrassRun(): GrassRunState {
  return state;
}

export function clearGrassRun(): void {
  generation += 1;
  state = EMPTY;
  for (const listener of listeners) listener();
}

/** Which cell the overlays are inspecting. Clearing is passing null. */
export function selectGrassCell(key: string | null): void {
  if (state.selectedCell === key) return;
  commit({ selectedCell: key });
}

/** Milliseconds the current run has been going, or took. Null before it started. */
export function grassElapsedMs(s: GrassRunState, now = Date.now()): number | null {
  if (s.startedAt === null) return null;
  return (s.finishedAt ?? now) - s.startedAt;
}

/** The cell key the measurement and the provenance agree on. */
export function cellKeyOf(
  coordinate: { alongRoadM: number; distanceFromRoadM: number },
  cellSizeM: number,
): string {
  return `${Math.round(coordinate.alongRoadM / cellSizeM - 0.5)},${Math.round(
    coordinate.distanceFromRoadM / cellSizeM - 0.5,
  )}`;
}

export interface GrassRunRequest {
  /** Everything except the road edge, which this store derives. */
  input: Omit<GrassHeightGridInput, "roadEdgeWorld">;
  /** Camera positions in display space, in capture order. */
  cameraPath: Vec3[];
  /** Metres to push the polyline sideways from the camera path. */
  offsetM: number;
  framesOffered: number;
}

/**
 * Run the grid, publishing after every frame.
 *
 * Resolves when the run finishes or fails; the caller does not need the result, because
 * everything it produced is in the store by then. A second call supersedes the first: the
 * older run notices at its next frame boundary and stops writing.
 */
export async function runGrassGrid(request: GrassRunRequest): Promise<void> {
  const mine = ++generation;
  const { input, cameraPath, offsetM, framesOffered } = request;

  state = {
    ...EMPTY,
    status: "running",
    plane: input.ground.plane,
    framesOffered,
    framesMasked: input.frames.length,
    startedAt: Date.now(),
  };
  for (const listener of listeners) listener();

  let edge: RoadEdgeFromTrack;
  try {
    edge = roadEdgeFromCameraTrack(cameraPath, input.ground.plane, offsetM);
  } catch (error) {
    if (mine !== generation) return;
    commit({ status: "failed", finishedAt: Date.now(), error: messageOf(error) });
    return;
  }

  const roadEdge = {
    polyline: edge.polyline,
    source: {
      kind: "camera-track" as const,
      offsetM,
      lengthM: edge.lengthM,
      keptPoses: edge.keptPoses,
      sourcePoses: edge.sourcePoses,
    },
  };
  commit({ roadEdge });

  try {
    const steps = measureGrassHeightGridStaged(
      { ...input, roadEdgeWorld: edge.polyline },
      { collectProvenance: true },
    );
    for (;;) {
      const step = steps.next();
      if (mine !== generation) return;
      if (step.done) break;
      publish(step.value);
      // Back to the browser, so the overlays paint this frame before the next one starts.
      await new Promise((resolve) => {
        setTimeout(resolve, 0);
      });
      if (mine !== generation) return;
    }
  } catch (error) {
    if (mine !== generation) return;
    commit({ status: "failed", finishedAt: Date.now(), error: messageOf(error) });
  }
}

function publish(step: GrassHeightProgress): void {
  if (step.phase !== "done") {
    commit({ progress: step });
    return;
  }
  const assessment = step.assessment;
  commit({
    status: "done",
    progress: step,
    assessment,
    provenance: step.provenance,
    finishedAt: Date.now(),
    // Chosen for the reviewer rather than for the code: the first abstained cell if there is
    // one, otherwise the highest — the two cells a wrong mask shows up in first.
    selectedCell: openingSelection(assessment),
  });
}

function openingSelection(assessment: GrassHeightAssessmentV1 | null): string | null {
  if (!assessment) return null;
  const samples = assessment.reviewEvidence.samples;
  const opener =
    samples.find((sample) => sample.reason === "highest-h95") ??
    samples.find((sample) => sample.reason === "abstained") ??
    samples[0];
  if (!opener) return null;
  return cellKeyOf(opener.coordinate, assessment.band.cellSizeM);
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * How the retained points split either side of the band's centre line.
 *
 * Computed on demand rather than during the run: it walks every point against every polyline
 * segment, which is worth doing once at the end and not twelve times on the way there.
 */
export function noteSideBalance(points: ArrayLike<number>): void {
  if (!state.roadEdge || !state.plane) return;
  commit({ balance: sideBalance(points, state.roadEdge.polyline, state.plane) });
}

/** Display the exact worker result rather than recomputing it with different floor settings. */
export function showGrassQuality(bundle: import("./grass-quality").GrassQualityBundle): void {
  const provenance: GrassProvenance = new Map();
  for (const cell of bundle.cells) {
    const pixelsByFrame = new Map<number, Uint32Array>();
    let pixelCount = 0;
    for (const p of cell.pixels) {
      const indices: number[] = [];
      for (let i = 0; i < p.runs.length; i += 2) for (let n = p.runs[i]; n < p.runs[i] + p.runs[i + 1]; n++) indices.push(n);
      pixelsByFrame.set(p.frameIndex, Uint32Array.from(indices)); pixelCount += indices.length;
    }
    provenance.set(cell.key, { coordinate: cell.coordinate, pixelsByFrame, pixelCount });
  }
  ++generation;
  commit({ ...EMPTY, status: "done", assessment: bundle.assessment, provenance,
    plane: bundle.ground.plane, framesOffered: bundle.frames.length,
    framesMasked: bundle.frames.filter((f) => f.mask).length,
    startedAt: Date.now() - bundle.timing.totalMs, finishedAt: Date.now(),
    roadEdge: bundle.corridor.lengthM === null ? null : { polyline: bundle.corridor.polyline,
      source: { kind: "camera-track", offsetM: bundle.corridor.offsetM, lengthM: bundle.corridor.lengthM,
        keptPoses: bundle.corridor.polyline.length, sourcePoses: bundle.frames.length } },
    selectedCell: openingSelection(bundle.assessment),
  });
}
