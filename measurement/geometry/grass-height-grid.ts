/**
 * Roadside grass height, as a grid of cells in road-local coordinates.
 *
 * A mown lawn has no single top to hold a tape against — press harder and the number
 * shrinks — so this does not try to find "the height of the grass". It reports three
 * percentiles of the grass surface per half-metre cell, and lets the reader decide which
 * one their job means. The 50th is where the canopy mostly is, the 95th is near the tips
 * without being the single highest point, and the gap between them is itself the answer
 * to "how even is this verge".
 *
 * THE DATUM IS THE CELL'S OWN GROUND. `extent50M`, `extent90M` and `extent95M` measure
 * from a low percentile of the cell's own retained heights, which is what a tape held
 * against a plant measures and the only estimator this project has ever graded: all 17
 * recorded trials across its three runs are `vertical_extent`, and on the one fixture
 * with a tape truth extent read +1.9% against H95's +20.1% on identical points
 * (`docs/evidence/2026-09-05-extent-vs-percentile.md`). `h50M`, `h90M` and `h95M` are the
 * same percentiles measured from the FITTED PLANE instead, kept beside them so the
 * difference — the cell's `localGroundM` pedestal — stays visible rather than assumed
 * away. A global plane cannot follow a raised bed, a crowned shoulder or a ditch; a
 * per-cell datum does, which is the whole reason for the pair.
 *
 * The cost of a local datum is that a cell must stay small enough for its own ground to
 * be flat. Ground that falls across a cell is added straight onto its extent as grass
 * that is not there, so `cellSizeM` is now a correctness parameter and not only a
 * resolution one. Aggregate cell RESULTS over a longer stretch; never widen the cell to
 * pool more points into one box.
 *
 * Two independent conditions define the grass being measured, and both are required:
 * a semantic grass mask, AND a distance band measured from a supplied road edge. The
 * mask alone is not enough, because a mask that is correct about grass still happily
 * includes the lawn behind the fence, the field beyond it, and the garden bed by the
 * house. None of those is the roadside the work order is about.
 *
 * Road-edge extraction is deliberately NOT here. The API takes an ordered 3D polyline,
 * so whatever eventually produces one — hand-drawn, segmented, or surveyed — can be
 * swapped in without touching any of the measurement below.
 *
 * WHAT THIS IS NOT. Nothing in this file has been graded against a lawn-height ground
 * truth. Every result it produces carries `validationStatus: "unvalidated"`, and a human
 * accepting one does not change that: acceptance says the picture looks plausible, not
 * that the centimetres are right. The review half of this module (`recordGrassHeightReview`)
 * is a separable layer — the calculation is complete and usable without it, and no
 * function here requires a review to have happened.
 *
 * Three conventions the tests pin down, each chosen rather than defaulted:
 *
 *   VERTICAL — heights are measured along gravity, not along the fitted plane's normal,
 *     by dividing the plane-perpendicular distance by `dot(normal, gravityUp)`. Measured
 *     on this project's own runs (REGISTRY section 3), the two verticals differ by
 *     7.3-8.2 cm on clumping plants. On a sprawling canopy a few degrees of tilt sweeps
 *     different parts of the plant into the top band, so the axis is not a detail.
 *   ONE FRAME, ONE VOTE — a frame that happens to view a cell from two metres away
 *     contributes tens of times more pixels than one viewing it from ten. Per-frame
 *     percentiles first, then a median across frames, so proximity cannot buy influence.
 *   ORDER INDEPENDENCE — every reduction here is either a sort, a min, an integer count,
 *     or a nearest-rank percentile. No running float sums, no first-seen wins. Shuffling
 *     the points or the frames produces byte-for-byte identical output, which is what
 *     makes a re-run a check rather than a new opinion.
 */

import { backprojectMask, erodeMask, resampleMaskNearest, transformPoints, type Frame } from "./backproject";
import { median, percentile, percentileOfSorted } from "./measure";
import { basisFromUp, dot, normalize, signedHeight, type Plane, type Vec3 } from "./types";

export const GRASS_HEIGHT_ASSESSMENT_SCHEMA = "verge.grass-height-assessment/0.1.0" as const;

/** Nearest-rank percentiles reported per cell. Ordered, because H50 <= H90 <= H95 is a test. */
const REPORTED_PERCENTILES = [50, 90, 95] as const;

/**
 * The percentile of a cell's own retained heights that stands for the ground under it.
 *
 * 2 rather than 0 for the same reason `measureVerticalExtent` uses P2 rather than `min`:
 * the lowest point in a cell is a flying pixel, and a datum taken from it drags every
 * height in the cell up with it. Matching that function's `lowerPercentile` is deliberate
 * — the graded extent measurements in `MEASUREMENTS.md` were all produced with it, so the
 * per-cell datum is the same estimator those numbers were earned by.
 */
const LOCAL_GROUND_PERCENTILE = 2;

/**
 * How close a voxel must sit to the local ground to count as touching it, in metres.
 *
 * A real ground surface puts many voxels within a few centimetres of its own low
 * percentile, because it is a surface. A cell that only ever saw canopy has a thin tail
 * there instead, so its datum is the bottom of the foliage rather than the soil and its
 * extent is an under-reading. The count is reported rather than gated on: what separates
 * the two cases on real roadside video has not been measured.
 */
const GROUND_CONTACT_BAND_M = 0.05;

/**
 * How much of each frame's own confidence distribution is thrown away.
 *
 * A fraction rather than an absolute threshold: DA3's confidence is not calibrated
 * across scenes, so a fixed cut keeps everything on one clip and nothing on another.
 * Taken over the frame's MASKED pixels, not the whole frame — the lowest fifth of the
 * evidence this measurement is actually built from, rather than the lowest fifth of a
 * picture that might be four-fifths sky.
 */
