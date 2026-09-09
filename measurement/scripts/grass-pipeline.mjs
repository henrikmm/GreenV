// The local worker entry point and the app both call this function. No cloud or network access.
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { basename } from "node:path";
import { typed } from "./inspect/typed.mjs";
import { resolveRun, readArrays, readCloud, frameFiles, readManifest, readMeasurementEvidence } from "./inspect/source.mjs";
import { segmentFrame, DEFAULT_MODEL, MODEL_CACHE } from "./inspect/segment-model.mjs";
import { apply4x4, invert4x4, projectToPixel } from "./inspect/render.mjs";
import { QUALITY_SCHEMA, encodeRuns, decodeRuns, roadContext, qualitySummary, compareAssessments } from "./grass-quality.mjs";

const require = createRequire(new URL("../app/package.json", import.meta.url));
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const fitOptions = { maxTiltDeg: 30, inlierDistance: 0.035, iterations: 1200, stride: 16,
  minInliers: 100, minInlierFraction: 0.01, proposalFractions: [1, 0.35], maxBelowFraction: 0.2, seed: 7 };

/**
 * Which Cityscapes classes count as the area of interest, as a caller's explicit choice.
 *
 * The default stays `terrain` alone. Broadening it is a measurement, not a configuration
 * change: on run `20260814-174814-b245bc` frame 80, `terrain` recovered 0% of the recorded
 * clumping-plant brush and `vegetation` 41.0%, so neither policy is established for an
 * arbitrary target and the right one depends on what the caller is measuring
 * (`docs/evidence/2026-09-05-class-fit.md`). The choice is recorded on every frame, so a
 * packet says what it segmented rather than leaving a reader to assume the default.
 */
export function rolesFor(classes, labels) {
  const wanted = [...new Set(
    (Array.isArray(classes) ? classes : String(classes).split(","))
      .map((value) => String(value).trim()).filter(Boolean),
  )];
  if (!wanted.length) throw new Error("classes must name at least one Cityscapes label");
  const unknown = wanted.filter((label) => !labels.includes(label));
  if (unknown.length) {
    throw new Error(`unknown Cityscapes label(s) ${unknown.join(", ")}; valid labels are ${labels.join(", ")}`);
  }
  return { wanted, roles: labels.map((label) => wanted.includes(label) ? "grass" : "excluded") };
}

