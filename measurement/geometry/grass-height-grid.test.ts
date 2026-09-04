/**
 * The grass grid, checked against scenes whose answer is known exactly.
 *
 * Every scene here is built as a DEPTH MAP and a mask, not as a bag of points, so each
 * test runs the real path: resample, erode, drop the least confident fifth, reject depth
 * edges, backproject, transform by `worldFromDa3`, normalise to gravity, voxelise,
 * per-frame percentile, median across frames. A test that fed points straight in would
 * pass while the pipeline in front of them was wrong.
 *
 * The synthetic camera looks straight down and is deliberately NEAR-ORTHOGRAPHIC — five
 * kilometres up with a focal length to match. That is not physical, and it is not meant
 * to be: it makes a pixel column land on a fixed world position to within a millimetre,
 * which is what lets these tests state exact expected cell centres and exact percentiles
 * instead of tolerances. Real perspective is exercised by the recorded runs, not here.
 *
 * Heights are always multiples of 1/64 m, which survive float64 arithmetic and float32
 * storage unchanged, so `toBe` is a legitimate assertion rather than a lucky one.
 */

import { describe, expect, it } from "vitest";
import {
  GrassHeightInputError,
  measureGrassHeightGrid,
  measureGrassHeightGridStaged,
  recordGrassHeightReview,
  type GrassHeightAssessmentV1,
  type GrassHeightFrameInput,
  type GrassHeightGridInput,
  type GrassHeightProgress,
  type GrassProvenance,
  type GrassHeightReviewDecision,
} from "./grass-height-grid";
import type { Plane, Vec3 } from "./types";

const VOXEL_M = 0.02;
const CELL_M = 0.5;
const CAMERA_HEIGHT_M = 5000;
/** One pixel per voxel column, so a full cell is exactly 25 x 25 = 625 voxels. */
const PIXEL_M = VOXEL_M;
const VOXELS_PER_CELL = CELL_M / VOXEL_M;
/** Half a voxel, so no pixel centre ever lands on a voxel or cell boundary. */
const HALF_VOXEL = VOXEL_M / 2;

const GROUND: Plane = { normal: [0, 1, 0], offset: 0 };
const GRAVITY_UP: Vec3 = [0, 1, 0];
const IDENTITY_4X4 = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
/** Along +X at z = 0, six metres long: a whole number of cells, which reversal needs. */
const ROAD_EDGE: Vec3[] = [
  [0, 0, 0],
  [6, 0, 0],
];

interface SceneFrameOptions {
  frameIndex: number;
  /** World position of pixel (0, 0). Offset half a voxel by convention. */
  xMin: number;
  zMin: number;
  columns: number;
  rows: number;
  pixelM?: number;
  /** Rotate the camera 180 degrees about the vertical, which emits the same world points in reverse. */
  flipped?: boolean;
  /** World Y of the grass surface at this position, or null where there is no grass. */
  surfaceYAt: (x: number, z: number) => number | null;
  confidenceAt?: (x: number, z: number) => number;
}

/**
 * One nadir frame over a rectangle of ground.
 *
 * The principal point sits at pixel (0, 0) rather than the image centre. A real camera
 * does not, but it makes the pixel-to-world mapping a plain `x = xMin + u * pixelM`,
 * and the property under test is the measurement, not the intrinsics.
 */
function nadirFrame(options: SceneFrameOptions): GrassHeightFrameInput {
  const { frameIndex, xMin, zMin, columns, rows, surfaceYAt, confidenceAt } = options;
  const pixelM = options.pixelM ?? PIXEL_M;
  const flipped = options.flipped ?? false;
  const focal = CAMERA_HEIGHT_M / pixelM;

  const depth = new Float64Array(columns * rows);
  const confidence = new Float64Array(columns * rows);
  const grassMask = new Uint8Array(columns * rows);

  for (let v = 0; v < rows; v++) {
    for (let u = 0; u < columns; u++) {
      // Flipped cameras walk the same world grid from the far corner back.
      const x = xMin + (flipped ? columns - 1 - u : u) * pixelM;
      const z = zMin + (flipped ? rows - 1 - v : v) * pixelM;
      const index = v * columns + u;
      const surfaceY = surfaceYAt(x, z);
      // Non-grass pixels still carry ground-level depth: a hole would be a depth
      // discontinuity, and the mask is what decides what is measured.
      depth[index] = CAMERA_HEIGHT_M - (surfaceY ?? 0);
      confidence[index] = confidenceAt ? confidenceAt(x, z) : 1;
      grassMask[index] = surfaceY === null ? 0 : 1;
    }
  }

  const intrinsics = [focal, 0, 0, 0, focal, 0, 0, 0, 1];
  // Row-major 3x4 world->camera. Rows are the camera axes in world coordinates.
  const extrinsics = flipped
    ? [-1, 0, 0, xMin + (columns - 1) * pixelM, 0, 0, -1, zMin + (rows - 1) * pixelM, 0, -1, 0, CAMERA_HEIGHT_M]
    : [1, 0, 0, -xMin, 0, 0, 1, -zMin, 0, -1, 0, CAMERA_HEIGHT_M];

  return {
    frameIndex,
    geometryFrame: { depth, confidence, width: columns, height: rows, intrinsics, extrinsics },
    grassMask,
  };
}

/** The voxel column a world position falls in. Pixel centres are mid-voxel, so this is stable. */
function voxelColumn(x: number, z: number): [number, number] {
  return [Math.floor(x / VOXEL_M), Math.floor(z / VOXEL_M)];
}