const CONFIDENCE_DROP_FRACTION = 0.2;

export interface GrassHeightGridOptions {
  /** Width of the measured band, from the road edge outwards, in metres. */
  maxDistanceFromRoadM?: number;
  /** Side of the square measurement cell, in metres. */
  cellSizeM?: number;
  /** Observations closer together than this, in road-local space, count once. */
  voxelSizeM?: number;
  /** A cell measured by fewer frames than this abstains. */
  minFrames?: number;
  /** A frame contributing fewer unique voxels than this to a cell does not vote on it. */
  minVoxelsPerFrame?: number;
}

const DEFAULTS: Required<GrassHeightGridOptions> = {
  maxDistanceFromRoadM: 5.0,
  cellSizeM: 0.5,
  voxelSizeM: 0.02,
  minFrames: 3,
  minVoxelsPerFrame: 20,
};

export interface GrassHeightFrameInput {
  /** The frame's identity in the run, NOT its position in this array. Must be unique. */
  frameIndex: number;
  geometryFrame: Frame;
  /** 1 where grass, 0 elsewhere. Row-major over the mask grid. */
  grassMask: Uint8Array;
  /**
   * The grid `grassMask` was painted on, when it is not the depth grid.
   *
   * Optional because a mask that already matches the depth map needs neither. A mask
   * painted at source resolution does: its length alone cannot say whether it is
   * 640x480 or 480x640, so a caller resampling from the photograph has to say. Given,
   * the mask is resampled nearest-neighbour onto the depth grid; omitted, a mask whose
   * length does not match the depth grid is an error rather than a guess.
   */
  maskWidth?: number;
  maskHeight?: number;
}

export interface GrassHeightGridInput {
  runId: string;
  frames: GrassHeightFrameInput[];
  /** Row-major 4x4, DA3's `hf_alignment`: raw reconstruction frame to world. */
  worldFromDa3: ArrayLike<number>;
  /** Ordered in the vehicle/travel direction. World space, at least two distinct points. */
  roadEdgeWorld: Vec3[];
  ground: {
    plane: Plane;
    gravityUp: Vec3;
    planeRmseM: number;
  };
  options?: GrassHeightGridOptions;
}

export interface GrassCellCoordinate {
  alongRoadM: number;
  distanceFromRoadM: number;
}

export interface GrassCellMeasurement {
  coordinate: GrassCellCoordinate;
  /**
   * The cell's own ground, in metres above the FITTED PLANE.
   *
   * The pedestal the plane-relative heights below cannot cancel: a raised bed, a bank, the
   * crown of a shoulder. Measured on this project's own fixture 2026-09-05, the taped clump
   * in `20260814-174814-b245bc` grows from 0.114 m of raised bed, which is 11.6% of its
   * 0.980 m tape truth — see `docs/evidence/2026-09-05-extent-vs-percentile.md`.
   */
  localGroundM: number | null;
  /** Voxels within `GROUND_CONTACT_BAND_M` of `localGroundM`. Thin means the datum is foliage. */
  groundContactVoxels: number | null;
  /**
   * Vegetation height above the cell's OWN ground — the default reading.
   *
   * `extent95M` is what a tape held against the plant measures, and is the only estimator
   * this project has graded: every one of the 17 recorded trials across its three runs is a
   * `vertical_extent`. The `h*` fields below are the same percentiles measured from the
   * fitted plane instead, kept so the pedestal stays visible as the difference.
   */
  extent50M: number | null;
  extent90M: number | null;
  extent95M: number | null;
  h50M: number | null;
  h90M: number | null;
  h95M: number | null;
  /** Frames that voted, i.e. reached `minVoxelsPerFrame` in this cell. Not frames that saw it. */
  frameCount: number;
  /** Unique voxels summed over the voting frames. */
  sampleCount: number;
  /** Observational disagreement, not a calibrated error bar. */
  h95SpreadM?: number | null;
  frameVotes?: Array<{ frameIndex: number; sampleCount: number; h50M: number; h90M: number; h95M: number }>;
  /**
   * Up to three frames to look at for this cell.
   *
   * Measured cells include a representative frame, the largest H95 disagreement and
   * the weakest support. Redundant choices are filled by the next representative.
   * The reviewer sees evidence against the answer as well as for it. Abstained
   * cells name the lowest-numbered frames that saw the cell at all, because the question
   * there is "why was there not enough evidence", and that is what those frames show.
   */
  evidenceFrameIndices: number[];
  status: "measured" | "insufficient-support";
  reason?: "too-few-frames" | "too-few-samples";
}

export type GrassReviewSampleReason =
  | "lowest-h95"
  | "median-h95"
  | "highest-h95"
  | "lowest-support"
  | "largest-disagreement"
  | "abstained";

export interface GrassReviewSample {
  coordinate: GrassCellCoordinate;
  reason: GrassReviewSampleReason;
  frameIndices: number[];
}

export interface GrassHeightAssessmentV1 {
  schemaVersion: typeof GRASS_HEIGHT_ASSESSMENT_SCHEMA;
  runId: string;
  coordinateFrame: "road-local-metres";
  validationStatus: "unvalidated";
  band: {
    minDistanceFromRoadM: 0;
    maxDistanceFromRoadM: number;
    cellSizeM: number;
  };
  measurements: GrassCellMeasurement[];
  reviewEvidence: {
    measuredCellCount: number;
    abstainedCellCount: number;
    /**
     * Measured cells over OBSERVED cells — not over the band's area.
     *
     * A cell exists here only once some retained grass point landed in it, so this
     * answers "of the places I saw, how many could I measure", not "how much of the
     * verge did I cover". A stretch of road the camera never faced contributes no cells
     * at all and therefore cannot lower this number. Say so wherever it is displayed.
     */
    coverageFraction: number;
    h95RangeM: { min: number; max: number } | null;
    /** The same, for the default reading. Above each cell's own ground, not the plane. */
    extent95RangeM: { min: number; max: number } | null;
    samples: GrassReviewSample[];
  };
  review: {
    status: "pending" | "accepted" | "rejected";
    checks: {
      correctGrassAndBand: boolean | null;
      plausibleHeightPattern: boolean | null;
      coverageUnderstood: boolean | null;
    };
    reviewerId: string | null;
    reviewedAt: string | null;
    note: string | null;
  };
}