export async function runGrassPipeline(request, onProgress = () => {}, signal) {
  const started = performance.now();
  const check = () => { if (signal?.aborted) throw new Error("cancelled"); };
  const progress = (phase, done, total) => { check(); onProgress({ phase, done, total, elapsedMs: performance.now() - started }); };
  if (!Number.isFinite(request.offsetM ?? 2) || Math.abs(request.offsetM ?? 2) > 6) throw new Error("offsetM must be between -6 and 6");
  const context = roadContext(request.context);
  const run = resolveRun(request.runId);
  const T = await typed();
  const { wanted: semanticClasses, roles } = rolesFor(request.classes ?? ["terrain"], T.CITYSCAPES_LABELS);
  progress("reading", 0, 0);
  const arrays = await readArrays(run);
  const geometryFrames = T.framesFromArrays(arrays);
  const files = frameFiles(run.frames);
  if (!files.length || files.length !== geometryFrames.length) throw new Error("source JPEG and depth frame counts must match exactly");
  const cloud = readCloud(run.glb);
  if (!cloud.alignment) throw new Error("run has no hf_alignment; cannot register evidence");
  const worldFromDa3 = Array.from(cloud.alignment);
  const rawGravity = T.estimateGravity(arrays.extrinsics.data);
  const m = worldFromDa3, u = rawGravity.up;
  const gravityUp = T.normalize([m[0]*u[0]+m[1]*u[1]+m[2]*u[2], m[4]*u[0]+m[5]*u[1]+m[6]*u[2], m[8]*u[0]+m[9]*u[1]+m[10]*u[2]]);
  let fit = null, groundError = null, edge = null;
  try {
    fit = T.fitGroundPlaneRobust(cloud.points, { ...fitOptions, up: gravityUp });
    const positions = T.cameraCentres(arrays.extrinsics.data).map((p) => apply4x4(m, p));
    edge = T.roadEdgeFromCameraTrack(positions, fit.plane, request.offsetM ?? 2);
  } catch (error) { groundError = error.message; }
  const ground = { available: !!fit, source: "global-plane-from-recorded-glb", status: "unvalidated",
    error: groundError, plane: fit?.plane ?? null, gravityUp, gravityCoherence: rawGravity.coherence,
    rmseM: fit?.rmse ?? null, inlierFraction: fit?.inlierFraction ?? null,
    belowFraction: fit?.belowFraction ?? null, tiltDeg: fit?.tiltDeg ?? null, fitOptions,
    limitation: "A global plane cannot establish soil beneath a crowned shoulder, ditch or hidden ground." };
  const frames = [], inputs = [], images = [];
  const inverse = invert4x4(worldFromDa3);
  const projected = (point, index) => {
    const p = projectToPixel(apply4x4(inverse, point), arrays.extrinsics.data, arrays.intrinsics.data, index);
    return p ? [p[0] / geometryFrames[index].width, p[1] / geometryFrames[index].height] : null;
  };
  const sharp = require("sharp");
  const manifest = readManifest(run);
  const fps = manifest?.frames?.effective_fps ?? manifest?.frames?.effectiveFps ?? manifest?.params?.fps;
  for (let i = 0; i < files.length; i++) {
    progress("segmenting", i, files.length);
    const bytes = await readFile(files[i]);
    const canonicalFrame = Number(basename(files[i]).match(/(\d+)/)?.[1]);
    const timestampS = fps > 0 ? i / fps : null;
    // Route identity is run-level unless a caller supplies an exact frame record. Never infer km from distance in a garden.
    const location = roadContext(request.frameContext?.[canonicalFrame] ?? { ...context, km: null, capturado_em: null });
    const frame = { frameIndex: i, canonicalFrame, file: basename(files[i]), timestampS, ...location,
      sourceWidth: (await sharp(bytes).metadata()).width, sourceHeight: (await sharp(bytes).metadata()).height,
      sourceSha256: hash(bytes), depthWidth: geometryFrames[i].width, depthHeight: geometryFrames[i].height };
    try {
      const result = await segmentFrame(files[i]);
      check();
      frame.sourceWidth = result.frame.width; frame.sourceHeight = result.frame.height;
      const mask = T.grassMaskFromLogits(result.logits, { roles });
      frame.status = mask.counts.kept ? "segmented" : "no-grass-detected";
      frame.mask = { width: mask.width, height: mask.height, runs: encodeRuns(mask.mask), sha256: hash(mask.mask) };
      frame.semantic = { kind: "semantic", modelId: DEFAULT_MODEL.id, modelRevision: DEFAULT_MODEL.revision,
        runtime: DEFAULT_MODEL.runtime, device: "cpu", probabilityFloor: mask.minProbability, classes: semanticClasses,
        counts: mask.counts, inferenceMs: result.timing.inferMs, modelLoadMs: result.timing.loadMs };
      inputs.push({ frameIndex: i, geometryFrame: geometryFrames[i], grassMask: mask.mask, maskWidth: mask.width, maskHeight: mask.height });
    } catch (error) { check(); frame.status = "failed"; frame.error = error.message; }
    const thumbnail = await sharp(bytes).resize({ width: 480, height: 480, fit: "inside", withoutEnlargement: true }).jpeg({ quality: 78 }).toBuffer();
    images.push(`data:image/jpeg;base64,${thumbnail.toString("base64")}`);
    frame.referenceLines = edge ? edge.polyline.slice(1).map((point, n) => [projected(edge.polyline[n], i), projected(point, i)]).filter((line) => line.every(Boolean)) : [];
    frames.push(frame);
    progress("segmenting", i + 1, files.length);
  }
  const input = edge && fit ? { runId: run.id, frames: inputs, worldFromDa3, roadEdgeWorld: edge.polyline,
    ground: { plane: fit.plane, gravityUp, planeRmseM: fit.rmse }, options: { cellSizeM: 0.5, voxelSizeM: 0.02, minFrames: 3, minVoxelsPerFrame: 20, maxDistanceFromRoadM: 5 } } : null;
  let assessment = null, provenance = null;
  if (input) {
    for (const step of T.measureGrassHeightGridStaged(input, { collectProvenance: true })) {
      progress("measuring", step.framesDone, step.frameTotal);
      if (step.frameIndex !== null) frames[step.frameIndex].retainedPixels = step.frameObservations;
      if (step.phase === "done") { assessment = step.assessment; provenance = step.provenance; }
      await new Promise((r) => setTimeout(r, 0));
    }
  }
  const cells = assessment?.measurements.map((cell) => {
    const key = `${Math.round(cell.coordinate.alongRoadM / 0.5 - 0.5)},${Math.round(cell.coordinate.distanceFromRoadM / 0.5 - 0.5)}`;
    const p = provenance.get(key);
    const pixels = [...(p?.pixelsByFrame ?? [])].map(([frameIndex, indices]) => {
      const mask = new Uint8Array(geometryFrames[frameIndex].width * geometryFrames[frameIndex].height);
      for (const index of indices) mask[index] = 1;
      return { frameIndex, runs: encodeRuns(mask) };
    });
    return { key, ...cell, ...roadContext({ ...context, km: null, capturado_em: null }), pixels };
  }) ?? [];
  const corridor = { source: "camera-track-offset", offsetM: request.offsetM ?? 2, widthM: 5,
    lengthM: edge?.lengthM ?? null, polyline: edge?.polyline ?? [], side: "unsigned-both-sides-folded", status: "assumed" };
  const comparison = [];
  if (request.against?.length && input) {
    const packets = readMeasurementEvidence(run);
    const humanFrames = [], autoFrames = [];
    const seen = new Set();
    for (const id of request.against) {
      const matches = packets.filter((p) => p.observation?.id === id || p.evidenceId === id);
      if (matches.length !== 1) throw new Error(`reference ${id} must identify exactly one recorded trial`);
      const p = matches[0], observation = p.observation;
      const frame = frames.find((f) => f.canonicalFrame === observation.canonicalFrame);
      if (!frame?.mask || seen.has(frame.frameIndex)) throw new Error("reference frame absent, failed or duplicated");
      seen.add(frame.frameIndex);
      const h = observation.mask;
      const human = decodeRuns(h.runs, h.width * h.height);
      const prediction = T.resampleMaskNearest(decodeRuns(frame.mask.runs, frame.mask.width * frame.mask.height), frame.mask.width, frame.mask.height, h.width, h.height);
      comparison.push({ evidenceId: p.evidenceId, frameIndex: frame.frameIndex, canonicalFrame: frame.canonicalFrame,
        ...T.scoreSemanticMask(prediction, human, frame.canonicalFrame, observation.canonicalFrame),
        referenceScope: "user-supplied-mask; evaluate only fully annotated target regions", humanMask: { width: h.width, height: h.height, runs: h.runs, sha256: hash(human) } });
      humanFrames.push({ frameIndex: frame.frameIndex, geometryFrame: geometryFrames[frame.frameIndex], grassMask: human, maskWidth: h.width, maskHeight: h.height });
      autoFrames.push(inputs.find((f) => f.frameIndex === frame.frameIndex));
    }
    const drain = (frames) => { let result; for (const s of T.measureGrassHeightGridStaged({ ...input, frames })) if (s.phase === "done") result = s.assessment; return result; };
    comparison.push({ measurementDelta: compareAssessments(drain(autoFrames), drain(humanFrames)), physicalAccuracy: "not-established" });
  }
  progress("packaging", files.length, files.length);
  const implementationPaths = ["scripts/grass-pipeline.mjs", "scripts/grass-quality.mjs", "scripts/inspect/segment-model.mjs", "geometry/grass-height-grid.ts", "geometry/semantic-mask.ts", "app/src/measurement/grass-grid.ts"];
  const implementation = Object.fromEntries(await Promise.all(implementationPaths.map(async path => [path, hash(await readFile(new URL(`../${path}`, import.meta.url)))])));
  const weights = await readFile(DEFAULT_MODEL.cachedAt(MODEL_CACHE)).catch(() => null);
  const bundle = { schemaVersion: QUALITY_SCHEMA, runId: run.id, ...context, createdAt: new Date().toISOString(),
    assessment, cells, frames, ground, corridor, comparison, semanticClasses,
    quality: qualitySummary(assessment, frames, context, ground, corridor),
    reproducibility: { model: { id: DEFAULT_MODEL.id, revision: DEFAULT_MODEL.revision, runtime: DEFAULT_MODEL.runtime, device: "cpu", dtype: "fp32" },
      implementation,
      inputs: { modelWeightsSha256: weights ? hash(weights) : null, npzSha256: hash(await readFile(run.npz)), glbSha256: hash(await readFile(run.glb)), manifestSha256: hash(await readFile(run.manifestPath)) },
      worldFromDa3, options: input?.options ?? null, sourceFrames: "all saved sampled frames, in recorded order", manifest },
    timing: { totalMs: performance.now() - started, segmentationMs: frames.reduce((s, f) => s + (f.semantic?.inferenceMs ?? 0), 0) },
  };
  bundle.contentSha256 = hash(JSON.stringify({ assessment: assessment && { ...assessment, review: undefined }, cells, frames: frames.map(({ semantic, ...f }) => ({ ...f, semantic: semantic && { ...semantic, inferenceMs: undefined, modelLoadMs: undefined } })), ground, corridor, comparison, reproducibility: bundle.reproducibility, context }));
  return { bundle, images };
}