/**
 * A height field that is constant across each voxel column.
 *
 * Constant per column on purpose: it makes the answer independent of how densely the
 * frame samples the ground, which is what the "a dense frame must not dominate" gate
 * asks about. Within one cell the 25 x 25 columns take each of 25 levels exactly 25
 * times, so the nearest-rank percentiles are arithmetic rather than approximation.
 */
function patternedSurface(baseFor: (x: number, z: number) => number | null) {
  return (x: number, z: number): number | null => {
    const base = baseFor(x, z);
    if (base === null) return null;
    const [vx, vz] = voxelColumn(x, z);
    return base + (((vx + vz) % 25) + 25) % 25 * (1 / 64);
  };
}

/** What a complete 625-voxel cell of `patternedSurface` reports, by nearest rank. */
const LEVEL = 1 / 64;
const EXPECTED_H50 = 12 * LEVEL; // rank 313 of 625 -> level 12
const EXPECTED_H90 = 22 * LEVEL; // rank 563 -> level 22
const EXPECTED_H95 = 23 * LEVEL; // rank 594 -> level 23

/**
 * Base height per along-road cell in the three-frame scene.
 *
 * Only cells with a neighbour on every side survive erosion complete, which here means
 * the three at 1.25 m, 1.75 m and 2.25 m along the road: 0.25 m, 0.5 m and 0.125 m of
 * grass respectively, before the pattern adds its 0 to 0.375 m.
 */
const SCENE_BASE = (x: number): number => (x < 1.5 ? 0.25 : x < 2.0 ? 0.5 : 0.125);

/** Cells that are complete, i.e. away from the eroded rim of the mask. */
function cellAt(assessment: GrassHeightAssessmentV1, alongRoadM: number, distanceFromRoadM: number) {
  return assessment.measurements.find(
    (cell) =>
      cell.coordinate.alongRoadM === alongRoadM && cell.coordinate.distanceFromRoadM === distanceFromRoadM,
  );
}

/**
 * A three-frame scene over x in [0.5, 3.0), z in [0.0, 1.5), padded by one cell.
 *
 * Padding matters: erosion removes the outer two pixels of the mask, so only cells with
 * a neighbour on every side hold all 625 of their voxels.
 */
function threeFrameScene(overrides: Partial<GrassHeightGridInput> = {}): GrassHeightGridInput {
  const surface = patternedSurface(SCENE_BASE);
  const frames = [0, 1, 2].map((frameIndex) =>
    nadirFrame({
      frameIndex,
      xMin: 0.5 + HALF_VOXEL,
      zMin: 0.0 + HALF_VOXEL,
      columns: 125, // 2.5 m along the road
      rows: 75, // 1.5 m out from it
      surfaceYAt: surface,
    }),
  );
  return {
    runId: "synthetic-three-frame",
    frames,
    worldFromDa3: IDENTITY_4X4,
    roadEdgeWorld: ROAD_EDGE,
    ground: { plane: GROUND, gravityUp: GRAVITY_UP, planeRmseM: 0.01 },
    ...overrides,
  };
}

describe("cell statistics", () => {
  it("reports exact nearest-rank H50, H90 and H95 for a complete cell", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    // x in [1.0, 1.5) has base 0.25; the cell at 0.75 m out is interior on every side.
    const cell = cellAt(assessment, 1.25, 0.75);
    expect(cell?.status).toBe("measured");
    expect(cell?.h50M).toBe(0.25 + EXPECTED_H50);
    expect(cell?.h90M).toBe(0.25 + EXPECTED_H90);
    expect(cell?.h95M).toBe(0.25 + EXPECTED_H95);
    expect(cell?.frameCount).toBe(3);
    expect(cell?.sampleCount).toBe(3 * VOXELS_PER_CELL * VOXELS_PER_CELL);
  });

  it("keeps h50 <= h90 <= h95 in every measured cell", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    const measured = assessment.measurements.filter((cell) => cell.status === "measured");
    expect(measured.length).toBeGreaterThan(4);
    for (const cell of measured) {
      expect(cell.h50M as number).toBeLessThanOrEqual(cell.h90M as number);
      expect(cell.h90M as number).toBeLessThanOrEqual(cell.h95M as number);
    }
  });

  it("gives every frame one vote, so a minority of frames cannot move the median", () => {
    const surface = patternedSurface(() => 0.25);
    const liar = patternedSurface(() => 0.25 + 1.0);
    const frames = [
      nadirFrame({ frameIndex: 0, xMin: 0.5 + HALF_VOXEL, zMin: HALF_VOXEL, columns: 100, rows: 75, surfaceYAt: surface }),
      nadirFrame({ frameIndex: 1, xMin: 0.5 + HALF_VOXEL, zMin: HALF_VOXEL, columns: 100, rows: 75, surfaceYAt: surface }),
      nadirFrame({ frameIndex: 2, xMin: 0.5 + HALF_VOXEL, zMin: HALF_VOXEL, columns: 100, rows: 75, surfaceYAt: surface }),
      nadirFrame({ frameIndex: 3, xMin: 0.5 + HALF_VOXEL, zMin: HALF_VOXEL, columns: 100, rows: 75, surfaceYAt: liar }),
      nadirFrame({ frameIndex: 4, xMin: 0.5 + HALF_VOXEL, zMin: HALF_VOXEL, columns: 100, rows: 75, surfaceYAt: liar }),
    ];
    const assessment = measureGrassHeightGrid({ ...threeFrameScene(), frames });
    const cell = cellAt(assessment, 1.25, 0.75);
    expect(cell?.frameCount).toBe(5);
    expect(cell?.h95M).toBe(0.25 + EXPECTED_H95);
    // The three agreeing frames are the evidence, not the two outliers.
    expect(cell?.evidenceFrameIndices).toEqual([0, 1, 2]);
  });

  it("never lets an unmasked tall object into the answer", () => {
    const grass = patternedSurface(() => 0.25);
    // A two-metre pole through the middle of the patch, absent from the grass mask.
    const withPole = (x: number, z: number): number | null =>
      x > 1.2 && x < 1.3 && z > 0.7 && z < 0.8 ? null : grass(x, z);
    const scene = threeFrameScene();
    const frames = [0, 1, 2].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 0.5 + HALF_VOXEL,
        zMin: HALF_VOXEL,
        columns: 125,
        rows: 75,
        surfaceYAt: withPole,
      }),
    );
    // The pole's pixels carry a 2 m depth step but are masked out, so they cannot be measured.
    for (const frame of frames) {
      const depth = frame.geometryFrame.depth as Float64Array;
      for (let i = 0; i < depth.length; i++) if (!frame.grassMask[i]) depth[i] = CAMERA_HEIGHT_M - 2;
    }
    const assessment = measureGrassHeightGrid({ ...scene, frames });
    for (const cell of assessment.measurements) {
      if (cell.status !== "measured") continue;
      expect(cell.h95M as number).toBeLessThan(1.0);
    }
  });
});