export interface GrassHeightReviewDecision {
  decision: "accepted" | "rejected";
  checks: {
    correctGrassAndBand: boolean;
    plausibleHeightPattern: boolean;
    coverageUnderstood: boolean;
  };
  reviewerId: string;
  /** ISO-8601. */
  reviewedAt: string;
  note?: string;
}

/** Bad input, as opposed to input that simply produced no measurable cells. */
export class GrassHeightInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GrassHeightInputError";
  }
}

// ------------------------------------------------------------------ road-local space

/** A point's position on the road, and how far off it lies. Both in metres, on the ground plane. */
interface RoadStation {
  alongRoadM: number;
  distanceFromRoadM: number;
}

/**
 * The road edge, flattened onto the ground plane and measured out in arc length.
 *
 * Everything downstream works in this 2D frame. Building it once means the polyline is
 * projected once rather than per point, and — more importantly — means the point and the
 * road are compared in the same plane, which is the only reason a distance from the road
 * is a distance along the ground rather than a distance through the air.
 */
interface RoadFrame {
  /** Polyline vertices as `[u, v]` in the plane's basis. */
  vertices: Array<[number, number]>;
  /** Cumulative arc length at each vertex; `station[0]` is 0. */
  station: number[];
  lengthM: number;
  e1: Vec3;
  e2: Vec3;
}

function projectOntoPlane(point: Vec3, plane: Plane): Vec3 {
  const h = signedHeight(plane, point);
  return [
    point[0] - plane.normal[0] * h,
    point[1] - plane.normal[1] * h,
    point[2] - plane.normal[2] * h,
  ];
}

function buildRoadFrame(roadEdgeWorld: Vec3[], plane: Plane): RoadFrame {
  if (roadEdgeWorld.length < 2) {
    throw new GrassHeightInputError(
      `road edge has ${roadEdgeWorld.length} point(s); at least 2 distinct points are required. ` +
        "Without a direction there is no along-road axis and no side to measure from.",
    );
  }
  for (const [index, vertex] of roadEdgeWorld.entries()) {
    if (vertex.length !== 3 || !vertex.every((component) => Number.isFinite(component))) {
      throw new GrassHeightInputError(`road edge vertex ${index} is not a finite 3D point`);
    }
  }

  const { e1, e2 } = basisFromUp(plane.normal);
  const vertices: Array<[number, number]> = roadEdgeWorld.map((vertex) => {
    const flat = projectOntoPlane(vertex, plane);
    return [dot(flat, e1), dot(flat, e2)];
  });

  const station = [0];
  for (let i = 1; i < vertices.length; i++) {
    const dx = vertices[i][0] - vertices[i - 1][0];
    const dy = vertices[i][1] - vertices[i - 1][1];
    station.push(station[i - 1] + Math.hypot(dx, dy));
  }
  const lengthM = station[station.length - 1];
  if (!(lengthM > 0)) {
    throw new GrassHeightInputError(
      "road edge has zero length once projected onto the ground plane — every vertex " +
        "collapsed to one point, or the polyline is vertical.",
    );
  }
  return { vertices, station, lengthM, e1, e2 };
}

/**
 * Where a point sits on the road, and how far from it.
 *
 * The nearest position is clamped to the polyline, so a point past either end folds back
 * onto that end: its `alongRoadM` piles up at 0 or at the full length, and its distance
 * becomes the distance to the end vertex. That is the honest answer to "how far along
 * this road edge is something that is not beside it", and it is why a polyline should
 * span the stretch being measured rather than stop short of it.
 */
function stationOf(u: number, v: number, road: RoadFrame): RoadStation {
  let bestDistance = Infinity;
  let bestAlong = 0;
  for (let i = 1; i < road.vertices.length; i++) {
    const [ax, ay] = road.vertices[i - 1];
    const [bx, by] = road.vertices[i];
    const dx = bx - ax;
    const dy = by - ay;
    const lengthSquared = dx * dx + dy * dy;
    // A repeated vertex is a zero-length segment: it is still a valid position on the
    // polyline, so measure to the point rather than dividing by zero.
    const t = lengthSquared > 0 ? Math.min(1, Math.max(0, ((u - ax) * dx + (v - ay) * dy) / lengthSquared)) : 0;
    const cx = ax + dx * t;
    const cy = ay + dy * t;
    const distance = Math.hypot(u - cx, v - cy);
    // Strict, so the earliest segment wins a tie and the answer cannot depend on
    // how the polyline was subdivided.
    if (distance < bestDistance) {
      bestDistance = distance;
      bestAlong = road.station[i - 1] + Math.hypot(cx - ax, cy - ay);
    }
  }
  return { alongRoadM: bestAlong, distanceFromRoadM: bestDistance };
}

/**
 * Which cell a coordinate falls in, with the far edge folded into the last cell.
 *
 * Without the clamp a point at exactly the band edge opens a cell whose centre is
 * outside the band, and — because reversing the road edge maps a station of 0 to a
 * station of `length` — that same off-by-one would break the reversal symmetry the
 * tests check. One rule fixes both.
 */
function cellIndex(value: number, cellSizeM: number, extentM: number): number {
  const last = Math.max(0, Math.ceil(extentM / cellSizeM) - 1);
  return Math.min(last, Math.max(0, Math.floor(value / cellSizeM)));
}

