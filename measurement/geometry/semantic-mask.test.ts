import { describe, expect, it } from "vitest";
import {
  CITYSCAPES_CLASS_COUNT,
  CITYSCAPES_LABELS,
  CITYSCAPES_ROLES,
  DEFAULT_MIN_PROBABILITY,
  GRASS_CLASS_ID,
  classFractions,
  classMapFromLogits,
  grassMaskFromClassMap,
  grassMaskFromLogits,
  type SemanticLogits,
  type SemanticRole,
} from "./semantic-mask";

/**
 * Build logits with a known answer.
 *
 * `wins[p]` is the class that should win pixel p, and `margin` is how far above the others its
 * logit sits. Softmax of one value `m` above 18 zeros is `e^m / (e^m + 18)`, so a margin picks
 * an exact winning probability and the floor can be tested at its boundary rather than near it.
 */
function logitsFrom(wins: number[], margin: number, width: number, height: number): SemanticLogits {
  const classes = CITYSCAPES_CLASS_COUNT;
  const stride = width * height;
  const data = new Float32Array(classes * stride);
  for (let pixel = 0; pixel < stride; pixel++) data[wins[pixel] * stride + pixel] = margin;
  return { data, classes, width, height };
}

/** The probability `logitsFrom` gives its winner, for a given margin. */
function winningProbability(margin: number): number {
  return Math.exp(margin) / (Math.exp(margin) + (CITYSCAPES_CLASS_COUNT - 1));
}

const VEGETATION = CITYSCAPES_LABELS.indexOf("vegetation");
const ROAD = CITYSCAPES_LABELS.indexOf("road");

describe("the class policy", () => {
  it("trusts exactly one class", () => {
    expect(CITYSCAPES_ROLES.filter((role) => role === "grass")).toHaveLength(1);
    expect(CITYSCAPES_ROLES[GRASS_CLASS_ID]).toBe("grass");
    expect(CITYSCAPES_LABELS[GRASS_CLASS_ID]).toBe("terrain");
  });

  it("excludes vegetation and road deliberately, not by omission", () => {
    // Both were measured and both failed: `vegetation` recovered 41.6% of a recorded clump
    // brush on run 20260814-174814-b245bc frame 80, and B2 called 9.74% of that garden frame
    // `road` while looking at a wall. If either is ever promoted it needs a measurement, and
    // this test is where a promotion has to be argued.
    expect(CITYSCAPES_ROLES[VEGETATION]).toBe("excluded");
    expect(CITYSCAPES_ROLES[ROAD]).toBe("excluded");
  });

  it("describes every class the model emits", () => {
    expect(CITYSCAPES_ROLES).toHaveLength(CITYSCAPES_CLASS_COUNT);
    expect(CITYSCAPES_LABELS).toHaveLength(CITYSCAPES_CLASS_COUNT);
  });
});

describe("the class map", () => {
  it("returns the winner and its softmax probability", () => {
    const map = classMapFromLogits(logitsFrom([GRASS_CLASS_ID, VEGETATION, ROAD, GRASS_CLASS_ID], 3, 2, 2));
    expect([...map.classIds]).toEqual([GRASS_CLASS_ID, VEGETATION, ROAD, GRASS_CLASS_ID]);
    for (const probability of map.probabilities) {
      expect(probability).toBeCloseTo(winningProbability(3), 12);
    }
  });

  it("gives a flat pixel to the lowest class id, because the scan order is fixed", () => {
    const data = new Float32Array(CITYSCAPES_CLASS_COUNT); // one pixel, every logit zero
    const map = classMapFromLogits({ data, classes: CITYSCAPES_CLASS_COUNT, width: 1, height: 1 });
    expect(map.classIds[0]).toBe(0);
    expect(map.probabilities[0]).toBeCloseTo(1 / CITYSCAPES_CLASS_COUNT, 12);
  });

  it("is unaffected by a constant added to every class, as softmax must be", () => {
    const wins = [GRASS_CLASS_ID, VEGETATION, GRASS_CLASS_ID, ROAD];
    const plain = classMapFromLogits(logitsFrom(wins, 2, 2, 2));
    const shifted = logitsFrom(wins, 2, 2, 2);
    const raised = new Float32Array(shifted.data as Float32Array);
    for (let i = 0; i < raised.length; i++) raised[i] += 1000;
    const after = classMapFromLogits({ ...shifted, data: raised });
    expect([...after.classIds]).toEqual([...plain.classIds]);
    for (let i = 0; i < plain.probabilities.length; i++) {
      expect(after.probabilities[i]).toBeCloseTo(plain.probabilities[i], 6);
    }
  });

  it("refuses logits that are too short for the shape they claim", () => {
    const data = new Float32Array(10);
    expect(() => classMapFromLogits({ data, classes: CITYSCAPES_CLASS_COUNT, width: 4, height: 4 })).toThrow(
      /need 304/,
    );
  });
});