describe("the measured band", () => {
  /** A wide, shallow strip that straddles the five-metre edge of the band. */
  function bandScene(): GrassHeightGridInput {
    const frames = [0, 1, 2].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 1.0 + HALF_VOXEL,
        zMin: 4.0 + HALF_VOXEL,
        columns: 100, // x in [1.01, 2.99]
        rows: 100, // z in [4.01, 5.99]
        surfaceYAt: patternedSurface(() => 0.25),
      }),
    );
    return {
      runId: "synthetic-band",
      frames,
      worldFromDa3: IDENTITY_4X4,
      roadEdgeWorld: ROAD_EDGE,
      ground: { plane: GROUND, gravityUp: GRAVITY_UP, planeRmseM: 0.01 },
    };
  }

  it("includes grass at 4.99 m and excludes it beyond 5.0 m", () => {
    const assessment = measureGrassHeightGrid(bandScene());
    const distances = new Set(assessment.measurements.map((cell) => cell.coordinate.distanceFromRoadM));
    // 4.99 m lands in the cell spanning [4.5, 5.0), whose centre is 4.75.
    expect(distances.has(4.75)).toBe(true);
    // Everything at 5.01 m and beyond is dropped, so no cell exists past the band.
    for (const distance of distances) expect(distance).toBeLessThan(5.0);
    expect(assessment.band.maxDistanceFromRoadM).toBe(5.0);
    expect(assessment.band.minDistanceFromRoadM).toBe(0);
  });

  it("honours a narrower band without changing the cells inside it", () => {
    const wide = measureGrassHeightGrid(bandScene());
    const narrow = measureGrassHeightGrid({ ...bandScene(), options: { maxDistanceFromRoadM: 4.5 } });
    const narrowDistances = narrow.measurements.map((cell) => cell.coordinate.distanceFromRoadM);
    expect(Math.max(...narrowDistances)).toBeLessThan(4.5);
    const shared = cellAt(wide, 2.25, 4.25);
    expect(cellAt(narrow, 2.25, 4.25)?.h95M).toBe(shared?.h95M);
  });
});

