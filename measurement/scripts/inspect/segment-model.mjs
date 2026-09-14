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
 *
 * The ADE20K models are not grass models: they are the second opinion on what is NOT grass.
 * Cityscapes leaves its guard-rail label out of the nineteen classes its models predict, so a
 * highway W-beam or a concrete barrier is `terrain` to the Cityscapes B0 in many frames. ADE20K
 * has 150 classes, `fence`, `railing`, `wall` and `bannister` among them, and on 2026-09-14 its
 * B2 and B4 painted the rail of `20260913-161156-40b383` frame 60 `fence` and the barrier of
 * frame 23 `wall` where the Cityscapes model said grass. B4 (246 MB) runs no slower than B2
 * (106 MB) on this CPU — 869 against 886 ms a frame over five frames, the decoder is the cost —
 * and finds a little more of the rail, so B4 is the one the pipeline is pointed at.
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
  {
    key: "ade20k-b2",
    id: "Xenova/segformer-b2-finetuned-ade-512-512",
    revision: "df795789e70f4089c8658907679c6fd2367c89a5",
    runtime: "@huggingface/transformers@3.8.1",
    default: false,
  },
  {
    key: "ade20k-b4",
    id: "Xenova/segformer-b4-finetuned-ade-512-512",
    revision: "0f92f0b465567a1a1f004ec33110992b6158edbc",
    runtime: "@huggingface/transformers@3.8.1",
    default: false,
  },
  // A prompted model: it has no classes, it answers a phrase with a probability per pixel at
  // 352x352. Asked "guardrail", "guard rail" and "concrete barrier" it found the rail in fog and
  // rain where both SegFormers saw grass (2026-09-14), and on two ONNX threads — the worker's
  // budget — it takes 0.75 s a frame where the ADE20K B4 takes 3.9. fp16 halves the weights to
  // 273 MB and gave the same probabilities as fp32 on the frames tried.
  {
    key: "clipseg",
    id: "Xenova/clipseg-rd64-refined",
    revision: "924dc94f85f58739f353f94258b33bc47eae4862",
    runtime: "@huggingface/transformers@3.8.1",
    kind: "prompted",
    dtype: "fp16",
    file: "model_fp16.onnx",
    default: false,
  },
  // A query model trained on Mapillary Vistas, the one public label set that names `Guard Rail`
  // and `Barrier` outright (ids 4 and 5 of 65). A MaskFormer predicts a hundred queries, each a
  // class distribution and a mask; the semantic map is their product, computed here because
  // transformers.js 3.8.1 has no semantic post-processing for it. Fed the frame at its own size:
  // at a 512 short side the barrier of `20260913-161156-40b383` frame 23 turned into `Wall`, and
  // the int8 file painted the sky `Building` (2026-09-14). 158 MB.
  {
    key: "vistas-r50",
    id: "onnx-community/maskformer-resnet50-vistas",
    revision: "d744d54982b5fb33c404ef8a9712666fd794075d",
    runtime: "@huggingface/transformers@3.8.1",
    kind: "queries",
    size: { shortest_edge: 576, longest_edge: 1024 },
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
  return join(cache, ...model.id.split("/"), model.revision, "onnx", model.file ?? "model.onnx");
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

/** Every model loaded so far, by key: a pipeline running two of them per frame keeps both warm. */
const loaded = new Map();

/**
 * Load a model once per process, from the cache only.
 *
 * `allowRemoteModels = false` is the whole point: it turns a missing download into an error
 * naming the fix, instead of a silent network call from a tool that promises never to make one.
 */
async function load(model) {
  if (loaded.has(model.key)) return loaded.get(model.key);

  if (!existsSync(model.cachedAt(MODEL_CACHE))) {
    throw new Error(`${model.id} is not cached — run \`node scripts/fetch-model.mjs\` once (free, ~15 MB)`);
  }

  let transformers;
  try {
    transformers = require("@huggingface/transformers");
  } catch {
    throw new Error("this needs the app's dependencies: run `npm ci --prefix app` first");
  }

  const { AutoModelForSemanticSegmentation, AutoProcessor, AutoTokenizer, CLIPSegForImageSegmentation, MaskFormerForInstanceSegmentation, RawImage, env } = transformers;
  env.cacheDir = MODEL_CACHE;
  env.allowRemoteModels = false;

  const started = Date.now();
  const pinned = { revision: model.revision, ...(model.dtype ? { dtype: model.dtype } : {}) };
  if (model.kind === "prompted") {
    const runner = await CLIPSegForImageSegmentation.from_pretrained(model.id, pinned);
    const processor = await AutoProcessor.from_pretrained(model.id, { revision: model.revision });
    const tokenizer = await AutoTokenizer.from_pretrained(model.id, { revision: model.revision });
    const entry = { key: model.key, model, runner, processor, tokenizer, RawImage, labels: null, loadMs: Date.now() - started };
    loaded.set(model.key, entry);
    return entry;
  }
  const runner = model.kind === "queries"
    ? await MaskFormerForInstanceSegmentation.from_pretrained(model.id, pinned)
    : await AutoModelForSemanticSegmentation.from_pretrained(model.id, pinned);
  const processor = await AutoProcessor.from_pretrained(model.id, { revision: model.revision });
  if (model.size) processor.image_processor.size = { ...model.size };
  // The checkpoint's own class names, by id. ADE20K spells a class with its synonyms
  // ("building, edifice"); the first name is the one a request can ask for.
  const count = Object.keys(runner.config.id2label ?? {}).length;
  const labels = Array.from({ length: count }, (_, id) => String(runner.config.id2label[id]).split(",")[0].trim());
  const entry = { key: model.key, model, runner, processor, RawImage, labels, loadMs: Date.now() - started };
  loaded.set(model.key, entry);
  return entry;
}

/** The class names a model predicts, in id order, read from its own config. Loads it if needed. */
export async function modelLabels(model = DEFAULT_MODEL) {
  return (await load(model)).labels;
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
  if (model.kind === "prompted") throw new Error(`${model.key} answers prompts, not classes: use promptFrame`);
  if (model.kind === "queries") throw new Error(`${model.key} answers with queries, not logits: use queriesFrame`);
  const { runner, processor, RawImage, labels, loadMs } = await load(model);
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
    labels,
  };
}

