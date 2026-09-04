/**
 * Assembling a grass-grid run out of what the app already has.
 *
 * `geometry/grass-height-grid.ts` asks for four things: frames with grass masks, the
 * accepted ground plane, the transform into display space, and an ordered road-edge
 * polyline. The first three are already on screen. The fourth does not exist anywhere in
 * this project, because nothing segments a road — so this file supplies a stand-in and is
 * loud about it being one.
 *
 * **The camera track is not the road edge.** It is where the vehicle drove. On a clip shot
 * from a car those differ by roughly a lane width and a constant, which is why the offset
 * below is a parameter rather than a guess baked into the code. Every readout that shows a
 * measurement resting on it has to say so: a band placed from the camera path is a band
 * placed by an assumption, and the whole point of the overlay is to let somebody look at
 * that assumption instead of trusting it.
 *
 * Pure functions only. The store drives them; the panes draw what comes back.
 */

import {
  basisFromUp,
  cross,
  dot,
  normalize,
  signedHeight,
  type Plane,
  type Vec3,
} from "../../../geometry";

/**
 * Metres of travel between kept vertices.
 *
 * The track has one pose per frame — 94 of them on the grass run, a few centimetres apart
 * while the camera is walking. Handing all of them over makes a polyline whose vertices are
 * closer together than the noise between them, so the arc length it reports wanders. Half a
 * metre is one cell of the default grid: finer than the thing being measured, coarser than
 * the jitter.
 */
const VERTEX_SPACING_M = 0.5;

export interface RoadEdgeFromTrack {
  polyline: Vec3[];
  /** Metres of polyline, after projection onto the plane. */
  lengthM: number;
  /** Poses that survived the spacing rule, out of those given. */
  keptPoses: number;
  sourcePoses: number;
  offsetM: number;
}

export class RoadEdgeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RoadEdgeError";
  }
}

/** Drop a point onto the plane along the plane's own normal. */
function ontoPlane(point: Vec3, plane: Plane): Vec3 {
  const h = signedHeight(plane, point);
  return [point[0] - plane.normal[0] * h, point[1] - plane.normal[1] * h, point[2] - plane.normal[2] * h];
}

/**
 * A road-parallel polyline from the reconstructed camera path.
 *
 * The path is flattened onto the ground plane, thinned to `VERTEX_SPACING_M`, then pushed
 * sideways by `offsetM` — positive to the left of travel, negative to the right. Sideways is
 * computed in the plane from each vertex's own travel direction, so the offset follows a bend
 * instead of shearing across it.
 *
 * Throws rather than returning something unusable: a stationary camera has no travel
 * direction, and a two-vertex polyline of zero length would fail deeper in the measurement
 * with a message about geometry rather than about the clip.
 */
