/**
 * A grass mask from a semantic segmentation model's logits.
 *
 * This takes numbers and returns numbers. It never loads a model, never touches the
 * network, and does not know what produced its input — which is what lets it be tested
 * without a download and run from both the app and the inspector.
 *
 * ## Why one class, and why that one
 *
 * Cityscapes labels 19 classes and this uses exactly one: `terrain` (id 9), which is the
 * grass and soil at the side of a road. `vegetation` (id 8) is the trees and bushes above
 * it, and is deliberately NOT included. Measured on run `20260814-174814-b245bc` frame 80,
 * 2026-09-04:
 *
 *   - `terrain` covered the lawn and overlapped the recorded Grass-Exe1 brush — 20,892
 *     pixels painted up one clump from base to tip — in ZERO pixels. The grass class does
 *     not swallow the bush, which was the property in doubt.
 *   - `vegetation` recovered only 41.6% of that same brush at full frame resolution; the
 *     rest read as `fence`, because thin blades against a pale wall look like one. Scored
 *     on the 128x128 logit grid with this floor applied — the mask the grid actually
 *     receives — it is 0.374 `vegetation` and 0.553 `fence`. Reproduce either with
 *     `node scripts/inspect.mjs segment 20260814-174814 --frame 79 --against Grass-Exe1`.
 *   - `road` (id 0) is unused. SegFormer-B2 labelled 9.74% of that garden frame `road`,
 *     and what it was looking at was a wall. A road edge from that class would be
 *     confidently wrong in the way this project cannot detect.
 *
 * Every class other than `terrain` maps to `excluded`, and that is a refusal rather than
 * a simplification: a class nobody has measured on our own frames must not be able to
 * contribute pixels to a height. Adding one is a measurement, not a config change.
 *
 * ## The floor, and why a pixel can be dropped
 *
 * Winning a 19-way argmax says a class beat the others, not that the model is sure. So a
 * pixel is kept only if `terrain` wins AND its probability clears `minProbability`. The
 * counts come back with the mask, because a mask that dropped 40% of its candidates to
 * the floor is a different object from one that dropped 2%, and nothing downstream can
 * tell them apart from the mask alone.
 */

/** Cityscapes' 19 training classes, in the order the model's channels are in. */
export const CITYSCAPES_LABELS = [
  "road",
  "sidewalk",
  "building",
  "wall",
  "fence",
  "pole",
  "traffic light",
  "traffic sign",
  "vegetation",
  "terrain",
  "sky",
  "person",
  "rider",
  "car",
  "truck",
  "bus",
  "train",
  "motorcycle",
  "bicycle",
] as const;

export const CITYSCAPES_CLASS_COUNT = CITYSCAPES_LABELS.length;

/** The one class this project measures grass from. See the header for the evidence. */
export const GRASS_CLASS_ID = 9;

export type SemanticRole = "grass" | "excluded";

/**
 * Class id to role, as data rather than a condition, so a test can read the whole policy
 * and a reviewer can see at a glance which classes are trusted. Exactly one is.
 */
export const CITYSCAPES_ROLES: readonly SemanticRole[] = CITYSCAPES_LABELS.map((_, id) =>
  id === GRASS_CLASS_ID ? "grass" : "excluded",
);

/**
 * The probability a winning `terrain` pixel must clear to be kept.
 *
 * 0.5 is the point where the winning class holds an outright majority of the probability
 * mass rather than merely leading a nineteen-way split, which is a boundary with a meaning
 * rather than a round number someone liked.
 *
 * It also happens to sit at the end of the lower tail on our own frames. Measured 2026-09-04
 * over 10 frames of runs `20260814-174814-b245bc` and `20260814-164826-0e4e4c` — 38,898
 * pixels where `terrain` won — the winning probability ran p5 0.509, p10 0.567, p25 0.750,
 * p50 0.953, p75 0.995. So the distribution is strongly bimodal: most kept pixels are
 * near-certain, and the uncertain ones are a thin tail this floor removes.
 *
 * What each candidate would discard, from that same pool:
 *
 *   0.50 → 4.24%      0.60 → 12.61%      0.70 → 20.66%      0.80 → 29.57%
 *
 * A stricter floor is not obviously better. The frames it cuts hardest are the ones where
 * grass is a small, distant or shaded part of the picture, and the grid already refuses those
 * on its own terms — a frame contributing fewer than `minVoxelsPerFrame` voxels does not vote.
 * Discarding the evidence twice would abstain on cells that had enough of it.
 */
export const DEFAULT_MIN_PROBABILITY = 0.5;

export interface SemanticMaskOptions {
  /** Winning probability a pixel must reach. Below it the pixel is excluded, not guessed. */
  minProbability?: number;
  /** Class id to role. Defaults to Cityscapes with `terrain` alone as grass. */
  roles?: readonly SemanticRole[];
}

export interface SemanticMaskCounts {
  /** Pixels in the logit grid. */
  total: number;
  /** Pixels where a `grass` class won the argmax, before the floor was applied. */
  grassWins: number;
  /** Of those, the ones that cleared the floor. This is the mask's pixel count. */
  kept: number;
  /** Of those, the ones the floor rejected. `grassWins === kept + droppedToFloor`. */
  droppedToFloor: number;
}

