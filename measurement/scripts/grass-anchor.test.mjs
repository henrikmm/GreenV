import { describe, it, expect } from "vitest";
import {
  lateralProfile, chooseBand, trackLengthScale, cameraHeightScale, scaleAffine, polylineLength, ontoPlane,
} from "./grass-anchor.mjs";

const GROUND = { normal: [0, 1, 0], offset: 0 };
// Travel along +x on a y-up plane: cross(up, travel) = cross([0,1,0],[1,0,0]) = [0,0,-1], so the
// positive side is -z and a point at +z is on the negative side.
const TRACK = Array.from({ length: 9 }, (_, i) => [i, 0, 0]);
const flat = (points) => points.flat();

describe("lateral profile", () => {
  it("signs each point the way the road-edge offset does", () => {
    const profile = lateralProfile(flat([[2, 0, 5], [2, 0, 6], [2, 0, -1]]), TRACK, GROUND.normal);
    expect(Array.from(profile.laterals)).toEqual([-5, -6, 1]);
    expect(profile.negativeShare).toBeCloseTo(2 / 3);
  });
  it("projects onto the plane first when given one", () => {
    const profile = lateralProfile(flat([[2, 3, 5]]), TRACK, GROUND.normal, GROUND);
    expect(Array.from(profile.laterals)).toEqual([-5]);
  });
  it("is empty without a direction of travel or without points", () => {
    expect(lateralProfile(flat([[1, 0, 1]]), [[0, 0, 0]], GROUND.normal).negativeShare).toBeNaN();
    expect(lateralProfile([], TRACK, GROUND.normal).laterals.length).toBe(0);
  });
});

describe("band from the mask", () => {
  const given = { offsetM: 2, widthM: 5 };
  it("puts the edge just inside the near edge of the vegetation and reaches its far edge", () => {
    // Vegetation 5 to 9 m out on the negative side, nothing on the other.
    const points = flat(Array.from({ length: 41 }, (_, i) => [4, 0, 5 + i * 0.1]));
    const band = chooseBand(lateralProfile(points, TRACK, GROUND.normal), given);
    expect(band.side).toBe("negative");
    expect(band.reason).toBe("mask-mass");
    expect(band.nearM).toBeCloseTo(5.4, 1);
    expect(band.farM).toBeCloseTo(8.6, 1);
    expect(band.offsetM).toBeCloseTo(-4.9, 1);
    // farM - |offset| + margin = 8.6 - 4.9 + 0.5 = 4.2, lifted to the 5 m floor.
    expect(band.widthM).toBe(5);
  });
  it("widens up to the cap for a deep verge and keeps the caller's magnitude sign-flipped elsewhere", () => {
    const points = flat(Array.from({ length: 200 }, (_, i) => [4, 0, -(3 + i * 0.1)]));
    const band = chooseBand(lateralProfile(points, TRACK, GROUND.normal), given);
    expect(band.side).toBe("positive");
    expect(band.offsetM).toBeGreaterThan(0);
    expect(band.widthM).toBe(10);
  });
  it("keeps the caller's band when the mask straddles the track or is absent", () => {
    const straddle = flat([[1, 0, 2], [2, 0, -2], [3, 0, 2], [4, 0, -2]]);
    expect(chooseBand(lateralProfile(straddle, TRACK, GROUND.normal), given)).toMatchObject({ offsetM: 2, widthM: 5, side: "given", reason: "mask-straddles-track" });
    expect(chooseBand(lateralProfile([], TRACK, GROUND.normal), given)).toMatchObject({ offsetM: 2, widthM: 5, side: "given", reason: "no-mask-points" });
  });
});

describe("track-length scale", () => {
  it("returns the factor that makes the reconstructed track as long as the vehicle drove", () => {
    expect(trackLengthScale(29.8, 23.3)).toEqual({ factor: 29.8 / 23.3, applied: true, reason: "track-length" });
  });
  it("refuses a short anchor, a collapsed track and a factor no wrong scale could produce", () => {
    expect(trackLengthScale(3, 2.5)).toMatchObject({ applied: false, reason: "expected-track-too-short" });
    expect(trackLengthScale(30, 0.5)).toMatchObject({ applied: false, reason: "reconstructed-track-too-short" });
    expect(trackLengthScale(232, 42.4)).toMatchObject({ applied: false, reason: "factor-out-of-range", proposedFactor: 232 / 42.4 });
    expect(trackLengthScale(null, 20)).toMatchObject({ applied: false, reason: "no-anchor" });
  });
});

describe("camera-height scale", () => {
  it("returns the factor that puts the camera at its known height", () => {
    expect(cameraHeightScale(1.25, 1.86)).toEqual({ factor: 1.25 / 1.86, applied: true, reason: "camera-height" });
  });
  it("refuses a camera in the plane, an absent anchor, and a factor out of range", () => {
    expect(cameraHeightScale(1.25, 0.04)).toMatchObject({ applied: false, reason: "camera-not-above-plane" });
    expect(cameraHeightScale(null, 1.5)).toMatchObject({ applied: false, reason: "no-anchor" });
    expect(cameraHeightScale(1.25, 12)).toMatchObject({ applied: false, reason: "factor-out-of-range" });
  });
});

describe("geometry helpers", () => {
  it("scales the linear part and the translation, never the homogeneous row", () => {
    const m = [1, 0, 0, 5, 0, 1, 0, 6, 0, 0, 1, 7, 0, 0, 0, 1];
    expect(scaleAffine(m, 2)).toEqual([2, 0, 0, 10, 0, 2, 0, 12, 0, 0, 2, 14, 0, 0, 0, 1]);
  });
  it("tells a curled track from a short one", () => {
    expect(polylineLength([[0, 0, 0], [3, 0, 0], [3, 0, 4]])).toEqual({ lengthM: 7, endToEndM: 5 });
    expect(polylineLength([[1, 1, 1]])).toEqual({ lengthM: 0, endToEndM: 0 });
  });
  it("projects onto the plane", () => {
    expect(ontoPlane([1, 7, 2], GROUND)).toEqual([1, 0, 2]);
  });
});