export function roadEdgeFromCameraTrack(
  positions: readonly Vec3[],
  plane: Plane,
  offsetM: number,
): RoadEdgeFromTrack {
  if (positions.length < 2) {
    throw new RoadEdgeError(`the camera track has ${positions.length} pose(s); at least 2 are needed`);
  }
  if (!Number.isFinite(offsetM)) throw new RoadEdgeError("the lateral offset must be a finite number");
  if (!normalize(plane.normal)) throw new RoadEdgeError("the ground plane normal is degenerate");

  const flat: Vec3[] = [];
  for (const position of positions) {
    if (!position.every((component) => Number.isFinite(component))) continue;
    const candidate = ontoPlane(position, plane);
    const last = flat[flat.length - 1];
    if (!last) {
      flat.push(candidate);
      continue;
    }
    const step = Math.hypot(candidate[0] - last[0], candidate[1] - last[1], candidate[2] - last[2]);
    if (step >= VERTEX_SPACING_M) flat.push(candidate);
  }
  // A short or slow clip can thin down to a single vertex. Keep the far end so the polyline
  // still spans the travel that did happen, rather than refusing a usable clip.
  if (flat.length === 1) {
    const far = ontoPlane(positions[positions.length - 1], plane);
    const step = Math.hypot(far[0] - flat[0][0], far[1] - flat[0][1], far[2] - flat[0][2]);
    if (step > 1e-6) flat.push(far);
  }
  if (flat.length < 2) {
    throw new RoadEdgeError(
      "the camera did not move far enough on the ground to give a direction of travel. " +
        "A grass band needs a clip that travels along the verge.",
    );
  }

  const normal = normalize(plane.normal) as Vec3;
  const polyline: Vec3[] = flat.map((vertex, index) => {
    const before = flat[Math.max(0, index - 1)];
    const after = flat[Math.min(flat.length - 1, index + 1)];
    const travel = normalize([after[0] - before[0], after[1] - before[1], after[2] - before[2]]);
    // Degenerate only if two kept vertices coincide, which the spacing rule prevents.
    if (!travel) return vertex;
    const sideways = normalize(cross(normal, travel));
    if (!sideways) return vertex;
    return [
      vertex[0] + sideways[0] * offsetM,
      vertex[1] + sideways[1] * offsetM,
      vertex[2] + sideways[2] * offsetM,
    ];
  });

  let lengthM = 0;
  for (let i = 1; i < polyline.length; i++) {
    lengthM += Math.hypot(
      polyline[i][0] - polyline[i - 1][0],
      polyline[i][1] - polyline[i - 1][1],
      polyline[i][2] - polyline[i - 1][2],
    );
  }
  if (!(lengthM > 0)) {
    throw new RoadEdgeError("the offset polyline collapsed to a point; try a smaller lateral offset");
  }

  return { polyline, lengthM, keptPoses: polyline.length, sourcePoses: positions.length, offsetM };
}

/**
 * The band's outline on the ground, as line segments to draw.
 *
 * Two rails and a rung every `rungSpacingM`, so the band reads as a measured strip rather
 * than an outline. Returned flat, two points per segment, ready for a THREE.LineSegments.
 */
export function buildBandSegments(
  polyline: readonly Vec3[],
  plane: Plane,
  widthM: number,
  rungSpacingM = 1,
): Float32Array {
  const out: number[] = [];
  if (polyline.length < 2 || !(widthM > 0)) return Float32Array.from(out);
  const normal = normalize(plane.normal);
  if (!normal) return Float32Array.from(out);

  const outward: Vec3[] = polyline.map((_vertex, index) => {
    const before = polyline[Math.max(0, index - 1)];
    const after = polyline[Math.min(polyline.length - 1, index + 1)];
    const travel = normalize([after[0] - before[0], after[1] - before[1], after[2] - before[2]]);
    const sideways = travel ? normalize(cross(travel, normal)) : null;
    return sideways ?? [0, 0, 0];
  });
  const far = (index: number): Vec3 => [
    polyline[index][0] + outward[index][0] * widthM,
    polyline[index][1] + outward[index][1] * widthM,
    polyline[index][2] + outward[index][2] * widthM,
  ];

  let travelled = 0;
  let nextRung = 0;
  for (let i = 0; i < polyline.length; i++) {
    if (i > 0) {
      // Near rail, then far rail.
      out.push(...polyline[i - 1], ...polyline[i]);
      out.push(...far(i - 1), ...far(i));
      travelled += Math.hypot(
        polyline[i][0] - polyline[i - 1][0],
        polyline[i][1] - polyline[i - 1][1],
        polyline[i][2] - polyline[i - 1][2],
      );
    }
    if (travelled >= nextRung) {
      out.push(...polyline[i], ...far(i));
      nextRung += rungSpacingM;
    }
  }
  return Float32Array.from(out);
}

/**
 * One cell as a quad on the ground plane, in road-local coordinates.
 *
 * Drawn where the measurement says it is rather than where its points happen to be: a cell
 * whose points are all bunched in one corner is exactly the case worth seeing, and a hull
 * around the points would hide it.
 */
