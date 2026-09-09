/**
 * The road-edge stand-in, checked against paths whose answer is arithmetic.
 *
 * The camera track is the one input to the grass grid that nothing in this project measures,
 * so it is the input most able to be quietly wrong. A polyline offset to the wrong side, or
 * sheared through a bend, produces a band that looks reasonable on screen and measures the
 * wrong strip of ground. These tests pin the side, the spacing and the round trip.
 */

import { describe, expect, it } from "vitest";
import {
  RoadEdgeError,
  buildBandSegments,
  cellQuad,
  roadEdgeFromCameraTrack,
  roadLocalToWorld,
  sideBalance,
} from "./grass-grid";
import type { Plane, Vec3 } from "../../../geometry";

const GROUND: Plane = { normal: [0, 1, 0], offset: 0 };

/** A camera walking along +X at head height, sampled every `stepM`. */
function straightWalk(count: number, stepM = 0.25, height = 1.5): Vec3[] {
  return Array.from({ length: count }, (_, i) => [i * stepM, height, 0] as Vec3);
}

describe("road edge from the camera track", () => {
  it("flattens the path onto the ground and keeps its direction", () => {
    const edge = roadEdgeFromCameraTrack(straightWalk(21), GROUND, 0);
    expect(edge.sourcePoses).toBe(21);
    // Every vertex lands on the plane, whatever height the camera was carried at.
    for (const vertex of edge.polyline) expect(vertex[1]).toBeCloseTo(0, 10);
    expect(edge.polyline[0][0]).toBeCloseTo(0, 10);
    expect(edge.lengthM).toBeCloseTo(5, 6);
  });

  it("thins the path to half-metre vertices rather than one per frame", () => {
    // 41 poses 25 cm apart span 10 m; at 0.5 m spacing that is 21 vertices, not 41.
    const edge = roadEdgeFromCameraTrack(straightWalk(41), GROUND, 0);
    expect(edge.keptPoses).toBe(21);
    expect(edge.sourcePoses).toBe(41);
  });

  it("offsets to one side of travel, and the other for a negative offset", () => {
    const left = roadEdgeFromCameraTrack(straightWalk(21), GROUND, 2);
    const right = roadEdgeFromCameraTrack(straightWalk(21), GROUND, -2);
    expect(left.polyline[5][2]).toBeCloseTo(-2, 6);
    expect(right.polyline[5][2]).toBeCloseTo(2, 6);
    // The offset is sideways only: it moves nothing along the direction of travel.
    expect(left.polyline[5][0]).toBeCloseTo(right.polyline[5][0], 6);
  });

  it("follows a bend instead of shearing across it", () => {
    // An L: ten metres along +X, then ten along +Z.
    const corner: Vec3[] = [
      ...Array.from({ length: 21 }, (_, i) => [i * 0.5, 1.5, 0] as Vec3),
      ...Array.from({ length: 20 }, (_, i) => [10, 1.5, (i + 1) * 0.5] as Vec3),
    ];
    const edge = roadEdgeFromCameraTrack(corner, GROUND, 1);
    const first = edge.polyline[2];
    const last = edge.polyline[edge.polyline.length - 3];
    // On the first leg the offset is along −Z; after the corner the same offset is along +X,
    // because "sideways" is taken from each vertex's own travel direction.
    expect(first[2]).toBeCloseTo(-1, 4);
    expect(last[0]).toBeCloseTo(11, 4);
  });

  it("refuses a camera that never moved, and says why in terms of the clip", () => {
    const parked: Vec3[] = Array.from({ length: 30 }, () => [3, 1.5, 2]);
    expect(() => roadEdgeFromCameraTrack(parked, GROUND, 0)).toThrow(RoadEdgeError);
    expect(() => roadEdgeFromCameraTrack(parked, GROUND, 0)).toThrow(/travels along the verge/);
    expect(() => roadEdgeFromCameraTrack([[0, 0, 0]], GROUND, 0)).toThrow(/at least 2/);
    expect(() => roadEdgeFromCameraTrack(straightWalk(21), GROUND, Number.NaN)).toThrow(/finite/);
  });

  it("keeps a short clip usable by spanning the travel it did make", () => {
    // Three poses 20 cm apart never clear the half-metre spacing rule, but the clip is real.
    const shuffle: Vec3[] = [
      [0, 1.5, 0],
      [0.2, 1.5, 0],
      [0.4, 1.5, 0],
    ];
    const edge = roadEdgeFromCameraTrack(shuffle, GROUND, 0);
    expect(edge.polyline).toHaveLength(2);
    expect(edge.lengthM).toBeCloseTo(0.4, 6);
  });
});