function cellCentre(index: number, cellSizeM: number): number {
  return (index + 0.5) * cellSizeM;
}

// ------------------------------------------------------------------- per-frame points

/** One retained observation, already in road-local space. */
interface Observation {
  alongIndex: number;
  distIndex: number;
  voxelKey: string;
  heightM: number;
  /** Source pixel on the depth grid. Only populated when provenance was asked for. */
  pixelIndex: number;
}

/**
 * The confidence floor for one frame: its own 20th percentile over the masked pixels.
 *
 * Returns -Infinity when the frame has no confidence map or too little of one to rank,
 * which keeps every pixel rather than silently emptying the frame.
 */
function confidenceFloor(frame: Frame, mask: Uint8Array): number {
  const confidence = frame.confidence;
  if (!confidence) return -Infinity;
  const values: number[] = [];
  for (let i = 0; i < mask.length; i++) {
    if (!mask[i]) continue;
    const c = confidence[i];
    if (Number.isFinite(c)) values.push(c);
  }
  if (values.length < 5) return -Infinity;
  return percentile(values, CONFIDENCE_DROP_FRACTION * 100);
}

function maskOnDepthGrid(frame: GrassHeightFrameInput): Uint8Array {
  const { geometryFrame, grassMask, maskWidth, maskHeight, frameIndex } = frame;
  const expected = geometryFrame.width * geometryFrame.height;
  if (maskWidth !== undefined || maskHeight !== undefined) {
    if (!maskWidth || !maskHeight || maskWidth < 1 || maskHeight < 1) {
      throw new GrassHeightInputError(`frame ${frameIndex}: maskWidth and maskHeight must both be positive`);
    }
    if (grassMask.length !== maskWidth * maskHeight) {
      throw new GrassHeightInputError(
        `frame ${frameIndex}: mask has ${grassMask.length} values, expected ${maskWidth}x${maskHeight}`,
      );
    }
    if (maskWidth === geometryFrame.width && maskHeight === geometryFrame.height) return grassMask;
    return resampleMaskNearest(grassMask, maskWidth, maskHeight, geometryFrame.width, geometryFrame.height);
  }
  if (grassMask.length !== expected) {
    throw new GrassHeightInputError(
      `frame ${frameIndex}: mask has ${grassMask.length} values but the depth map is ` +
        `${geometryFrame.width}x${geometryFrame.height}. Pass maskWidth and maskHeight to have it resampled.`,
    );
  }
  return grassMask;
}

/**
 * One frame's grass pixels, as road-local observations.
 *
 * The height is normalised to gravity here rather than later, because everything after
 * this point — the below-ground rejection, the voxel grid, the percentiles — is stated
 * in physical vertical metres, and a mixture of two verticals in one distribution is
 * exactly the error that would be invisible in the output.
 */
function observationsFor(
  frame: GrassHeightFrameInput,
  input: GrassHeightGridInput,
  road: RoadFrame,
  options: Required<GrassHeightGridOptions>,
  verticalScale: number,
  collectPixelIndices: boolean,
): Observation[] {
  const { plane, planeRmseM } = { plane: input.ground.plane, planeRmseM: input.ground.planeRmseM };
  const onGrid = maskOnDepthGrid(frame);
  const eroded = erodeMask(onGrid, frame.geometryFrame.width, frame.geometryFrame.height, 2);
  const minConfidence = confidenceFloor(frame.geometryFrame, eroded);

  const backprojected = backprojectMask(frame.geometryFrame, eroded, {
    erodeRadius: 0, // already eroded, so the confidence floor is taken over the pixels actually used
    minConfidence,
    maxRelativeDepthStep: 0.05,
    collectPixelIndices,
  });
  const sourcePixels = backprojected.pixelIndices;
  const world = transformPoints(backprojected.points, input.worldFromDa3);

  const belowGroundLimit = -3 * Math.abs(planeRmseM);
  const observations: Observation[] = [];
  for (let i = 0; i + 2 < world.length; i += 3) {
    const pixelIndex = sourcePixels ? sourcePixels[i / 3] : -1;
    const point: Vec3 = [world[i], world[i + 1], world[i + 2]];
    const perpendicular = signedHeight(plane, point);
    if (!Number.isFinite(perpendicular)) continue;
    const heightM = perpendicular / verticalScale;
    if (!Number.isFinite(heightM)) continue;
    // Below the fitted ground by more than its own fit error is a reconstruction
    // artefact, not a negative-height plant. Nearer misses are the plane's own noise
    // and are flattened rather than dropped, which keeps a sparse cell's support.
    if (heightM < belowGroundLimit) continue;
    const height = heightM < 0 ? 0 : heightM;

    const flat = projectOntoPlane(point, plane);
    const station = stationOf(dot(flat, road.e1), dot(flat, road.e2), road);
    if (station.distanceFromRoadM > options.maxDistanceFromRoadM) continue;

    // Voxels are quantised in ROAD-LOCAL space, not world space. A world-space grid is
    // pinned to the origin, so translating the scene slides points across voxel walls and
    // changes which observations survive deduplication. Road-local coordinates ride along
    // with the scene, which is what makes the rigid-transform invariance exact rather than
    // approximate.
    const vx = Math.floor(station.alongRoadM / options.voxelSizeM);
    const vy = Math.floor(station.distanceFromRoadM / options.voxelSizeM);
    const vz = Math.floor(height / options.voxelSizeM);
    observations.push({
      alongIndex: cellIndex(station.alongRoadM, options.cellSizeM, road.lengthM),
      distIndex: cellIndex(station.distanceFromRoadM, options.cellSizeM, options.maxDistanceFromRoadM),
      voxelKey: `${vx},${vy},${vz}`,
      heightM: height,
      pixelIndex,
    });
  }
  return observations;
}

// ---------------------------------------------------------------------- accumulation

