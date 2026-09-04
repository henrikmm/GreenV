/**
 * The two pure decisions in the grass overlay: which shade a height gets, and which cell a
 * coordinate belongs to. Both are wrong-able in ways that look fine on screen — a shade that
 * saturates hides the variation the overlay exists to show, and a key that disagrees with the
 * store's selects the wrong cell's pixels while highlighting the right cell's outline.
 */

import { describe, expect, it } from "vitest";
import { cellKey, heightShade } from "./grass-overlay";
import { cellKeyOf } from "../measurement/grass-grid-store";

describe("height shade", () => {
  it("spans the run's own range end to end", () => {
    expect(heightShade(0.2, 0.2, 0.8)).toBe(0);
    expect(heightShade(0.8, 0.2, 0.8)).toBe(1);
    expect(heightShade(0.5, 0.2, 0.8)).toBeCloseTo(0.5, 6);
  });

  it("clamps rather than running off either end", () => {
    expect(heightShade(-3, 0.2, 0.8)).toBe(0);
    expect(heightShade(99, 0.2, 0.8)).toBe(1);
  });

  it("picks a mid shade when every cell reads the same, instead of dividing by zero", () => {
    expect(heightShade(0.4, 0.4, 0.4)).toBe(0.75);
    expect(Number.isFinite(heightShade(0.4, 0.4, 0.4))).toBe(true);
  });

  it("is never NaN, whatever it is handed", () => {
    expect(heightShade(Number.NaN, 0, 1)).toBe(0);
  });
});

describe("cell keys", () => {
  it("agrees with the store, which is what makes a selection select one cell", () => {
    for (const [along, distance] of [
      [0.25, 0.25],
      [1.25, 4.75],
      [12.75, 0.75],
    ]) {
      const coordinate = { alongRoadM: along, distanceFromRoadM: distance };
      expect(cellKey(along, distance, 0.5)).toBe(cellKeyOf(coordinate, 0.5));
    }
  });

  it("recovers the index a cell centre was built from", () => {
    // Centres are (index + 0.5) * size, so these are indices 0, 2 and 25.
    expect(cellKey(0.25, 0.25, 0.5)).toBe("0,0");
    expect(cellKey(1.25, 0.25, 0.5)).toBe("2,0");
    expect(cellKey(12.75, 4.75, 0.5)).toBe("25,9");
  });

  it("follows a different cell size", () => {
    expect(cellKey(0.5, 1.5, 1)).toBe("0,1");
  });
});