export function cellQuad(
  polyline: readonly Vec3[],
  plane: Plane,
  alongRoadM: number,
  distanceFromRoadM: number,
  cellSizeM: number,
): Vec3[] {
  const half = cellSizeM / 2;
  return [
    [alongRoadM - half, distanceFromRoadM - half],
    [alongRoadM + half, distanceFromRoadM - half],
    [alongRoadM + half, distanceFromRoadM + half],
    [alongRoadM - half, distanceFromRoadM + half],
  ].map(([along, distance]) => roadLocalToWorld(polyline, plane, along, distance));
}

/**
 * Road-local metres back into world space.
 *
 * The inverse of what the measurement did. It walks the polyline to `alongRoadM`, then steps
 * `distanceFromRoadM` outward in the plane. Clamped at both ends, matching the measurement's
 * own clamped projection, so a coordinate past the polyline lands on its end rather than
 * flying off along the last segment's direction.
 */
export function roadLocalToWorld(
  polyline: readonly Vec3[],
  plane: Plane,
  alongRoadM: number,
  distanceFromRoadM: number,
): Vec3 {
  const normal = normalize(plane.normal) ?? [0, 1, 0];
  if (polyline.length === 0) return [0, 0, 0];
  if (polyline.length === 1) return polyline[0];

  let remaining = Math.max(0, alongRoadM);
  for (let i = 1; i < polyline.length; i++) {
    const a = polyline[i - 1];
    const b = polyline[i];
    const segment: Vec3 = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const length = Math.hypot(segment[0], segment[1], segment[2]);
    const last = i === polyline.length - 1;
    if (remaining <= length || last) {
      const t = length > 0 ? Math.min(1, remaining / length) : 0;
      const at: Vec3 = [a[0] + segment[0] * t, a[1] + segment[1] * t, a[2] + segment[2] * t];
      const travel = normalize(segment) ?? [1, 0, 0];
      const outward = normalize(cross(travel, normal)) ?? [0, 0, 1];
      return [
        at[0] + outward[0] * distanceFromRoadM,
        at[1] + outward[1] * distanceFromRoadM,
        at[2] + outward[2] * distanceFromRoadM,
      ];
    }
    remaining -= length;
  }
  return polyline[polyline.length - 1];
}

/**
 * Which side of the polyline the grass actually fell on.
 *
 * The measurement reports an UNSIGNED distance from the road, because V1 measures one side.
 * That means a band placed down the middle of the grass folds both sides onto each other and
 * reports a plausible grid built from two places at once — a failure with no signature in the
 * numbers. This counts the split so a readout can say when it happened.
 */
export function sideBalance(
  points: ArrayLike<number>,
  polyline: readonly Vec3[],
  plane: Plane,
): { left: number; right: number } {
  const normal = normalize(plane.normal);
  const balance = { left: 0, right: 0 };
  if (!normal || polyline.length < 2) return balance;
  const { e1, e2 } = basisFromUp(normal);
  const flat2 = (p: Vec3): [number, number] => [dot(p, e1), dot(p, e2)];
  const vertices = polyline.map((vertex) => flat2(ontoPlane(vertex, plane)));

  for (let i = 0; i + 2 < points.length; i += 3) {
    const [u, v] = flat2(ontoPlane([points[i], points[i + 1], points[i + 2]], plane));
    let best = Infinity;
    let sign = 0;
    for (let s = 1; s < vertices.length; s++) {
      const [ax, ay] = vertices[s - 1];
      const [bx, by] = vertices[s];
      const dx = bx - ax;
      const dy = by - ay;
      const lengthSquared = dx * dx + dy * dy;
      const t = lengthSquared > 0 ? Math.min(1, Math.max(0, ((u - ax) * dx + (v - ay) * dy) / lengthSquared)) : 0;
      const cx = ax + dx * t;
      const cy = ay + dy * t;
      const distance = Math.hypot(u - cx, v - cy);
      if (distance < best) {
        best = distance;
        sign = Math.sign(dx * (v - ay) - dy * (u - ax));
      }
    }
    if (sign > 0) balance.left += 1;
    else if (sign < 0) balance.right += 1;
  }
  return balance;
}