describe("road-local coordinates", () => {
  it("is unchanged by translating and rotating the whole scene", () => {
    const scene = threeFrameScene();
    const upright = measureGrassHeightGrid(scene);

    // A rigid transform applied where the pipeline already applies one, so the camera,
    // the road edge, the plane and gravity all move together.
    const angle = Math.PI / 7;
    const c = Math.cos(angle);
    const s = Math.sin(angle);
    // Rotate about +Z, then translate. Row-major 4x4.
    const rotate = (p: Vec3): Vec3 => [c * p[0] - s * p[1], s * p[0] + c * p[1], p[2]];
    const shift: Vec3 = [12.5, -3.25, 40];
    const worldFromDa3 = [c, -s, 0, shift[0], s, c, 0, shift[1], 0, 0, 1, shift[2], 0, 0, 0, 1];

    const normal = rotate(GROUND.normal);
    const moved = measureGrassHeightGrid({
      ...scene,
      worldFromDa3,
      roadEdgeWorld: scene.roadEdgeWorld.map((vertex) => {
        const r = rotate(vertex);
        return [r[0] + shift[0], r[1] + shift[1], r[2] + shift[2]] as Vec3;
      }),
      ground: {
        plane: {
          normal,
          offset: GROUND.offset - (normal[0] * shift[0] + normal[1] * shift[1] + normal[2] * shift[2]),
        },
        gravityUp: rotate(GRAVITY_UP),
        planeRmseM: 0.01,
      },
    });

    expect(moved.measurements.length).toBe(upright.measurements.length);
    for (const [index, cell] of moved.measurements.entries()) {
      const original = upright.measurements[index];
      expect(cell.coordinate).toEqual(original.coordinate);
      expect(cell.status).toBe(original.status);
      if (cell.status !== "measured") continue;
      // Float32 storage of a rotated coordinate is the only difference, and it is tiny.
      expect(cell.h95M as number).toBeCloseTo(original.h95M as number, 5);
      expect(cell.frameCount).toBe(original.frameCount);
    }
  });

  it("reverses the along-road axis when the road edge is reversed", () => {
    const scene = threeFrameScene();
    const forward = measureGrassHeightGrid(scene);
    const backward = measureGrassHeightGrid({ ...scene, roadEdgeWorld: [...scene.roadEdgeWorld].reverse() });

    const roadLength = 6;
    expect(backward.measurements.length).toBe(forward.measurements.length);
    for (const cell of forward.measurements) {
      const mirrored = cellAt(backward, roadLength - cell.coordinate.alongRoadM, cell.coordinate.distanceFromRoadM);
      expect(mirrored).toBeDefined();
      expect(mirrored?.h95M).toBe(cell.h95M);
      expect(mirrored?.sampleCount).toBe(cell.sampleCount);
    }
  });

  it("sorts cells by along-road, then by distance from the road", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    for (let i = 1; i < assessment.measurements.length; i++) {
      const previous = assessment.measurements[i - 1].coordinate;
      const current = assessment.measurements[i].coordinate;
      const ordered =
        previous.alongRoadM < current.alongRoadM ||
        (previous.alongRoadM === current.alongRoadM && previous.distanceFromRoadM < current.distanceFromRoadM);
      expect(ordered).toBe(true);
    }
  });

  it("refuses a road edge that cannot define a direction", () => {
    const scene = threeFrameScene();
    expect(() => measureGrassHeightGrid({ ...scene, roadEdgeWorld: [[0, 0, 0]] })).toThrow(GrassHeightInputError);
    expect(() =>
      measureGrassHeightGrid({
        ...scene,
        roadEdgeWorld: [
          [1, 0, 2],
          [1, 0, 2],
        ],
      }),
    ).toThrow(/zero length/);
    expect(() =>
      measureGrassHeightGrid({
        ...scene,
        roadEdgeWorld: [
          [0, 0, 0],
          [Number.NaN, 0, 1],
        ],
      }),
    ).toThrow(/finite/);
  });

  it("refuses a ground plane that is edge-on to gravity", () => {
    const scene = threeFrameScene();
    expect(() =>
      measureGrassHeightGrid({
        ...scene,
        ground: { plane: { normal: [1, 0, 0], offset: 0 }, gravityUp: GRAVITY_UP, planeRmseM: 0.01 },
      }),
    ).toThrow(/edge-on to gravity/);
  });
});

describe("the vertical", () => {
  it("measures along gravity, not along the plane normal", () => {
    // A ground tilted by 10 degrees about +Z, with grass exactly 0.25 m above it,
    // measured straight up. Along the plane normal the same points read 0.25 * cos 10.
    const tilt = (25 * Math.PI) / 180;
    const normal: Vec3 = [Math.sin(tilt), Math.cos(tilt), 0];
    const grassAbove = 0.25;
    const frames = [0, 1, 2].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 1.0 + HALF_VOXEL,
        zMin: 1.0 + HALF_VOXEL,
        columns: 75,
        rows: 75,
        // World Y of a surface `grassAbove` metres vertically over the tilted plane.
        surfaceYAt: (x) => grassAbove - x * Math.tan(tilt),
      }),
    );
    const assessment = measureGrassHeightGrid({
      runId: "synthetic-tilt",
      frames,
      worldFromDa3: IDENTITY_4X4,
      roadEdgeWorld: ROAD_EDGE,
      ground: { plane: { normal, offset: 0 }, gravityUp: GRAVITY_UP, planeRmseM: 0.01 },
    });
    const cell = cellAt(assessment, 1.75, 1.75);
    expect(cell?.status).toBe("measured");
    expect(cell?.h95M as number).toBeCloseTo(grassAbove, 4);
    // Measured along the plane normal instead, the same points read 0.227 m: 2.3 cm
    // lower, on grass that is only 25 cm tall.
    expect(cell?.h95M as number).toBeGreaterThan(grassAbove * Math.cos(tilt) + 0.01);
  });

  it("flattens shallow negative residuals and drops deep ones", () => {
    const scene = threeFrameScene();
    const rmse = 0.01;
    const frames = [0, 1, 2].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 1.0 + HALF_VOXEL,
        zMin: 1.0 + HALF_VOXEL,
        columns: 75,
        rows: 75,
        // Half the columns sit 1 cm below the plane (inside 3 RMSE, so clamped to zero),
        // the other half sit 1 m below it (a reconstruction artefact, so dropped).
        surfaceYAt: (x) => {
          const [vx] = voxelColumn(x, 0);
          return vx % 2 === 0 ? -0.01 : -1;
        },
      }),
    );
    const assessment = measureGrassHeightGrid({
      ...scene,
      frames,
      ground: { plane: GROUND, gravityUp: GRAVITY_UP, planeRmseM: rmse },
    });
    const cell = cellAt(assessment, 1.75, 1.75);
    expect(cell?.status).toBe("measured");
    expect(cell?.h50M).toBe(0);
    expect(cell?.h95M).toBe(0);
    // Only the clamped columns survived: 12 of the cell's 25, times its 25 rows.
    expect(cell?.sampleCount).toBe(3 * 12 * VOXELS_PER_CELL);
  });
});