describe("the grass mask", () => {
  it("keeps terrain and nothing else", () => {
    const wins = [GRASS_CLASS_ID, VEGETATION, ROAD, GRASS_CLASS_ID, GRASS_CLASS_ID, 2];
    const result = grassMaskFromLogits(logitsFrom(wins, 5, 3, 2));
    expect([...result.mask]).toEqual([1, 0, 0, 1, 1, 0]);
    expect(result.counts).toEqual({ total: 6, grassWins: 3, kept: 3, droppedToFloor: 0 });
  });

  it("drops a terrain pixel that wins without clearing the floor", () => {
    // A margin of 2 gives 0.291 and a margin of 4 gives 0.752, so a floor of 0.5 sits between
    // them and the two pixels must be decided differently.
    expect(winningProbability(2)).toBeLessThan(0.5);
    expect(winningProbability(4)).toBeGreaterThan(0.5);

    const stride = 2;
    const data = new Float32Array(CITYSCAPES_CLASS_COUNT * stride);
    data[GRASS_CLASS_ID * stride + 0] = 4;
    data[GRASS_CLASS_ID * stride + 1] = 2;
    const result = grassMaskFromLogits({ data, classes: CITYSCAPES_CLASS_COUNT, width: 2, height: 1 });

    expect([...result.mask]).toEqual([1, 0]);
    expect(result.counts).toEqual({ total: 2, grassWins: 2, kept: 1, droppedToFloor: 1 });
    expect(result.minProbability).toBe(DEFAULT_MIN_PROBABILITY);
  });

  it("applies the floor at its exact boundary, keeping a pixel that reaches it", () => {
    const stride = 1;
    const data = new Float32Array(CITYSCAPES_CLASS_COUNT * stride);
    data[GRASS_CLASS_ID] = 3;
    // Read the probability back rather than recomputing it: the point of this test is the
    // comparison at the boundary, not whether two float expressions agree in the last bit.
    const exact = classMapFromLogits({ data, classes: CITYSCAPES_CLASS_COUNT, width: 1, height: 1 })
      .probabilities[0];

    const at = grassMaskFromLogits({ data, classes: CITYSCAPES_CLASS_COUNT, width: 1, height: 1 }, {
      minProbability: exact,
    });
    expect(at.counts.kept).toBe(1);

    const justAbove = grassMaskFromLogits({ data, classes: CITYSCAPES_CLASS_COUNT, width: 1, height: 1 }, {
      minProbability: exact + 1e-9,
    });
    expect(justAbove.counts.kept).toBe(0);
  });

  it("counts what the floor removed, because a mask alone cannot say", () => {
    const stride = 4;
    const data = new Float32Array(CITYSCAPES_CLASS_COUNT * stride);
    for (let pixel = 0; pixel < stride; pixel++) data[GRASS_CLASS_ID * stride + pixel] = pixel < 3 ? 2 : 6;
    const result = grassMaskFromLogits({ data, classes: CITYSCAPES_CLASS_COUNT, width: 4, height: 1 });
    expect(result.counts.grassWins).toBe(4);
    expect(result.counts.kept).toBe(1);
    expect(result.counts.droppedToFloor).toBe(3);
  });

  it("lets a caller trust a different class, without that being the default", () => {
    const roles: SemanticRole[] = CITYSCAPES_LABELS.map((_, id) => (id === VEGETATION ? "grass" : "excluded"));
    const wins = [GRASS_CLASS_ID, VEGETATION];
    const result = grassMaskFromLogits(logitsFrom(wins, 5, 2, 1), { roles });
    expect([...result.mask]).toEqual([0, 1]);
  });

  it("refuses a role table that does not describe the model's classes", () => {
    expect(() => grassMaskFromLogits(logitsFrom([GRASS_CLASS_ID], 5, 1, 1), { roles: ["grass"] })).toThrow(
      /roles describe 1 classes but/,
    );
  });

  it("gives byte-identical output on a repeat", () => {
    const wins = Array.from({ length: 64 }, (_, i) => (i * 7) % CITYSCAPES_CLASS_COUNT);
    const logits = logitsFrom(wins, 4, 8, 8);
    const first = grassMaskFromLogits(logits);
    const second = grassMaskFromLogits(logits);
    expect(Buffer.from(second.mask)).toEqual(Buffer.from(first.mask));
    expect(second.counts).toEqual(first.counts);
  });

  it("carries the logit grid's own size, not the frame's", () => {
    // The grass grid resamples a mask onto the depth grid itself, so this reports what the
    // model actually produced rather than upsampling and inventing detail.
    const result = grassMaskFromLogits(logitsFrom(new Array(12).fill(GRASS_CLASS_ID), 5, 4, 3));
    expect(result.width).toBe(4);
    expect(result.height).toBe(3);
    expect(result.mask).toHaveLength(12);
  });
});

describe("class fractions", () => {
  it("sum to one and name what the model saw", () => {
    const wins = [GRASS_CLASS_ID, GRASS_CLASS_ID, VEGETATION, ROAD];
    const fractions = classFractions(classMapFromLogits(logitsFrom(wins, 5, 2, 2)));
    expect(fractions.get(GRASS_CLASS_ID)).toBe(0.5);
    expect(fractions.get(VEGETATION)).toBe(0.25);
    expect(fractions.get(ROAD)).toBe(0.25);
    expect([...fractions.values()].reduce((a, b) => a + b, 0)).toBeCloseTo(1, 12);
  });

  it("omits a class no pixel chose, rather than reporting a zero", () => {
    const fractions = classFractions(classMapFromLogits(logitsFrom([GRASS_CLASS_ID], 5, 1, 1)));
    expect(fractions.has(VEGETATION)).toBe(false);
  });
});

describe("the map and the mask agree", () => {
  it("gives the same answer whether the map is built first or not", () => {
    const wins = Array.from({ length: 36 }, (_, i) => (i * 5) % CITYSCAPES_CLASS_COUNT);
    const logits = logitsFrom(wins, 3, 6, 6);
    const direct = grassMaskFromLogits(logits, { minProbability: 0.4 });
    const staged = grassMaskFromClassMap(classMapFromLogits(logits), { minProbability: 0.4 });
    expect(Buffer.from(staged.mask)).toEqual(Buffer.from(direct.mask));
    expect(staged.counts).toEqual(direct.counts);
  });
});