interface FrameVote {
  frameIndex: number;
  voxelCount: number;
  /** Nearest-rank percentiles of this frame's voxel heights, in `REPORTED_PERCENTILES` order. */
  percentiles: number[];
}

interface CellAccumulator {
  alongIndex: number;
  distIndex: number;
  /** frameIndex -> voxelKey -> the lowest height seen in that voxel. */
  byFrame: Map<number, Map<string, number>>;
}

/**
 * Reduce one frame's observations in one cell to a vote.
 *
 * The per-voxel representative is the LOWEST height in the voxel. Any choice within a
 * 2 cm box is arbitrary at the level it resolves, so the tie-break is made on two other
 * grounds: `Math.min` is exactly order-independent in floating point, where a running
 * mean is not, and taking the lowest can only ever under-report a grass height.
 */
function voteFor(frameIndex: number, voxels: Map<string, number>, minVoxels: number): FrameVote | null {
  if (voxels.size < minVoxels) return null;
  const heights = Float64Array.from(voxels.values()).sort();
  return {
    frameIndex,
    voxelCount: voxels.size,
    percentiles: REPORTED_PERCENTILES.map((p) => percentileOfSorted(heights, p)),
  };
}

/**
 * The cell's own ground, and how much evidence actually touches it.
 *
 * Pooled across the voting frames rather than taken per frame and averaged, because the
 * ground under a half-metre of verge is a property of the place, not of the viewpoint —
 * whereas a canopy percentile genuinely depends on which frame saw it, which is why the
 * heights above keep one frame, one vote and this does not. A voxel seen by two frames
 * contributes once, at its lower height, so the union is order-independent for the same
 * reason `voteFor` is.
 */
function localGroundOf(votingFrames: Array<Map<string, number>>): { groundM: number; contactVoxels: number } {
  const pooled = new Map<string, number>();
  for (const voxels of votingFrames) {
    for (const [key, height] of voxels) {
      const existing = pooled.get(key);
      pooled.set(key, existing === undefined ? height : Math.min(existing, height));
    }
  }
  const heights = Float64Array.from(pooled.values()).sort();
  const groundM = percentileOfSorted(heights, LOCAL_GROUND_PERCENTILE);
  let contactVoxels = 0;
  for (const height of heights) if (height <= groundM + GROUND_CONTACT_BAND_M) contactVoxels += 1;
  return { groundM, contactVoxels };
}

/**
 * A percentile re-datumed onto the cell's own ground.
 *
 * The datum is one number for the whole cell, so subtracting it after the median across
 * frames is identical to subtracting it from every frame's vote first — one frame, one
 * vote is preserved exactly rather than approximately. Clamped at zero: a canopy
 * percentile below the cell's own P2 is arithmetic noise on a near-empty cell, not a
 * plant of negative height.
 */
function above(groundM: number, heightM: number): number {
  return Math.max(0, heightM - groundM);
}

// ------------------------------------------------------------------------ the API

function resolveOptions(options: GrassHeightGridOptions | undefined): Required<GrassHeightGridOptions> {
  const resolved = { ...DEFAULTS, ...options };
  const positive: Array<[string, number]> = [
    ["maxDistanceFromRoadM", resolved.maxDistanceFromRoadM],
    ["cellSizeM", resolved.cellSizeM],
    ["voxelSizeM", resolved.voxelSizeM],
    ["minFrames", resolved.minFrames],
    ["minVoxelsPerFrame", resolved.minVoxelsPerFrame],
  ];
  for (const [name, value] of positive) {
    if (!Number.isFinite(value) || value <= 0) {
      throw new GrassHeightInputError(`${name} must be a positive finite number, got ${value}`);
    }
  }
  return resolved;
}

/**
 * Which pixels a cell's numbers were computed from.
 *
 * The overlays exist to answer one question the JSON cannot: is this cell's 1.18 m grass, or is
 * it a fence post? Only the source pixels drawn back onto the photograph answer that, and a
 * compacted point array has already thrown away which pixel each point was. So this is collected
 * on request, alongside the measurement rather than instead of it — the same run, not a re-run.
 *
 * These are the pixels that survived every filter and landed in the cell, BEFORE voxel
 * deduplication. That is deliberate: dedup decides how much a pixel counts, and the question
 * being asked here is which pixels were looked at.
 */
export interface GrassCellProvenance {
  coordinate: GrassCellCoordinate;
  /** Source pixel indices on the depth grid, keyed by the frame they belong to. */
  pixelsByFrame: Map<number, Uint32Array>;
  pixelCount: number;
}

/** Keyed exactly as the cells are: `${alongIndex},${distIndex}`. */
export type GrassProvenance = Map<string, GrassCellProvenance>;

/** Ask the staged calculation for more than it needs. Never used by the measurement path. */
export interface GrassHeightDebugOptions {
  collectProvenance?: boolean;
}

/** What the calculation is doing, for a caller that wants to watch it happen. */
export type GrassGridPhase = "reading-frames" | "gridding" | "done";

export interface GrassHeightProgress {
  phase: GrassGridPhase;
  /** Frames finished, out of the total. Counted, never estimated. */
  framesDone: number;
  frameTotal: number;
  /** The frame just finished. Null before the first one and on the closing steps. */
  frameIndex: number | null;
  /** Observations that frame contributed, and the running total across frames. */
  frameObservations: number;
  observationsRetained: number;
  /** Distinct cells touched so far. Rises as coverage grows, never falls. */
  cellsTouched: number;
  /** Present only on the `done` step. */
  assessment: GrassHeightAssessmentV1 | null;
  /** Present only on the `done` step, and only when provenance was asked for. */
  provenance: GrassProvenance | null;
}