describe("support and abstention", () => {
  it("abstains with too-few-frames when only some frames saw enough", () => {
    const surface = patternedSurface(() => 0.25);
    const frames = [0, 1].map((frameIndex) =>
      nadirFrame({ frameIndex, xMin: 1.0 + HALF_VOXEL, zMin: 1.0 + HALF_VOXEL, columns: 75, rows: 75, surfaceYAt: surface }),
    );
    const assessment = measureGrassHeightGrid({ ...threeFrameScene(), frames });
    const cell = cellAt(assessment, 1.75, 1.75);
    expect(cell?.status).toBe("insufficient-support");
    expect(cell?.reason).toBe("too-few-frames");
    expect(cell?.h50M).toBeNull();
    expect(cell?.h90M).toBeNull();
    expect(cell?.h95M).toBeNull();
    expect(cell?.frameCount).toBe(2);
    expect(cell?.evidenceFrameIndices).toEqual([0, 1]);
  });

  it("abstains with too-few-samples when no frame saw enough of a cell", () => {
    // A three-pixel-wide sliver: 3 x 3 = 9 voxels per cell per frame, under the floor of 20.
    const frames = [0, 1, 2, 3].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 1.0 + HALF_VOXEL,
        zMin: 1.0 + HALF_VOXEL,
        columns: 7,
        rows: 7,
        surfaceYAt: patternedSurface(() => 0.25),
      }),
    );
    const assessment = measureGrassHeightGrid({ ...threeFrameScene(), frames });
    expect(assessment.measurements).toHaveLength(1);
    const [cell] = assessment.measurements;
    expect(cell.status).toBe("insufficient-support");
    expect(cell.reason).toBe("too-few-samples");
    expect(cell.frameCount).toBe(0);
    expect(cell.sampleCount).toBe(0);
    expect(cell.evidenceFrameIndices).toEqual([0, 1, 2]);
  });

  it("counts coverage over the cells it observed, and says which they were", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    const { measuredCellCount, abstainedCellCount, coverageFraction } = assessment.reviewEvidence;
    expect(measuredCellCount + abstainedCellCount).toBe(assessment.measurements.length);
    expect(coverageFraction).toBeCloseTo(measuredCellCount / assessment.measurements.length, 12);
  });
});

describe("repeatability", () => {
  it("is unmoved by a frame that samples the same ground 25 times as densely", () => {
    const surface = patternedSurface(() => 0.25);
    const coarse = [0, 1, 2].map((frameIndex) =>
      nadirFrame({ frameIndex, xMin: 1.0 + HALF_VOXEL, zMin: 1.0 + HALF_VOXEL, columns: 75, rows: 75, surfaceYAt: surface }),
    );
    // Five pixels per voxel edge over the same rectangle: 25 observations per voxel column.
    const dense = [0, 1, 2].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 1.0 + VOXEL_M / 10,
        zMin: 1.0 + VOXEL_M / 10,
        columns: 375,
        rows: 375,
        pixelM: VOXEL_M / 5,
        surfaceYAt: surface,
      }),
    );
    const base = threeFrameScene();
    const sparseResult = measureGrassHeightGrid({ ...base, frames: coarse });
    const denseResult = measureGrassHeightGrid({ ...base, frames: dense });
    const sparseCell = cellAt(sparseResult, 1.75, 1.75);
    const denseCell = cellAt(denseResult, 1.75, 1.75);
    expect(denseCell?.h50M).toBe(sparseCell?.h50M);
    expect(denseCell?.h90M).toBe(sparseCell?.h90M);
    expect(denseCell?.h95M).toBe(sparseCell?.h95M);
    // 25x the pixels collapse to the same voxels, so even the support is identical.
    expect(denseCell?.sampleCount).toBe(sparseCell?.sampleCount);
  });

  it("gives identical output whatever order the points and frames arrive in", () => {
    const scene = threeFrameScene();
    const forward = measureGrassHeightGrid(scene);

    // Same world points, emitted from the far corner backwards by a camera turned around.
    const surface = patternedSurface(SCENE_BASE);
    const flippedFrames = [2, 0, 1].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 0.5 + HALF_VOXEL,
        zMin: HALF_VOXEL,
        columns: 125,
        rows: 75,
        flipped: true,
        surfaceYAt: surface,
      }),
    );
    const shuffled = measureGrassHeightGrid({ ...scene, frames: flippedFrames });

    expect(JSON.stringify(shuffled.measurements)).toBe(JSON.stringify(forward.measurements));
    expect(JSON.stringify(shuffled.reviewEvidence)).toBe(JSON.stringify(forward.reviewEvidence));
  });

  it("does not drift when run again", () => {
    const scene = threeFrameScene();
    expect(JSON.stringify(measureGrassHeightGrid(scene))).toBe(JSON.stringify(measureGrassHeightGrid(scene)));
  });

  it("refuses the same frame twice, because one frame gets one vote", () => {
    const scene = threeFrameScene();
    const frames = [...scene.frames, scene.frames[0]];
    expect(() => measureGrassHeightGrid({ ...scene, frames })).toThrow(/appears twice/);
  });
});

