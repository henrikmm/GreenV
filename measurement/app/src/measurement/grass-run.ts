/**
 * Turning what is on screen into a grass-grid run.
 *
 * Everything the measurement needs is already loaded somewhere in this app — the depth field
 * in the graph, the masks in the measurement store, the plane in the floor state, the camera
 * poses derivable from the extrinsics. This gathers them, and refuses clearly when one is
 * missing rather than starting a run that will fail three steps later with a geometry error.
 *
 * The grass mask is a NORMAL BRUSH MASK, painted on more than one frame. That is the whole
 * trick that makes this runnable today: masks are already keyed by (target, frame), so
 * painting the verge on three frames of one target produces exactly the multi-frame input the
 * grid asks for. Nothing here segments anything — see docs/TASK.md item 4 for what would.
 */

import type { GrassHeightFrameInput, GrassHeightGridInput, Vec3 } from "../../../geometry";
import type { GraphStoreState } from "../graph/graph-store";
import { resolveInput } from "../graph/graph-store";
import { POINT_CLOUD_ID, VIEWER_3D_ID, type PointCloudValue } from "../graph/nodes";
import { buildCameraTrack } from "../panes/camera-track";
import { readFloorState } from "../panes/floor-state";
import { geometryFrame, type DepthFieldValue } from "./depth-field";
import { getMask } from "./measurement-store";
import { runGrassGrid } from "./grass-grid-store";

/** What a run would be made of, computable without loading a hundred megabytes of NPZ. */
export interface GrassRunPlan {
  ready: boolean;
  /** Why it cannot run, in terms of what the operator would have to do. Absent when ready. */
  blocker?: string;
  framesOffered: number;
  /** Frames of this target that carry a painted mask. */
  maskedFrames: number[];
  objectId: string | null;
}

const MIN_MASKED_FRAMES = 3;

function depthFieldOf(graph: GraphStoreState): DepthFieldValue | undefined {
  return resolveInput(graph, POINT_CLOUD_ID, "depth")?.value as DepthFieldValue | undefined;
}

function cloudOf(graph: GraphStoreState): PointCloudValue | undefined {
  return resolveInput(graph, VIEWER_3D_ID, "points")?.value as PointCloudValue | undefined;
}

/**
 * What is and is not in place for a run, as a checklist the pane can render.
 *
 * Modelled on the Run preconditions in Setup: each blocker says what would satisfy it, so a
 * disabled control is never a mystery.
 */
export function planGrassRun(graph: GraphStoreState, objectId: string | null): GrassRunPlan {
  const depthField = depthFieldOf(graph);
  const cloud = cloudOf(graph);
  const floor = readFloorState(graph);
  const framesOffered = depthField?.frames.length ?? 0;

  const maskedFrames: number[] = [];
  if (depthField && objectId) {
    for (const descriptor of depthField.frames) {
      const mask = getMask(objectId, descriptor.canonicalIndex);
      if (mask && mask.data.some((value) => value !== 0)) maskedFrames.push(descriptor.canonicalIndex);
    }
  }

  const blocker = !depthField
    ? "load a run — the grid measures depth frames, and none are wired in"
    : !cloud
      ? "run the point cloud, which carries the transform into display space"
      : floor.kind !== "ok"
        ? "fit a ground plane — heights are measured above it"
        : !objectId
          ? "select a target to paint the verge on"
          : maskedFrames.length < MIN_MASKED_FRAMES
            ? `paint the verge on at least ${MIN_MASKED_FRAMES} frames — ${maskedFrames.length} so far`
            : undefined;

  return { ready: blocker === undefined, blocker, framesOffered, maskedFrames, objectId };
}

/**
 * Load the arrays, assemble the input, and hand it to the store.
 *
 * The NPZ read is the slow part and is why this is async; everything after it is arithmetic.
 * Failures are published into the store rather than thrown, because the pane that started the
 * run is the pane that should show what went wrong.
 */
export async function startGrassRun(
  graph: GraphStoreState,
  objectId: string,
  offsetM: number,
): Promise<void> {
  const depthField = depthFieldOf(graph);
  const cloud = cloudOf(graph);
  const floor = readFloorState(graph);
  if (!depthField || !cloud || floor.kind !== "ok") {
    throw new Error("a grass run needs a depth field, a point cloud and an accepted ground plane");
  }
  const ground = floor.ground;

  const arrays = await depthField.loadArrays();
  const frames: GrassHeightFrameInput[] = [];
  for (const descriptor of depthField.frames) {
    const mask = getMask(objectId, descriptor.canonicalIndex);
    if (!mask || !mask.data.some((value) => value !== 0)) continue;
    frames.push({
      // The NPZ index, because that is what identifies a frame everywhere else in this project
      // — the frame slider, the inspector, the recorded trials.
      frameIndex: descriptor.npzIndex,
      geometryFrame: geometryFrame(arrays, descriptor),
      grassMask: mask.nativeSemanticMask?.data ?? mask.data,
      maskWidth: mask.nativeSemanticMask?.width ?? mask.width,
      maskHeight: mask.nativeSemanticMask?.height ?? mask.height,
    });
  }

  const depth = arrays.depth;
  const [, imageHeight, imageWidth] = depth?.shape ?? [0, 0, 0];
  const track = buildCameraTrack({
    extrinsics: arrays.extrinsics?.data ?? new Float32Array(),
    intrinsics: arrays.intrinsics?.data ?? new Float32Array(),
    imageWidth,
    imageHeight,
    worldFromDa3: cloud.worldFromDa3,
  });
  const cameraPath: Vec3[] = track.poses.map((pose) => pose.position);

  const input: Omit<GrassHeightGridInput, "roadEdgeWorld"> = {
    runId: depthField.manifest.runId,
    frames,
    worldFromDa3: cloud.worldFromDa3,
    ground: {
      plane: ground.plane,
      gravityUp: ground.gravity.up,
      planeRmseM: ground.fit.rmse,
    },
  };

  await runGrassGrid({ input, cameraPath, offsetM, framesOffered: depthField.frames.length });
}
