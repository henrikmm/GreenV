// The segmentation model, pinned and loaded from a local cache.
//
// Two jobs, and the split matters. The MODEL REGISTRY below is the pinned identity — the id,
// the revision and where its weights land on disk — and it is imported by the one script that
// downloads (`scripts/fetch-model.mjs`). The LOADER runs the model with remote loading turned
// off, so the inspector keeps its promise of containing no network code: if the weights are not
// cached this fails immediately with an instruction, rather than quietly fetching 15 MB.
//
// Nothing here decides what a mask means. The logits go to `geometry/semantic-mask.ts`, which
// is pure and tested; this file only produces them.

import { createRequire } from "node:module";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { REPO } from "./source.mjs";

const require = createRequire(new URL("../../app/package.json", import.meta.url));

/** Gitignored, and inside the repository so a `du` shows what the tooling costs. */
export const MODEL_CACHE = process.env.VERGE_MODEL_CACHE ?? join(REPO, ".models");

/**
 * Every model this project is allowed to run, pinned by revision.
 *
 * `main` is not reproducible evidence — the same rule `app/src/measurement/segmenter.ts`
 * applies to SlimSAM. The default is B0 and that is a measured choice, not a size compromise:
 * on run `20260814-174814-b245bc` frame 80 (2026-09-04) B2 labelled 9.74% of a garden frame
 * `road` and what it was looking at was a wall, where B0 gave the same class 0.05%. B2 is kept
 * here so that finding can be reproduced, not because anything should use it.
 */
export const SEGMENTATION_MODELS = [
  {
    key: "cityscapes-b0",
    id: "Xenova/segformer-b0-finetuned-cityscapes-1024-1024",
    revision: "64e537ca2bf6bf2afc13727ee6bb61cfca9e1048",
    runtime: "@huggingface/transformers@3.8.1",
    default: true,
  },
  {
    key: "cityscapes-b2",
    id: "Xenova/segformer-b2-finetuned-cityscapes-1024-1024",
    revision: "178592c2b7cd2025e0d5e8c32a5356f36fbde311",
    runtime: "@huggingface/transformers@3.8.1",
    default: false,
  },
];

/**
 * Where a pinned model's weights land.
 *
 * Transformers.js keys its cache by revision when one is given, so two revisions of the same
 * id sit side by side and can never be confused for each other. That is worth more than the
 * shorter path: it makes the pin real on the filesystem rather than only in a comment.
 */
function cachedAt(model, cache = MODEL_CACHE) {
  return join(cache, ...model.id.split("/"), model.revision, "onnx", "model.onnx");
}

for (const model of SEGMENTATION_MODELS) model.cachedAt = (cache) => cachedAt(model, cache);

export const DEFAULT_MODEL = SEGMENTATION_MODELS.find((model) => model.default);

export function findModel(key) {
  const model = SEGMENTATION_MODELS.find((candidate) => candidate.key === key || candidate.id === key);
  if (!model) {
    const known = SEGMENTATION_MODELS.map((candidate) => candidate.key).join(", ");
    throw new Error(`unknown model "${key}" — known models are ${known}`);
  }
  return model;
}

let loaded = null;

/**
 * Load the model once per process, from the cache only.
 *
 * `allowRemoteModels = false` is the whole point: it turns a missing download into an error
 * naming the fix, instead of a silent network call from a tool that promises never to make one.
 */
async function load(model) {
  if (loaded?.key === model.key) return loaded;

  if (!existsSync(model.cachedAt(MODEL_CACHE))) {
    throw new Error(`${model.id} is not cached — run \`node scripts/fetch-model.mjs\` once (free, ~15 MB)`);
  }

  let transformers;
  try {
    transformers = require("@huggingface/transformers");
  } catch {
    throw new Error("this needs the app's dependencies: run `npm ci --prefix app` first");
  }

  const { AutoModelForSemanticSegmentation, AutoProcessor, RawImage, env } = transformers;
  env.cacheDir = MODEL_CACHE;
  env.allowRemoteModels = false;

  const started = Date.now();
  const runner = await AutoModelForSemanticSegmentation.from_pretrained(model.id, { revision: model.revision });
  const processor = await AutoProcessor.from_pretrained(model.id, { revision: model.revision });
  loaded = { key: model.key, model, runner, processor, RawImage, loadMs: Date.now() - started };
  return loaded;
}

/**
 * Segment one frame, returning raw logits.
 *
 * The logits come back at the model's own resolution — 128x128 for this family, a quarter of
 * its 512x512 input in each direction — and are NOT upsampled here. That is deliberate: the
 * grass grid resamples a mask onto the depth grid itself and says so in its API, so upsampling
 * twice would invent detail the model never produced. On a 576x1024 frame each logit cell
 * covers roughly 4.5 by 8 source pixels, and that is the honest resolution of this instrument.
 */
export async function segmentFrame(framePath, model = DEFAULT_MODEL) {
  const { runner, processor, RawImage, loadMs } = await load(model);
  const image = await RawImage.read(framePath);
  const inputs = await processor(image);
  const started = Date.now();
  const { logits } = await runner(inputs);
  const inferMs = Date.now() - started;

  const [, classes, height, width] = logits.dims;
  return {
    logits: { data: logits.data, classes, height, width },
    frame: { width: image.width, height: image.height },
    timing: { loadMs, inferMs },
    model,
  };
}