describe("frame preparation", () => {
  it("drops the least confident fifth of each frame's masked pixels", () => {
    const surface = patternedSurface(() => 0.25);
    // Confidence rises with the voxel column, so the lowest fifth is a known set of columns.
    const confidenceAt = (x: number) => voxelColumn(x, 0)[0];
    const frames = [0, 1, 2].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 1.0 + HALF_VOXEL,
        zMin: 1.0 + HALF_VOXEL,
        columns: 75,
        rows: 75,
        surfaceYAt: surface,
        confidenceAt,
      }),
    );
    const withConfidence = measureGrassHeightGrid({ ...threeFrameScene(), frames });
    const flat = measureGrassHeightGrid({
      ...threeFrameScene(),
      frames: [0, 1, 2].map((frameIndex) =>
        nadirFrame({ frameIndex, xMin: 1.0 + HALF_VOXEL, zMin: 1.0 + HALF_VOXEL, columns: 75, rows: 75, surfaceYAt: surface }),
      ),
    });
    // The first cell along the road loses its low-confidence columns; the last keeps all of them.
    const cut = cellAt(withConfidence, 1.25, 1.75);
    const kept = cellAt(withConfidence, 1.75, 1.75);
    expect(cut?.sampleCount).toBeLessThan(cellAt(flat, 1.25, 1.75)?.sampleCount as number);
    expect(kept?.sampleCount).toBe(cellAt(flat, 1.75, 1.75)?.sampleCount);
  });

  it("resamples a mask that was painted at another resolution", () => {
    const scene = threeFrameScene();
    const frames = scene.frames.map((frame) => {
      const { width, height } = frame.geometryFrame;
      // The same mask at half resolution, which nearest-neighbour must restore.
      const half = new Uint8Array(Math.floor(width / 2) * Math.floor(height / 2));
      half.fill(1);
      return {
        ...frame,
        grassMask: half,
        maskWidth: Math.floor(width / 2),
        maskHeight: Math.floor(height / 2),
      };
    });
    const assessment = measureGrassHeightGrid({ ...scene, frames });
    expect(cellAt(assessment, 1.25, 0.75)?.h95M).toBe(0.25 + EXPECTED_H95);
  });

  it("refuses a mask whose size it cannot know", () => {
    const scene = threeFrameScene();
    const frames = scene.frames.map((frame) => ({ ...frame, grassMask: new Uint8Array(17) }));
    expect(() => measureGrassHeightGrid({ ...scene, frames })).toThrow(/Pass maskWidth and maskHeight/);
  });

  it("refuses a worldFromDa3 that is not a 4x4", () => {
    expect(() => measureGrassHeightGrid({ ...threeFrameScene(), worldFromDa3: [1, 0, 0] })).toThrow(/4x4/);
  });
});

describe("review evidence", () => {
  /**
   * Four cells whose lowest, median, highest and weakest are four DIFFERENT cells.
   *
   * They have to be different, or deduplication would collapse two samples into one and
   * the test would pass without ever exercising the fourth rule. The rightmost cell is
   * deliberately two pixel columns narrower, which makes it the weakest support without
   * touching any height.
   */
  function ladderScene(): GrassHeightGridInput {
    const base = (x: number) => (x < 1.5 ? 0.25 : x < 2.0 ? 0.5 : x < 2.5 ? 0.0625 : 0.375);
    const frames = [0, 1, 2].map((frameIndex) =>
      nadirFrame({
        frameIndex,
        xMin: 1.0 + HALF_VOXEL,
        zMin: 1.0 + HALF_VOXEL,
        columns: 98,
        rows: 25,
        surfaceYAt: patternedSurface(base),
      }),
    );
    return {
      runId: "synthetic-ladder",
      frames,
      worldFromDa3: IDENTITY_4X4,
      roadEdgeWorld: ROAD_EDGE,
      ground: { plane: GROUND, gravityUp: GRAVITY_UP, planeRmseM: 0.01 },
    };
  }

  it("picks the lowest, median, highest, weakest and first abstained cell, in that order", () => {
    const assessment = measureGrassHeightGrid(ladderScene());
    const reasons = assessment.reviewEvidence.samples.map((sample) => sample.reason);
    expect(reasons.slice(0, 3)).toEqual(["lowest-h95", "median-h95", "highest-h95"]);
    expect(reasons).toContain("lowest-support");

    const measured = assessment.measurements.filter((cell) => cell.status === "measured");
    const h95 = measured.map((cell) => cell.h95M as number);
    const lowest = assessment.reviewEvidence.samples[0];
    const highest = assessment.reviewEvidence.samples.find((sample) => sample.reason === "highest-h95");
    expect(cellAt(assessment, lowest.coordinate.alongRoadM, lowest.coordinate.distanceFromRoadM)?.h95M).toBe(
      Math.min(...h95),
    );
    expect(
      cellAt(assessment, highest?.coordinate.alongRoadM as number, highest?.coordinate.distanceFromRoadM as number)
        ?.h95M,
    ).toBe(Math.max(...h95));
    expect(assessment.reviewEvidence.h95RangeM).toEqual({ min: Math.min(...h95), max: Math.max(...h95) });
  });

  it("names an abstained cell when one exists, and no duplicate coordinates ever", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    const abstained = assessment.measurements.find((cell) => cell.status === "insufficient-support");
    const sample = assessment.reviewEvidence.samples.find((entry) => entry.reason === "abstained");
    if (abstained) {
      expect(sample?.coordinate).toEqual(abstained.coordinate);
    } else {
      expect(sample).toBeUndefined();
    }
    const keys = assessment.reviewEvidence.samples.map(
      (entry) => `${entry.coordinate.alongRoadM},${entry.coordinate.distanceFromRoadM}`,
    );
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.length).toBeLessThanOrEqual(5);
  });

  it("hands each sample the frames a reviewer should open", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    for (const sample of assessment.reviewEvidence.samples) {
      expect(sample.frameIndices.length).toBeGreaterThan(0);
      expect(sample.frameIndices.length).toBeLessThanOrEqual(3);
    }
  });
});