/**
 * Ask a prompted model how much each pixel is what each phrase names.
 *
 * One sigmoid per prompt per pixel, on the model's own 352x352 grid, not upsampled, for the
 * reason `segmentFrame` gives. The prompts are the caller's: the model has no classes to
 * validate them against, so a misspelt phrase is a weaker answer, not an error.
 */
export async function promptFrame(framePath, prompts, model) {
  if (model.kind !== "prompted") throw new Error(`${model.key} has classes, not prompts: use segmentFrame`);
  if (!Array.isArray(prompts) || !prompts.length) throw new Error("promptFrame needs at least one prompt");
  const { runner, processor, tokenizer, RawImage, loadMs } = await load(model);
  const image = await RawImage.read(framePath);
  const text = tokenizer(prompts, { padding: true, truncation: true });
  const inputs = await processor(image);
  const started = Date.now();
  const { logits } = await runner({ ...text, ...inputs });
  const inferMs = Date.now() - started;

  const [count, height, width] = logits.dims;
  const probabilities = new Float32Array(count * height * width);
  for (let i = 0; i < probabilities.length; i++) probabilities[i] = 1 / (1 + Math.exp(-Number(logits.data[i])));
  return {
    probabilities,
    prompts: count,
    height,
    width,
    frame: { width: image.width, height: image.height },
    timing: { loadMs, inferMs },
    model,
  };
}

/**
 * Ask a query model for its class mass per pixel.
 *
 * A MaskFormer predicts Q queries, each a distribution over the classes plus "no object" and a
 * mask over the frame; the semantic answer at a pixel is, per class, the sum over queries of the
 * class probability times the mask's sigmoid — the product HF's own post-processing takes the
 * argmax of. The masses are returned whole, at the model's own quarter-resolution mask grid, so a
 * caller can weigh a set of classes against the rest instead of asking only who won.
 */
export async function queriesFrame(framePath, model) {
  if (model.kind !== "queries") throw new Error(`${model.key} has logits, not queries: use segmentFrame`);
  const { runner, processor, RawImage, labels, loadMs } = await load(model);
  const image = await RawImage.read(framePath);
  const inputs = await processor(image);
  const started = Date.now();
  const output = await runner(inputs);
  const inferMs = Date.now() - started;

  const classLogits = output.class_queries_logits;
  const maskLogits = output.masks_queries_logits;
  const [, queries, withNothing] = classLogits.dims;
  const classes = withNothing - 1;
  const [, , height, width] = maskLogits.dims;
  const pixels = height * width;
  const cl = classLogits.data;
  const mk = maskLogits.data;
  const mass = new Float32Array(classes * pixels);
  const probs = new Float32Array(withNothing);
  for (let q = 0; q < queries; q++) {
    let max = -Infinity;
    for (let c = 0; c < withNothing; c++) { const v = Number(cl[q * withNothing + c]); if (v > max) max = v; }
    let sum = 0;
    for (let c = 0; c < withNothing; c++) { probs[c] = Math.exp(Number(cl[q * withNothing + c]) - max); sum += probs[c]; }
    // Queries that are nearly all "no object" contribute nothing worth the pixels' time.
    const active = [];
    for (let c = 0; c < classes; c++) { probs[c] /= sum; if (probs[c] > 1e-3) active.push(c); }
    if (!active.length) continue;
    const base = q * pixels;
    for (let p = 0; p < pixels; p++) {
      const m = 1 / (1 + Math.exp(-Number(mk[base + p])));
      if (m < 1e-3) continue;
      for (const c of active) mass[c * pixels + p] += probs[c] * m;
    }
  }
  return {
    mass,
    classes,
    height,
    width,
    labels,
    frame: { width: image.width, height: image.height },
    timing: { loadMs, inferMs },
    model,
  };
}