/**
 * The calculation, one frame at a time.
 *
 * Same code as `measureGrassHeightGrid` — that function drains this one — because an
 * interface watching a computation must be watching the computation, not a second
 * implementation of it that can drift. This is the same rule the inspector follows.
 *
 * Every number a consumer can draw from here is counted: frames finished out of frames
 * given, observations kept, cells touched. None of it is a clock or an estimate, which
 * is what lets a progress bar be drawn from it at all (docs/DESIGN.md, honesty rule 5).
 */
export function* measureGrassHeightGridStaged(
  input: GrassHeightGridInput,
  debug: GrassHeightDebugOptions = {},
): Generator<GrassHeightProgress, GrassHeightAssessmentV1, void> {
  const options = resolveOptions(input.options);

  if (input.worldFromDa3.length !== 16) {
    throw new GrassHeightInputError(`worldFromDa3 must be a row-major 4x4, got ${input.worldFromDa3.length} values`);
  }
  if (!normalize(input.ground.plane.normal)) {
    throw new GrassHeightInputError("ground plane normal is degenerate");
  }
  const up = normalize(input.ground.gravityUp);
  if (!up) throw new GrassHeightInputError("gravityUp is degenerate");
  if (!Number.isFinite(input.ground.planeRmseM) || input.ground.planeRmseM < 0) {
    throw new GrassHeightInputError(`planeRmseM must be a non-negative number, got ${input.ground.planeRmseM}`);
  }
  const planeNormal = normalize(input.ground.plane.normal) as Vec3;
  const verticalScale = dot(planeNormal, up);
  if (!(verticalScale > 1e-6)) {
    throw new GrassHeightInputError(
      `the ground plane is edge-on to gravity (normal-up alignment ${verticalScale.toFixed(6)}), ` +
        "so a vertical height above it has no meaning. The plane normal must point up.",
    );
  }

  const seen = new Set<number>();
  for (const frame of input.frames) {
    if (!Number.isInteger(frame.frameIndex)) {
      throw new GrassHeightInputError(`frameIndex must be an integer, got ${frame.frameIndex}`);
    }
    if (seen.has(frame.frameIndex)) {
      throw new GrassHeightInputError(
        `frame ${frame.frameIndex} appears twice. One frame gets one vote, so the same frame ` +
          "passed twice would silently weight itself double.",
      );
    }
    seen.add(frame.frameIndex);
  }

  const road = buildRoadFrame(input.roadEdgeWorld, input.ground.plane);

  const cells = new Map<string, CellAccumulator>();
  const collectProvenance = debug.collectProvenance === true;
  const pixelTrail = collectProvenance ? new Map<string, Map<number, number[]>>() : null;
  const frameTotal = input.frames.length;
  let framesDone = 0;
  let observationsRetained = 0;

  for (const frame of input.frames) {
    const observations = observationsFor(frame, input, road, options, verticalScale, collectProvenance);
    for (const observation of observations) {
      const key = `${observation.alongIndex},${observation.distIndex}`;
      let cell = cells.get(key);
      if (!cell) {
        cell = { alongIndex: observation.alongIndex, distIndex: observation.distIndex, byFrame: new Map() };
        cells.set(key, cell);
      }
      let voxels = cell.byFrame.get(frame.frameIndex);
      if (!voxels) {
        voxels = new Map();
        cell.byFrame.set(frame.frameIndex, voxels);
      }
      const existing = voxels.get(observation.voxelKey);
      voxels.set(
        observation.voxelKey,
        existing === undefined ? observation.heightM : Math.min(existing, observation.heightM),
      );
      if (pixelTrail) {
        let byFrame = pixelTrail.get(key);
        if (!byFrame) {
          byFrame = new Map();
          pixelTrail.set(key, byFrame);
        }
        const list = byFrame.get(frame.frameIndex);
        if (list) list.push(observation.pixelIndex);
        else byFrame.set(frame.frameIndex, [observation.pixelIndex]);
      }
    }
    framesDone += 1;
    observationsRetained += observations.length;
    yield {
      phase: "reading-frames",
      framesDone,
      frameTotal,
      frameIndex: frame.frameIndex,
      frameObservations: observations.length,
      observationsRetained,
      cellsTouched: cells.size,
      assessment: null,
      provenance: null,
    };
  }

  yield {
    phase: "gridding",
    framesDone,
    frameTotal,
    frameIndex: null,
    frameObservations: 0,
    observationsRetained,
    cellsTouched: cells.size,
    assessment: null,
    provenance: null,
  };

  const assessment = reduceCells(cells, input.runId, options);
  yield {
    phase: "done",
    framesDone,
    frameTotal,
    frameIndex: null,
    frameObservations: 0,
    observationsRetained,
    cellsTouched: cells.size,
    assessment,
    provenance: pixelTrail ? freezeProvenance(pixelTrail, cells, options) : null,
  };
  return assessment;
}

/**
 * Measure roadside grass height into a grid of road-local cells.
 *
 * Pure: no clock, no filesystem, no network. The result always carries a PENDING review
 * with null reviewer fields — this function never marks its own work accepted, and never
 * reports anything but `validationStatus: "unvalidated"`.
 */
export function measureGrassHeightGrid(input: GrassHeightGridInput): GrassHeightAssessmentV1 {
  const steps = measureGrassHeightGridStaged(input);
  let step = steps.next();
  while (!step.done) step = steps.next();
  return step.value;
}