describe("human review", () => {
  const ACCEPTED_CHECKS = {
    correctGrassAndBand: true,
    plausibleHeightPattern: true,
    coverageUnderstood: true,
  };
  const decision = (overrides: Partial<GrassHeightReviewDecision> = {}): GrassHeightReviewDecision => ({
    decision: "accepted",
    checks: ACCEPTED_CHECKS,
    reviewerId: "operator-7",
    reviewedAt: "2026-09-03T09:15:00Z",
    ...overrides,
  });

  it("starts every calculation pending, with no reviewer", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    expect(assessment.review.status).toBe("pending");
    expect(assessment.review.checks).toEqual({
      correctGrassAndBand: null,
      plausibleHeightPattern: null,
      coverageUnderstood: null,
    });
    expect(assessment.review.reviewerId).toBeNull();
    expect(assessment.review.reviewedAt).toBeNull();
    expect(assessment.review.note).toBeNull();
    expect(assessment.validationStatus).toBe("unvalidated");
    expect(assessment.schemaVersion).toBe("verge.grass-height-assessment/0.1.0");
    expect(assessment.coordinateFrame).toBe("road-local-metres");
  });

  it("accepts only when all three checks are true", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    const accepted = recordGrassHeightReview(assessment, decision());
    expect(accepted.review.status).toBe("accepted");
    expect(accepted.review.reviewerId).toBe("operator-7");

    for (const failing of ["correctGrassAndBand", "plausibleHeightPattern", "coverageUnderstood"] as const) {
      expect(() =>
        recordGrassHeightReview(assessment, decision({ checks: { ...ACCEPTED_CHECKS, [failing]: false } })),
      ).toThrow(new RegExp(failing));
    }
  });

  it("refuses a rejection with nothing written down", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    expect(() =>
      recordGrassHeightReview(assessment, decision({ decision: "rejected", checks: { ...ACCEPTED_CHECKS, correctGrassAndBand: false } })),
    ).toThrow(/must carry a note/);
    expect(() =>
      recordGrassHeightReview(
        assessment,
        decision({ decision: "rejected", checks: { ...ACCEPTED_CHECKS, correctGrassAndBand: false }, note: "   " }),
      ),
    ).toThrow(/must carry a note/);

    const rejected = recordGrassHeightReview(
      assessment,
      decision({
        decision: "rejected",
        checks: { ...ACCEPTED_CHECKS, correctGrassAndBand: false },
        note: "grass mask includes shrubs",
      }),
    );
    expect(rejected.review.status).toBe("rejected");
    expect(rejected.review.note).toBe("grass mask includes shrubs");
  });

  it("refuses a review with no reviewer or a timestamp that is not ISO-8601", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    expect(() => recordGrassHeightReview(assessment, decision({ reviewerId: "  " }))).toThrow(/name its reviewer/);
    expect(() => recordGrassHeightReview(assessment, decision({ reviewedAt: "3 September" }))).toThrow(/ISO-8601/);
  });

  it("changes nothing but the review, in either direction", () => {
    const assessment = measureGrassHeightGrid(threeFrameScene());
    const before = JSON.stringify(assessment.measurements);
    const evidenceBefore = JSON.stringify(assessment.reviewEvidence);

    const accepted = recordGrassHeightReview(assessment, decision());
    const rejected = recordGrassHeightReview(
      assessment,
      decision({
        decision: "rejected",
        checks: { ...ACCEPTED_CHECKS, plausibleHeightPattern: false },
        note: "highest cell is caused by a pole",
      }),
    );

    for (const result of [accepted, rejected]) {
      expect(JSON.stringify(result.measurements)).toBe(before);
      expect(JSON.stringify(result.reviewEvidence)).toBe(evidenceBefore);
      expect(result.validationStatus).toBe("unvalidated");
      expect(result.band).toEqual(assessment.band);
      expect(result.runId).toBe(assessment.runId);
    }
    // The original is untouched: reviewing returns a new object.
    expect(assessment.review.status).toBe("pending");
  });

  it("survives a round trip through JSON", () => {
    const assessment = recordGrassHeightReview(measureGrassHeightGrid(threeFrameScene()), decision());
    const restored = JSON.parse(JSON.stringify(assessment)) as GrassHeightAssessmentV1;
    expect(restored).toEqual(assessment);
    expect(restored.measurements[0].coordinate).toEqual(assessment.measurements[0].coordinate);
  });
});

