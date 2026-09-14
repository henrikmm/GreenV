#!/usr/bin/env node
// Download the segmentation model into a local cache. THE ONLY FILE IN scripts/ THAT USES
// THE NETWORK.
//
// It is separate for one reason. `scripts/inspect.mjs` states three load-bearing properties
// and the first is that it cannot spend money because it contains no network code at all.
// Segmenting a frame needs 15 MB of model weights, and the honest way to keep that promise is
// to fetch them here, once, and have the inspector read the cache with remote loading turned
// off. Then `inspect segment` works on a plane, cannot stall on a hub outage, and cannot wake
// anything that bills.
//
// This is free. Hugging Face serves these weights over plain HTTPS with no account and no
// quota; the cost is bandwidth and about fifteen seconds.
//
// Only the default model is fetched. B2 is registered so the finding that it invents roads out
// of walls can be reproduced, and it is 110 MB, so it is fetched only when asked for by name.
//
//   node scripts/fetch-model.mjs                  # fetch the default if it is missing
//   node scripts/fetch-model.mjs --check          # say what is cached, download nothing
//   node scripts/fetch-model.mjs cityscapes-b2    # fetch one by name

import { createRequire } from "node:module";
import { existsSync } from "node:fs";
import { relative } from "node:path";
import { DEFAULT_MODEL, MODEL_CACHE, SEGMENTATION_MODELS, findModel } from "./inspect/segment-model.mjs";
import { REPO } from "./inspect/source.mjs";

const require = createRequire(new URL("../app/package.json", import.meta.url));

const argv = process.argv.slice(2);
const check = argv.includes("--check");
const named = argv.filter((token) => !token.startsWith("--"));
const wanted = named.length > 0 ? named.map(findModel) : [DEFAULT_MODEL];

console.log(`cache ${relative(REPO, MODEL_CACHE) || MODEL_CACHE}`);
for (const model of SEGMENTATION_MODELS) {
  const cached = existsSync(model.cachedAt(MODEL_CACHE));
  const asked = wanted.includes(model);
  console.log(`  ${cached ? "cached " : asked ? "MISSING" : "absent "}  ${model.id}`);
  console.log(`            revision ${model.revision}${asked ? "" : "  (not requested)"}`);
}

const missing = wanted.filter((model) => !existsSync(model.cachedAt(MODEL_CACHE)));

if (check) {
  console.log(missing.length === 0 ? "\nrequested models cached — inspect segment can run offline" : `\n${missing.length} to fetch: run without --check`);
  process.exit(missing.length === 0 ? 0 : 1);
}

if (missing.length === 0) {
  console.log("\nnothing to do");
  process.exit(0);
}

let transformers;
try {
  transformers = require("@huggingface/transformers");
} catch {
  console.error("\nthis needs the app's dependencies: run `npm ci --prefix app` first");
  process.exit(1);
}

const { AutoModelForSemanticSegmentation, AutoProcessor, AutoTokenizer, CLIPSegForImageSegmentation, MaskFormerForInstanceSegmentation, env } = transformers;
env.cacheDir = MODEL_CACHE;

for (const model of missing) {
  console.log(`\nfetching ${model.id} @ ${model.revision}`);
  const started = Date.now();
  // Pinned by revision, not by `main`. A model id that follows a moving branch is not
  // reproducible evidence, which is the same rule segmenter.ts applies to SlimSAM.
  const pinned = { revision: model.revision, ...(model.dtype ? { dtype: model.dtype } : {}) };
  if (model.kind === "prompted") {
    await CLIPSegForImageSegmentation.from_pretrained(model.id, pinned);
    await AutoTokenizer.from_pretrained(model.id, { revision: model.revision });
  } else if (model.kind === "queries") {
    await MaskFormerForInstanceSegmentation.from_pretrained(model.id, pinned);
  } else {
    await AutoModelForSemanticSegmentation.from_pretrained(model.id, pinned);
  }
  await AutoProcessor.from_pretrained(model.id, { revision: model.revision });
  console.log(`  done in ${((Date.now() - started) / 1000).toFixed(1)} s`);
}

console.log("\ncached — inspect segment can now run offline");