/** Growable pixel lists become typed arrays once nothing more can be added to them. */
function freezeProvenance(
  trail: Map<string, Map<number, number[]>>,
  cells: Map<string, CellAccumulator>,
  options: Required<GrassHeightGridOptions>,
): GrassProvenance {
  const out: GrassProvenance = new Map();
  for (const [key, byFrame] of trail) {
    const cell = cells.get(key);
    if (!cell) continue;
    const pixelsByFrame = new Map<number, Uint32Array>();
    let pixelCount = 0;
    for (const [frameIndex, list] of byFrame) {
      // Ascending, so a consumer painting them walks the image in raster order rather than
      // in whatever order the backprojection happened to emit.
      pixelsByFrame.set(frameIndex, Uint32Array.from(list).sort());
      pixelCount += list.length;
    }
    out.set(key, {
      coordinate: {
        alongRoadM: cellCentre(cell.alongIndex, options.cellSizeM),
        distanceFromRoadM: cellCentre(cell.distIndex, options.cellSizeM),
      },
      pixelsByFrame,
      pixelCount,
    });
  }
  return out;
}

/** Votes, percentiles and abstentions, once every frame has been read. */
function reduceCells(
  cells: Map<string, CellAccumulator>,
  runId: string,
  options: Required<GrassHeightGridOptions>,
): GrassHeightAssessmentV1 {
  const measurements: GrassCellMeasurement[] = [];
  for (const cell of cells.values()) {
    const votes: FrameVote[] = [];
    for (const [frameIndex, voxels] of cell.byFrame) {
      const vote = voteFor(frameIndex, voxels, options.minVoxelsPerFrame);
      if (vote) votes.push(vote);
    }
    votes.sort((a, b) => a.frameIndex - b.frameIndex);

    const coordinate: GrassCellCoordinate = {
      alongRoadM: cellCentre(cell.alongIndex, options.cellSizeM),
      distanceFromRoadM: cellCentre(cell.distIndex, options.cellSizeM),
    };
    const frameCount = votes.length;
    const sampleCount = votes.reduce((total, vote) => total + vote.voxelCount, 0);

    if (frameCount < options.minFrames) {
      // Which of the two shortages it was: some frames qualified but not enough of them,
      // or no single frame ever saw enough of this cell to be worth a vote.
      const reason = frameCount > 0 ? "too-few-frames" : "too-few-samples";
      const observing = Array.from(cell.byFrame.keys()).sort((a, b) => a - b);
      measurements.push({
        coordinate,
        localGroundM: null,
        groundContactVoxels: null,
        extent50M: null,
        extent90M: null,
        extent95M: null,
        h50M: null,
        h90M: null,
        h95M: null,
        frameCount,
        sampleCount,
        h95SpreadM: null,
        frameVotes: votes.map((v) => ({ frameIndex: v.frameIndex, sampleCount: v.voxelCount,
          h50M: v.percentiles[0], h90M: v.percentiles[1], h95M: v.percentiles[2] })),
        evidenceFrameIndices: observing.slice(0, 3),
        status: "insufficient-support",
        reason,
      });
      continue;
    }

    const [h50M, h90M, h95M] = REPORTED_PERCENTILES.map((_, slot) =>
      median(votes.map((vote) => vote.percentiles[slot])),
    );
    // The datum comes from the frames that voted, so the ground and the heights above it
    // are built from one body of evidence rather than two.
    const { groundM, contactVoxels } = localGroundOf(
      votes.map((vote) => cell.byFrame.get(vote.frameIndex)!),
    );
    // Representative, disagreement and support all deserve inspection. Ties use frame identity.
    const byAgreement = votes
      .map((vote) => ({ frameIndex: vote.frameIndex, delta: Math.abs(vote.percentiles[2] - h95M) }))
      .sort((a, b) => a.delta - b.delta || a.frameIndex - b.frameIndex);
    const weakest = [...votes].sort((a, b) => a.voxelCount - b.voxelCount || a.frameIndex - b.frameIndex)[0];
    const mostDifferent = [...byAgreement].sort((a, b) => b.delta - a.delta || a.frameIndex - b.frameIndex)[0];
    const evidenceFrameIndices = [...new Set([
      byAgreement[0].frameIndex, mostDifferent.frameIndex, weakest.frameIndex,
      ...byAgreement.map((v) => v.frameIndex),
    ])].slice(0, 3);

    measurements.push({
      coordinate,
      localGroundM: groundM,
      groundContactVoxels: contactVoxels,
      extent50M: above(groundM, h50M),
      extent90M: above(groundM, h90M),
      extent95M: above(groundM, h95M),
      h50M,
      h90M,
      h95M,
      frameCount,
      sampleCount,
      h95SpreadM: Math.max(...votes.map((v) => v.percentiles[2])) - Math.min(...votes.map((v) => v.percentiles[2])),
      frameVotes: votes.map((v) => ({ frameIndex: v.frameIndex, sampleCount: v.voxelCount,
        h50M: v.percentiles[0], h90M: v.percentiles[1], h95M: v.percentiles[2] })),
      evidenceFrameIndices,
      status: "measured",
    });
  }

  measurements.sort(
    (a, b) =>
      a.coordinate.alongRoadM - b.coordinate.alongRoadM ||
      a.coordinate.distanceFromRoadM - b.coordinate.distanceFromRoadM,
  );

  return {
    schemaVersion: GRASS_HEIGHT_ASSESSMENT_SCHEMA,
    runId,
    coordinateFrame: "road-local-metres",
    validationStatus: "unvalidated",
    band: {
      minDistanceFromRoadM: 0,
      maxDistanceFromRoadM: options.maxDistanceFromRoadM,
      cellSizeM: options.cellSizeM,
    },
    measurements,
    reviewEvidence: buildReviewEvidence(measurements),
    review: {
      status: "pending",
      checks: { correctGrassAndBand: null, plausibleHeightPattern: null, coverageUnderstood: null },
      reviewerId: null,
      reviewedAt: null,
      note: null,
    },
  };
}

/**
 * The bounded set of cells a person is asked to look at.
 *
 * A verge measured every half metre produces thousands of cells, and "check the result"
 * is then a request nobody can honestly say yes to. These five are the ones that would
 * expose the failures that actually happen: the extremes catch a mask that swallowed the
 * road or a pole, the median says whether the ordinary case is sane, the weakest measured
 * cell shows what the support threshold is letting through, and the first abstention shows
 * where the pipeline gave up. Everything is picked from the sorted measurements, so the
 * selection is a function of the numbers alone.
 */