describe("watching it happen", () => {
  it("reaches the same answer one frame at a time as it does in one call", () => {
    const scene = threeFrameScene();
    const steps = measureGrassHeightGridStaged(scene);
    let step = steps.next();
    while (!step.done) step = steps.next();
    // Byte-for-byte, because the staged path is the one-shot path — not a second
    // implementation that could drift from it.
    expect(JSON.stringify(step.value)).toBe(JSON.stringify(measureGrassHeightGrid(scene)));
  });

  it("counts frames, observations and cells, and never counts down", () => {
    const scene = threeFrameScene();
    const progress = [...measureGrassHeightGridStaged(scene)];

    expect(progress.map((p) => p.phase)).toEqual([
      "reading-frames",
      "reading-frames",
      "reading-frames",
      "gridding",
      "done",
    ]);
    expect(progress.map((p) => p.framesDone)).toEqual([1, 2, 3, 3, 3]);
    for (const step of progress) expect(step.frameTotal).toBe(3);
    expect(progress.slice(0, 3).map((p) => p.frameIndex)).toEqual([0, 1, 2]);

    for (let i = 1; i < progress.length; i++) {
      expect(progress[i].observationsRetained).toBeGreaterThanOrEqual(progress[i - 1].observationsRetained);
      expect(progress[i].cellsTouched).toBeGreaterThanOrEqual(progress[i - 1].cellsTouched);
    }
    // Every frame in this scene sees the same ground, so each contributes real evidence.
    for (const step of progress.slice(0, 3)) expect(step.frameObservations).toBeGreaterThan(0);
  });

  it("carries the finished assessment on the last step and nowhere earlier", () => {
    const progress = [...measureGrassHeightGridStaged(threeFrameScene())];
    const last = progress[progress.length - 1];
    expect(last.phase).toBe("done");
    expect(last.assessment?.review.status).toBe("pending");
    expect(last.assessment?.validationStatus).toBe("unvalidated");
    expect(last.cellsTouched).toBe(last.assessment?.measurements.length);
    for (const step of progress.slice(0, -1)) expect(step.assessment).toBeNull();
  });

  it("refuses bad input before yielding anything at all", () => {
    const scene = threeFrameScene();
    const steps = measureGrassHeightGridStaged({ ...scene, roadEdgeWorld: [[0, 0, 0]] });
    // A generator body does not run until the first next(), so the throw must land there
    // rather than leaving a consumer to draw a progress row for a run that cannot start.
    expect(() => steps.next()).toThrow(GrassHeightInputError);
  });
});

describe("provenance", () => {
  /** Drain the staged calculation and hand back its closing step. */
  function finalStep(scene: GrassHeightGridInput, collectProvenance: boolean): GrassHeightProgress {
    const steps = measureGrassHeightGridStaged(scene, { collectProvenance });
    const progress = [...steps];
    return progress[progress.length - 1];
  }

  function cellProvenance(
    provenance: GrassProvenance,
    coordinate: { alongRoadM: number; distanceFromRoadM: number },
  ) {
    for (const entry of provenance.values()) {
      if (
        entry.coordinate.alongRoadM === coordinate.alongRoadM &&
        entry.coordinate.distanceFromRoadM === coordinate.distanceFromRoadM
      ) {
        return entry;
      }
    }
    return undefined;
  }

  it("costs nothing and returns nothing unless asked for", () => {
    const scene = threeFrameScene();
    expect(finalStep(scene, false).provenance).toBeNull();
    expect(finalStep(scene, true).provenance).not.toBeNull();
  });

  it("does not change a single measured number", () => {
    const scene = threeFrameScene();
    expect(JSON.stringify(finalStep(scene, true).assessment)).toBe(
      JSON.stringify(measureGrassHeightGrid(scene)),
    );
  });

  it("attributes every pixel to the cell it actually landed in", () => {
    const provenance = finalStep(threeFrameScene(), true).provenance as GrassProvenance;

    // The scene's frames are the plain nadir grid, so a pixel's world position is arithmetic
    // this test can do without the pipeline's help. That makes "is this pixel really in this
    // cell" an independent check rather than a restatement of the code under test.
    const columns = 125;
    const xMin = 0.5 + HALF_VOXEL;
    const zMin = 0.0 + HALF_VOXEL;

    let checked = 0;
    for (const cell of provenance.values()) {
      const alongLow = cell.coordinate.alongRoadM - CELL_M / 2;
      const distLow = cell.coordinate.distanceFromRoadM - CELL_M / 2;
      for (const pixels of cell.pixelsByFrame.values()) {
        for (const index of pixels) {
          const x = xMin + (index % columns) * PIXEL_M;
          const z = zMin + Math.floor(index / columns) * PIXEL_M;
          expect(x).toBeGreaterThanOrEqual(alongLow);
          expect(x).toBeLessThan(alongLow + CELL_M);
          expect(z).toBeGreaterThanOrEqual(distLow);
          expect(z).toBeLessThan(distLow + CELL_M);
          checked += 1;
        }
      }
    }
    expect(checked).toBeGreaterThan(1000);
  });

  it("covers every cell, with at least as many pixels as the cell counted samples", () => {
    const step = finalStep(threeFrameScene(), true);
    const provenance = step.provenance as GrassProvenance;
    const assessment = step.assessment as GrassHeightAssessmentV1;

    expect(provenance.size).toBe(assessment.measurements.length);
    for (const cell of assessment.measurements) {
      const entry = cellProvenance(provenance, cell.coordinate);
      expect(entry).toBeDefined();
      // Pixels are counted before voxel deduplication, so they can only outnumber the voxels.
      expect(entry?.pixelCount).toBeGreaterThanOrEqual(cell.sampleCount);
      expect(entry?.pixelsByFrame.size ?? 0).toBeGreaterThan(0);
    }
  });

  it("sorts each frame's pixels into raster order", () => {
    const provenance = finalStep(threeFrameScene(), true).provenance as GrassProvenance;
    for (const cell of provenance.values()) {
      for (const pixels of cell.pixelsByFrame.values()) {
        for (let i = 1; i < pixels.length; i++) expect(pixels[i]).toBeGreaterThan(pixels[i - 1]);
      }
    }
  });
});