export interface SemanticMaskResult {
  /** 1 where grass, 0 elsewhere. Row-major over the LOGIT grid, not the source frame. */
  mask: Uint8Array;
  /** The logit grid's size. Feed these to the grid as `maskWidth`/`maskHeight`. */
  width: number;
  height: number;
  counts: SemanticMaskCounts;
  /** The floor actually applied, so a caller reporting provenance need not guess it. */
  minProbability: number;
}

export interface SemanticLogits {
  /** Channel-major: the value for class `c` at pixel `p` is `data[c * height * width + p]`. */
  data: ArrayLike<number>;
  classes: number;
  height: number;
  width: number;
}

/**
 * Softmax over a pixel's classes, returning the winner and its probability.
 *
 * The maximum is subtracted before exponentiating, which is the standard guard against
 * overflow and also makes the result independent of the logits' absolute scale. Ties go to
 * the lowest class id, because the scan order is fixed and a tie has to go somewhere.
 */
function argmaxWithProbability(
  data: ArrayLike<number>,
  pixel: number,
  classes: number,
  stride: number,
): { classId: number; probability: number } {
  let max = -Infinity;
  let classId = 0;
  for (let c = 0; c < classes; c++) {
    const value = data[c * stride + pixel];
    if (value > max) {
      max = value;
      classId = c;
    }
  }
  let sum = 0;
  for (let c = 0; c < classes; c++) sum += Math.exp(data[c * stride + pixel] - max);
  // exp(max - max) is 1, so the winner's share is simply 1/sum.
  return { classId, probability: 1 / sum };
}

export interface SemanticClassMap {
  /** The winning class id per pixel, row-major over the logit grid. */
  classIds: Uint8Array;
  /**
   * That winner's softmax probability, in the same order.
   *
   * Float64 rather than Float32, so that a pixel whose probability is exactly the floor is
   * kept rather than being rounded under it. The floor is a documented boundary and it should
   * behave like one; the extra 64 KB on a 128x128 grid is not worth a surprise at the edge.
   */
  probabilities: Float64Array;
  width: number;
  height: number;
  classes: number;
}

/**
 * The winning class and its probability for every pixel.
 *
 * Everything else here is built on this, so a mask and the diagnostic that explains it can
 * never be computed two different ways. Order independent by construction: every pixel is
 * decided from its own column of the logits and nothing accumulates across pixels, so the
 * same input gives byte-identical output however the memory is walked.
 */
export function classMapFromLogits(logits: SemanticLogits): SemanticClassMap {
  const { data, classes, height, width } = logits;
  const stride = height * width;
  if (classes < 1 || stride < 1) throw new Error("logits must have at least one class and one pixel");
  if (classes > 256) throw new Error(`${classes} classes will not fit a Uint8Array of ids`);
  if (data.length < classes * stride) {
    throw new Error(`logits data is ${data.length}, need ${classes * stride} for ${classes}x${height}x${width}`);
  }

  const classIds = new Uint8Array(stride);
  const probabilities = new Float64Array(stride);
  for (let pixel = 0; pixel < stride; pixel++) {
    const { classId, probability } = argmaxWithProbability(data, pixel, classes, stride);
    classIds[pixel] = classId;
    probabilities[pixel] = probability;
  }
  return { classIds, probabilities, width, height, classes };
}

/** Turn one frame's class map into a grass mask, applying the probability floor. */
export function grassMaskFromClassMap(
  map: SemanticClassMap,
  options: SemanticMaskOptions = {},
): SemanticMaskResult {
  const roles = options.roles ?? CITYSCAPES_ROLES;
  const minProbability = options.minProbability ?? DEFAULT_MIN_PROBABILITY;
  if (roles.length !== map.classes) {
    throw new Error(`roles describe ${roles.length} classes but the map carries ${map.classes}`);
  }

  const stride = map.width * map.height;
  const mask = new Uint8Array(stride);
  let grassWins = 0;
  let kept = 0;

  for (let pixel = 0; pixel < stride; pixel++) {
    if (roles[map.classIds[pixel]] !== "grass") continue;
    grassWins += 1;
    if (map.probabilities[pixel] < minProbability) continue;
    mask[pixel] = 1;
    kept += 1;
  }

  return {
    mask,
    width: map.width,
    height: map.height,
    counts: { total: stride, grassWins, kept, droppedToFloor: grassWins - kept },
    minProbability,
  };
}

/** The same, straight from logits, for a caller that needs nothing else. */
export function grassMaskFromLogits(
  logits: SemanticLogits,
  options: SemanticMaskOptions = {},
): SemanticMaskResult {
  return grassMaskFromClassMap(classMapFromLogits(logits), options);
}

/**
 * Every class's share of the frame, for reporting what the model saw.
 *
 * Not used by the mask — this is the diagnostic that says "39.5% terrain, 17.3% fence" and
 * makes a surprising mask explainable rather than merely wrong.
 */
export function classFractions(map: SemanticClassMap): Map<number, number> {
  const stride = map.width * map.height;
  const counts = new Map<number, number>();
  for (let pixel = 0; pixel < stride; pixel++) {
    counts.set(map.classIds[pixel], (counts.get(map.classIds[pixel]) ?? 0) + 1);
  }
  const fractions = new Map<number, number>();
  for (const [classId, count] of counts) fractions.set(classId, count / stride);
  return fractions;
}