function buildReviewEvidence(measurements: GrassCellMeasurement[]): GrassHeightAssessmentV1["reviewEvidence"] {
  const measured = measurements.filter((cell) => cell.status === "measured");
  const abstained = measurements.filter((cell) => cell.status === "insufficient-support");
  const total = measurements.length;

  // Folded rather than spread: a kilometre of verge is tens of thousands of cells, and
  // `Math.min(...cells)` passes every one as an argument until the stack runs out.
  const rangeOf = (values: number[]) =>
    values.length > 0
      ? values.reduce(
          (range, value) => ({ min: Math.min(range.min, value), max: Math.max(range.max, value) }),
          { min: Infinity, max: -Infinity },
        )
      : null;
  const h95RangeM = rangeOf(measured.map((cell) => cell.h95M as number));
  const extent95RangeM = rangeOf(measured.map((cell) => cell.extent95M as number));

  const samples: GrassReviewSample[] = [];
  const push = (cell: GrassCellMeasurement | undefined, reason: GrassReviewSampleReason) => {
    if (!cell) return;
    const duplicate = samples.some(
      (sample) =>
        sample.coordinate.alongRoadM === cell.coordinate.alongRoadM &&
        sample.coordinate.distanceFromRoadM === cell.coordinate.distanceFromRoadM,
    );
    if (duplicate) return;
    samples.push({ coordinate: cell.coordinate, reason, frameIndices: cell.evidenceFrameIndices });
  };

  if (measured.length > 0) {
    // Picked on the DEFAULT reading, so the cells a reviewer is sent to are the extremes of
    // the number the assessment leads with rather than of the plane-relative one beside it.
    // `reduce` with a strict comparison keeps the earliest cell in sorted order on a tie.
    const extentValues = measured.map((cell) => cell.extent95M as number);
    const lowest = measured.reduce((best, cell) => ((cell.extent95M as number) < (best.extent95M as number) ? cell : best));
    const highest = measured.reduce((best, cell) => ((cell.extent95M as number) > (best.extent95M as number) ? cell : best));
    const middle = median(extentValues);
    const nearestMedian = measured.reduce((best, cell) =>
      Math.abs((cell.extent95M as number) - middle) < Math.abs((best.extent95M as number) - middle) ? cell : best,
    );
    const weakest = measured.reduce((best, cell) =>
      cell.frameCount < best.frameCount || (cell.frameCount === best.frameCount && cell.sampleCount < best.sampleCount)
        ? cell
        : best,
    );
    push(lowest, "lowest-h95");
    push(nearestMedian, "median-h95");
    push(highest, "highest-h95");
    push(weakest, "lowest-support");
    push(measured.reduce((best, cell) => (cell.h95SpreadM ?? 0) > (best.h95SpreadM ?? 0) ? cell : best), "largest-disagreement");
  }
  push(abstained[0], "abstained");

  return {
    measuredCellCount: measured.length,
    abstainedCellCount: abstained.length,
    coverageFraction: total > 0 ? measured.length / total : 0,
    h95RangeM,
    extent95RangeM,
    samples,
  };
}

const ISO_8601 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;

/**
 * Record a person's plausibility decision, without letting them touch a number.
 *
 * The asymmetry is the point. Accepting requires all three checks to be true, because
 * "accepted" is what a dashboard aggregate will filter on. Rejecting requires a written
 * reason, because a rejection with no reason cannot be acted on and the calculation can
 * only be fixed upstream — from better masks or a better road edge — never by typing a
 * replacement height here.
 *
 * `validationStatus` is untouched on purpose. A human finding a result plausible is not
 * a field measurement, and the day this project has lawn ground truth is the day that
 * field is allowed to change.
 */
export function recordGrassHeightReview(
  assessment: GrassHeightAssessmentV1,
  decision: GrassHeightReviewDecision,
): GrassHeightAssessmentV1 {
  const { checks } = decision;
  if (decision.decision !== "accepted" && decision.decision !== "rejected") {
    throw new GrassHeightInputError(`unknown review decision "${decision.decision}"`);
  }
  for (const name of ["correctGrassAndBand", "plausibleHeightPattern", "coverageUnderstood"] as const) {
    if (typeof checks[name] !== "boolean") {
      throw new GrassHeightInputError(`review check "${name}" must be answered true or false`);
    }
  }
  if (typeof decision.reviewerId !== "string" || decision.reviewerId.trim() === "") {
    throw new GrassHeightInputError("a review must name its reviewer");
  }
  if (typeof decision.reviewedAt !== "string" || !ISO_8601.test(decision.reviewedAt)) {
    throw new GrassHeightInputError(`reviewedAt must be an ISO-8601 timestamp, got "${decision.reviewedAt}"`);
  }
  const note = decision.note?.trim() ?? "";

  if (decision.decision === "accepted") {
    const unmet = (["correctGrassAndBand", "plausibleHeightPattern", "coverageUnderstood"] as const).filter(
      (name) => !checks[name],
    );
    if (unmet.length > 0) {
      throw new GrassHeightInputError(
        `cannot accept with ${unmet.join(", ")} unmet — acceptance requires all three checks to be true. ` +
          "Reject with a note instead, and rerun from corrected masks or road edge.",
      );
    }
  } else if (note === "") {
    throw new GrassHeightInputError("a rejection must carry a note saying what was wrong");
  }

  return {
    ...assessment,
    review: {
      status: decision.decision,
      checks: { ...checks },
      reviewerId: decision.reviewerId,
      reviewedAt: decision.reviewedAt,
      note: note === "" ? null : note,
    },
  };
}