describe("road-local coordinates, drawn", () => {
  it("puts a road-local coordinate back where the measurement found it", () => {
    const edge = roadEdgeFromCameraTrack(straightWalk(21), GROUND, 0);
    const world = roadLocalToWorld(edge.polyline, GROUND, 3, 2);
    expect(world[0]).toBeCloseTo(3, 6);
    expect(world[1]).toBeCloseTo(0, 6);
    expect(Math.abs(world[2])).toBeCloseTo(2, 6);
  });

  it("clamps past the end of the polyline instead of flying off it", () => {
    const edge = roadEdgeFromCameraTrack(straightWalk(21), GROUND, 0);
    const past = roadLocalToWorld(edge.polyline, GROUND, 500, 1);
    // The polyline is 5 m long, so 500 m along it is its far end, not 500 m away.
    expect(past[0]).toBeCloseTo(5, 6);
  });

  it("draws a cell as a square of the asked-for size", () => {
    const edge = roadEdgeFromCameraTrack(straightWalk(21), GROUND, 0);
    const quad = cellQuad(edge.polyline, GROUND, 2.25, 1.75, 0.5);
    expect(quad).toHaveLength(4);
    const side = Math.hypot(quad[1][0] - quad[0][0], quad[1][1] - quad[0][1], quad[1][2] - quad[0][2]);
    expect(side).toBeCloseTo(0.5, 6);
    for (const corner of quad) expect(corner[1]).toBeCloseTo(0, 6);
  });

  it("draws both rails of the band and a rung along it", () => {
    const edge = roadEdgeFromCameraTrack(straightWalk(21), GROUND, 0);
    const segments = buildBandSegments(edge.polyline, GROUND, 5, 1);
    expect(segments.length % 6).toBe(0);
    // Every drawn point lies on the ground plane and within the band's own width.
    for (let i = 0; i + 2 < segments.length; i += 3) {
      expect(segments[i + 1]).toBeCloseTo(0, 5);
      expect(Math.abs(segments[i + 2])).toBeLessThanOrEqual(5.001);
    }
    expect(buildBandSegments([[0, 0, 0]], GROUND, 5)).toHaveLength(0);
  });
});

describe("which side the grass fell on", () => {
  it("counts the split, because an unsigned distance cannot", () => {
    const edge = roadEdgeFromCameraTrack(straightWalk(21), GROUND, 0);
    // Nine points at z = +1 and three at z = −1: the same distance from the road, and a
    // failure the measurement's unsigned distance folds together silently.
    const points: number[] = [];
    for (let i = 0; i < 9; i++) points.push(1 + i * 0.1, 0.2, 1);
    for (let i = 0; i < 3; i++) points.push(1 + i * 0.1, 0.2, -1);
    const balance = sideBalance(points, edge.polyline, GROUND);
    expect(balance.left + balance.right).toBe(12);
    expect(Math.max(balance.left, balance.right)).toBe(9);
    expect(Math.min(balance.left, balance.right)).toBe(3);
  });

  it("is empty when there is no polyline to take a side of", () => {
    expect(sideBalance([0, 0, 0], [[0, 0, 0]], GROUND)).toEqual({ left: 0, right: 0 });
  });
});
