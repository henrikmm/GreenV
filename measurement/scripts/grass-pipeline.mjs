// The local worker entry point and the app both call this function. No cloud or network access.
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { basename } from "node:path";
import { typed } from "./inspect/typed.mjs";
import { resolveRun, readArrays, readCloud, frameFiles, readManifest, readMeasurementEvidence } from "./inspect/source.mjs";
import { segmentFrame, promptFrame, findModel, modelLabels, DEFAULT_MODEL, MODEL_CACHE } from "./inspect/segment-model.mjs";
import { apply4x4, invert4x4, projectToPixel } from "./inspect/render.mjs";
import { QUALITY_SCHEMA, encodeRuns, decodeRuns, roadContext, qualitySummary, compareAssessments } from "./grass-quality.mjs";
import { lateralProfile, chooseBand, trackLengthScale, cameraHeightScale, scaleAffine, polylineLength, ontoPlane } from "./grass-anchor.mjs";

const require = createRequire(new URL("../app/package.json", import.meta.url));
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const fitOptions = { maxTiltDeg: 30, inlierDistance: 0.035, iterations: 1200, stride: 16,
  minInliers: 100, minInlierFraction: 0.01, proposalFractions: [1, 0.35], maxBelowFraction: 0.2, seed: 7 };
const gridDefaults = { cellSizeM: 0.5, voxelSizeM: 0.02, minFrames: 3, minVoxelsPerFrame: 20, maxDistanceFromRoadM: 5,
  maxHeightM: Infinity, canopyExtentM: Infinity, canopyGapM: Infinity, datum: "pooled", bandSide: "both", slopeRiseM: Infinity,
  structureFrames: Infinity, pastEnds: "fold" };

/**
 * The second, coarser ground fit a caller may allow when the first finds no floor.
 *
 * A wet road reflects the sky, and the depth model reads the reflection as depth scattered
 * below the surface: the true ground is then a thin layer no 3.5 cm plane can gather 1% of.
 * On 2026-09-13 one segment's best plane held 0.80% of the cloud against that floor and the
 * segment measured nothing, though its verge is in every frame. Twice the inlier distance and
 * half the support floor recover a plane there at 5 degrees of tilt; the packet says the fit
 * was relaxed, and the camera must still stand a plausible height above the result.
 */
const relaxedFit = { inlierDistance: 0.07, minInlierFraction: 0.005 };
const CAMERA_ABOVE_RELAXED_PLANE_M = [0.3, 6];

/**
 * The request fields a vehicle-mounted capture adds, validated and defaulted to "do nothing".
 *
 * Every default here reproduces the pipeline as it was before 2026-09-13, so a walked fixture
 * measures exactly as recorded. A car asks for each one explicitly; see `grass-anchor.mjs`.
 */
export function vehicleOptions(request) {
  const offsetSide = request.offsetSide ?? "given";
  if (!["given", "auto"].includes(offsetSide)) throw new Error(`offsetSide must be "given" or "auto", got ${JSON.stringify(offsetSide)}`);
  const minTrackM = request.minTrackM ?? 0;
  if (!Number.isFinite(minTrackM) || minTrackM < 0) throw new Error("minTrackM must be zero or a positive number of metres");
  const cameraHeightM = request.cameraHeightM ?? null;
  if (cameraHeightM !== null && !(cameraHeightM > 0 && cameraHeightM < 10)) throw new Error(`cameraHeightM must be a height in metres, got ${JSON.stringify(cameraHeightM)}`);
  const trackLengthM = request.trackLengthM ?? null;
  if (trackLengthM !== null && !(trackLengthM >= 0 && trackLengthM < 10000)) throw new Error(`trackLengthM must be a length in metres, got ${JSON.stringify(trackLengthM)}`);
  const minProbability = request.minProbability ?? null;
  if (minProbability !== null && !(minProbability >= 0 && minProbability <= 1)) throw new Error("minProbability must be between 0 and 1");
  const gridOptions = { ...gridDefaults };
  for (const [key, value] of Object.entries(request.gridOptions ?? {})) {
    if (!(key in gridDefaults)) throw new Error(`unknown grid option ${key}; known: ${Object.keys(gridDefaults).join(", ")}`);
    if (key === "datum") {
      if (value !== "pooled" && value !== "per-frame") throw new Error(`grid option datum must be "pooled" or "per-frame"`);
      gridOptions.datum = value;
      continue;
    }
    if (key === "bandSide") {
      if (!["both", "positive", "negative"].includes(value)) throw new Error(`grid option bandSide must be "both", "positive" or "negative"`);
      gridOptions.bandSide = value;
      continue;
    }
    if (key === "pastEnds") {
      if (value !== "fold" && value !== "drop") throw new Error(`grid option pastEnds must be "fold" or "drop"`);
      gridOptions.pastEnds = value;
      continue;
    }
    if (key === "structureFrames") {
      if (!(value === Infinity || (Number.isInteger(value) && value >= 1))) throw new Error("grid option structureFrames must be a whole number of frames, at least 1");
      gridOptions.structureFrames = value;
      continue;
    }
    const ceiling = key === "maxHeightM" || key === "canopyExtentM" || key === "canopyGapM" || key === "slopeRiseM";
    if (!(value > 0) || (!ceiling && !Number.isFinite(value))) throw new Error(`grid option ${key} must be a positive number`);
    gridOptions[key] = value;
  }
  const widthPinned = request.gridOptions?.maxDistanceFromRoadM !== undefined;
  const groundFallback = request.groundFallback ?? false;
  if (typeof groundFallback !== "boolean") throw new Error("groundFallback must be true or false");
  // Pixels next to a fence, a wall, a pole or a building are not measured. At 128x128 logits
  // one class pixel is 4.5 by 8 photograph pixels, and the grass beside a guardrail carries the
  // rail's lower edge with it: on 2026-09-13 the tallest cells of a mown strip were the strip's
  // last half metre against the rail, 40-57 cm where the strip read 3-9. Empty, the default,
  // excludes nothing.
  const excludeNear = [...new Set(String(request.excludeNearClasses ?? "").split(",").map((v) => v.trim()).filter(Boolean))];
  const excludeNearPx = request.excludeNearPx ?? 1;
  if (!Number.isInteger(excludeNearPx) || excludeNearPx < 0 || excludeNearPx > 8) throw new Error("excludeNearPx must be a whole number of logit pixels, 0 to 8");
  const bandSidePinned = request.gridOptions?.bandSide !== undefined;
  // A second segmentation, asked only what is NOT grass. The grass model is Cityscapes-trained
  // and Cityscapes never taught it a guardrail, so a wet W-beam or a concrete barrier is
  // `terrain` to it in many frames; ADE20K knows `fence`, `railing`, `wall` and `bannister`.
  // Its named classes join the structure map: taken out of the grass mask with the same margin,
  // and handed to the grid so a cell one stands in can be refused. Null runs one model only.
  const structureModel = request.structureModel ?? null;
  if (structureModel !== null && typeof structureModel !== "string") throw new Error("structureModel must be the key of a registered segmentation model, or null");
  if (structureModel !== null) findModel(structureModel);
  const structureClasses = [...new Set(String(request.structureClasses ?? "").split(",").map((v) => v.trim()).filter(Boolean))];
  if (structureModel !== null && !structureClasses.length) throw new Error("structureClasses must name at least one class of the structure model");
  if (structureModel === null && structureClasses.length) throw new Error("structureClasses needs a structureModel to read them from");
  // The second model spreads a guardrail over `fence`, `railing`, `wall` and `bannister`, so no
  // one class need win a pixel: it is a structure when the probability it gives its structure
  // classes, summed, reaches this floor. 0.5 is a majority of the probability.
  const structureFloor = request.structureFloor ?? 0.5;
  if (!(structureFloor > 0 && structureFloor <= 1)) throw new Error("structureFloor must be a probability above 0 and at most 1");
  return { offsetSide, minTrackM, cameraHeightM, trackLengthM, minProbability, gridOptions, widthPinned, groundFallback, excludeNear, excludeNearPx, bandSidePinned,
    structureModel, structureClasses, structureFloor };
}

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
  const vehicle = vehicleOptions(request);
  const run = resolveRun(request.runId);
  const T = await typed();
  const { wanted: semanticClasses, roles } = rolesFor(request.classes ?? ["terrain"], T.CITYSCAPES_LABELS);
  const unknownNear = vehicle.excludeNear.filter((label) => !T.CITYSCAPES_LABELS.includes(label));
  if (unknownNear.length) throw new Error(`unknown Cityscapes label(s) in excludeNearClasses: ${unknownNear.join(", ")}`);
  const excludeNearIds = new Set(vehicle.excludeNear.map((label) => T.CITYSCAPES_LABELS.indexOf(label)));
  // The second model's classes are read from its own checkpoint, so a request is checked against
  // what the model actually predicts rather than against a list kept here.
  const secondModel = vehicle.structureModel === null ? null : findModel(vehicle.structureModel);
  let secondIds = null;
  if (secondModel && secondModel.kind !== "prompted") {
    const labels = await modelLabels(secondModel);
    const unknown = vehicle.structureClasses.filter((label) => !labels.includes(label));
    if (unknown.length) throw new Error(`unknown ${secondModel.key} label(s) in structureClasses: ${unknown.join(", ")}`);
    secondIds = new Set(vehicle.structureClasses.map((label) => labels.indexOf(label)));
  }
  progress("reading", 0, 0);
  const arrays = await readArrays(run);
  const geometryFrames = T.framesFromArrays(arrays);
  const files = frameFiles(run.frames);
  if (!files.length || files.length !== geometryFrames.length) throw new Error("source JPEG and depth frame counts must match exactly");
  const cloud = readCloud(run.glb);
  if (!cloud.alignment) throw new Error("run has no hf_alignment; cannot register evidence");
  let worldFromDa3 = Array.from(cloud.alignment);
  const rawGravity = T.estimateGravity(arrays.extrinsics.data);
  const m = worldFromDa3, u = rawGravity.up;
  const gravityUp = T.normalize([m[0]*u[0]+m[1]*u[1]+m[2]*u[2], m[4]*u[0]+m[5]*u[1]+m[6]*u[2], m[8]*u[0]+m[9]*u[1]+m[10]*u[2]]);
  let fit = null, groundError = null, edge = null, positions = [], track = [];
  // The scale anchor. DA3 fixes one scalar per clip, so one scalar corrects it. A known camera
  // height wins when a caller has one; otherwise the length the vehicle drove over these very
  // frames, which the capture's telemetry measures and the reconstruction measures too. Both
  // the model's own numbers and the decision are recorded whether or not a factor was applied.
  const scale = { anchor: vehicle.cameraHeightM !== null ? "camera-height" : vehicle.trackLengthM !== null ? "track-length" : null,
    requested: vehicle.cameraHeightM !== null || vehicle.trackLengthM !== null,
    anchorCameraHeightM: vehicle.cameraHeightM, expectedTrackLengthM: vehicle.trackLengthM,
    measuredCameraHeightM: null, measuredTrackLengthM: null, factor: 1, applied: false, reason: "no-anchor" };
  let relaxed = null;
  try {
    try {
      fit = T.fitGroundPlaneRobust(cloud.points, { ...fitOptions, up: gravityUp });
    } catch (strict) {
      if (!vehicle.groundFallback) throw strict;
      // One coarser attempt, and only one: a plane the camera does not stand above is a wall or
      // a reflection, and the first refusal stands.
      const candidate = T.fitGroundPlaneRobust(cloud.points, { ...fitOptions, ...relaxedFit, up: gravityUp });
      const centres = T.cameraCentres(arrays.extrinsics.data).map((p) => apply4x4(m, p));
      const cameraAbove = T.median(centres.map((p) => T.signedHeight(candidate.plane, p)));
      if (!(cameraAbove >= CAMERA_ABOVE_RELAXED_PLANE_M[0] && cameraAbove <= CAMERA_ABOVE_RELAXED_PLANE_M[1])) throw strict;
      fit = candidate;
      relaxed = { ...relaxedFit, strictError: strict.message, cameraAbovePlaneM: cameraAbove };
    }
    positions = T.cameraCentres(arrays.extrinsics.data).map((p) => apply4x4(m, p));
    scale.measuredCameraHeightM = T.median(positions.map((p) => T.signedHeight(fit.plane, p)));
    scale.measuredTrackLengthM = polylineLength(positions.map((p) => ontoPlane(p, fit.plane))).lengthM;
    if (scale.anchor === "camera-height") Object.assign(scale, cameraHeightScale(vehicle.cameraHeightM, scale.measuredCameraHeightM));
    else if (scale.anchor === "track-length") Object.assign(scale, trackLengthScale(vehicle.trackLengthM, scale.measuredTrackLengthM));
    if (scale.applied) {
      // Scaling every point about the origin scales the plane's offset and its residual by the
      // same factor and leaves the normal alone, so the fit is carried over rather than redone.
      worldFromDa3 = scaleAffine(worldFromDa3, scale.factor);
      positions = positions.map((p) => [p[0] * scale.factor, p[1] * scale.factor, p[2] * scale.factor]);
      fit = { ...fit, plane: { normal: fit.plane.normal, offset: fit.plane.offset * scale.factor }, rmse: fit.rmse * scale.factor };
    }
    track = positions.map((p) => ontoPlane(p, fit.plane));
  } catch (error) { groundError = error.message; }
  const trackLength = polylineLength(track);
  // A track shorter than the caller's floor means the reconstruction did not see the vehicle
  // move; whatever is in the band then is a door handle or a tree, not a verge.
  const trackTooShort = fit !== null && vehicle.minTrackM > 0 && trackLength.lengthM < vehicle.minTrackM;
  const ground = { available: !!fit, source: "global-plane-from-recorded-glb", status: "unvalidated",
    error: groundError, plane: fit?.plane ?? null, gravityUp, gravityCoherence: rawGravity.coherence,
    rmseM: fit?.rmse ?? null, inlierFraction: fit?.inlierFraction ?? null,
    belowFraction: fit?.belowFraction ?? null, tiltDeg: fit?.tiltDeg ?? null, fitOptions: relaxed ? { ...fitOptions, ...relaxedFit } : fitOptions,
    // Null when the first fit held; otherwise what was relaxed, why, and where the camera stands.
    relaxedFit: relaxed,
    limitation: "A global plane cannot establish soil beneath a crowned shoulder, ditch or hidden ground." };
  const frames = [], inputs = [], images = [];
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
      const mask = T.grassMaskFromLogits(result.logits, { roles, ...(vehicle.minProbability === null ? {} : { minProbability: vehicle.minProbability }) });
      let droppedNearStructure = 0;
      // Where the excluded classes stand on the logit grid: taken out of the grass mask with a
      // margin here, and handed to the grid whole so a cell one stands in can be refused even in
      // the frames that called it grass. Two sources paint it: the grass model's own structure
      // classes, and the second model's when one is asked for.
      let structure = null;
      let second = null;
      if (excludeNearIds.size || secondModel) {
        const map = T.classMapFromLogits(result.logits);
        const w = map.width, h = map.height, r = vehicle.excludeNearPx;
        structure = new Uint8Array(w * h);
        for (let p = 0; p < w * h; p++) if (excludeNearIds.has(map.classIds[p])) structure[p] = 1;
        if (secondModel) {
          // How much of a structure the second model makes of each pixel, 0 to 1, on its own grid.
          let opinion, structureMass, h2, w2;
          if (secondModel.kind === "prompted") {
            // A prompted model: as much as the phrase that fits the pixel best says it is.
            opinion = await promptFrame(files[i], vehicle.structureClasses, secondModel);
            check();
            ({ height: h2, width: w2 } = opinion);
            const stride2 = h2 * w2;
            structureMass = new Float32Array(stride2);
            for (let k = 0; k < opinion.prompts; k++) for (let p = 0; p < stride2; p++) {
              const v = opinion.probabilities[k * stride2 + p];
              if (v > structureMass[p]) structureMass[p] = v;
            }
          } else {
            opinion = await segmentFrame(files[i], secondModel);
            check();
            // The probability the second model gives its structure classes, summed per pixel: a
            // softmax over its logits, with the maximum subtracted first so the exponentials cannot
            // overflow. No one class need win — a rail is spread over four of them.
            const { data, classes } = opinion.logits;
            ({ height: h2, width: w2 } = opinion.logits);
            const stride2 = h2 * w2;
            structureMass = new Float32Array(stride2);
            for (let p = 0; p < stride2; p++) {
              let max = -Infinity;
              for (let c = 0; c < classes; c++) { const v = data[c * stride2 + p]; if (v > max) max = v; }
              let sum = 0, mass = 0;
              for (let c = 0; c < classes; c++) { const e = Math.exp(data[c * stride2 + p] - max); sum += e; if (secondIds.has(c)) mass += e; }
              structureMass[p] = mass / sum;
            }
          }
          let pixels = 0;
          // The two logit grids agree in size for the 512-input SegFormers; a different one is
          // sampled nearest onto the grass model's grid, the same rule the grid applies to a mask.
          for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
            const p2 = Math.floor(y * h2 / h) * w2 + Math.floor(x * w2 / w);
            if (structureMass[p2] >= vehicle.structureFloor) { structure[y * w + x] = 1; pixels += 1; }
          }
          second = { modelId: secondModel.id, modelRevision: secondModel.revision, runtime: secondModel.runtime, kind: secondModel.kind ?? "classes",
            ...(secondModel.dtype ? { dtype: secondModel.dtype } : {}), classes: vehicle.structureClasses,
            floor: vehicle.structureFloor, logitsWidth: w2, logitsHeight: h2, structurePixels: pixels, inferenceMs: opinion.timing.inferMs, modelLoadMs: opinion.timing.loadMs };
        }
        // Everything either model called a structure, grown by `excludeNearPx`, taken out of the mask.
        for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
          const p = y * w + x;
          if (!mask.mask[p]) continue;
          let near = false;
          for (let dy = -r; dy <= r && !near; dy++) for (let dx = -r; dx <= r && !near; dx++) {
            const yy = y + dy, xx = x + dx;
            if (yy >= 0 && yy < h && xx >= 0 && xx < w && structure[yy * w + xx]) near = true;
          }
          if (near) { mask.mask[p] = 0; droppedNearStructure += 1; }
        }
        mask.counts.kept -= droppedNearStructure;
      }
      frame.status = mask.counts.kept ? "segmented" : "no-grass-detected";
      frame.mask = { width: mask.width, height: mask.height, runs: encodeRuns(mask.mask), sha256: hash(mask.mask) };
      frame.semantic = { kind: "semantic", modelId: DEFAULT_MODEL.id, modelRevision: DEFAULT_MODEL.revision,
        runtime: DEFAULT_MODEL.runtime, device: "cpu", probabilityFloor: mask.minProbability, classes: semanticClasses,
        excludedNear: structure ? { classes: vehicle.excludeNear, radiusPx: vehicle.excludeNearPx, dropped: droppedNearStructure,
          structurePixels: structure.reduce((n, v) => n + v, 0) } : null,
        structureModel: second,
        counts: mask.counts, inferenceMs: result.timing.inferMs, modelLoadMs: result.timing.loadMs };
      inputs.push({ frameIndex: i, geometryFrame: geometryFrames[i], grassMask: mask.mask, maskWidth: mask.width, maskHeight: mask.height,
        ...(structure ? { structureMask: structure } : {}) });
    } catch (error) { check(); frame.status = "failed"; frame.error = error.message; }
    const thumbnail = await sharp(bytes).resize({ width: 480, height: 480, fit: "inside", withoutEnlargement: true }).jpeg({ quality: 78 }).toBuffer();
    images.push(`data:image/jpeg;base64,${thumbnail.toString("base64")}`);
    frame.referenceLines = [];
    frames.push(frame);
    progress("segmenting", i + 1, files.length);
  }

  // Where the band goes. A fixed offset to a fixed side was right for every walked fixture and
  // wrong for every driven segment of 2026-09-13, so `auto` reads side, distance and width off
  // the masks just segmented: a sample of frames is back-projected and the band is placed where
  // the vegetation actually lies. The decision and its evidence travel in the packet.
  const band = { requested: vehicle.offsetSide, offsetM: request.offsetM ?? 2, widthM: vehicle.gridOptions.maxDistanceFromRoadM,
    side: "given", reason: "as-requested", share: null, nearM: null, medianM: null, farM: null, sampledFrames: 0 };
  let edgeError = null;
  if (fit && !trackTooShort) {
    if (vehicle.offsetSide === "auto" && inputs.length) {
      const sample = [];
      const step = Math.max(1, Math.floor(inputs.length / 12));
      for (let n = 0; n < inputs.length; n += step) {
        const f = inputs[n];
        const onGrid = T.resampleMaskNearest(f.grassMask, f.maskWidth, f.maskHeight, f.geometryFrame.width, f.geometryFrame.height);
        const back = T.backprojectMask(f.geometryFrame, onGrid, { erodeRadius: 2, maxRelativeDepthStep: 0.05 });
        const world = T.transformPoints(back.points, worldFromDa3);
        for (let k = 0; k + 2 < world.length; k += 3 * 4) sample.push(world[k], world[k + 1], world[k + 2]);
        band.sampledFrames += 1;
      }
      const profile = lateralProfile(sample, track, fit.plane.normal, fit.plane);
      Object.assign(band, chooseBand(profile, { offsetM: request.offsetM ?? 2, widthM: vehicle.gridOptions.maxDistanceFromRoadM }));
      if (band.side !== "given" && !vehicle.widthPinned) vehicle.gridOptions.maxDistanceFromRoadM = band.widthM;
      // The edge was placed on the vegetation's side of the track, so the band need not fold the
      // road in with it: the verge continues outward from the edge, on the offset's own sign.
      if (band.side !== "given" && !vehicle.bandSidePinned) vehicle.gridOptions.bandSide = band.offsetM > 0 ? "positive" : "negative";
    }
    try {
      edge = T.roadEdgeFromCameraTrack(positions, fit.plane, band.offsetM);
    } catch (error) { edgeError = error.message; }
  }
  const inverse = invert4x4(worldFromDa3);
  const projected = (point, index) => {
    const p = projectToPixel(apply4x4(inverse, point), arrays.extrinsics.data, arrays.intrinsics.data, index);
    return p ? [p[0] / geometryFrames[index].width, p[1] / geometryFrames[index].height] : null;
  };
  if (edge) {
    for (const frame of frames) {
      const i = frame.frameIndex;
      frame.referenceLines = edge.polyline.slice(1).map((point, n) => [projected(edge.polyline[n], i), projected(point, i)]).filter((line) => line.every(Boolean));
    }
  }
  const input = edge && fit ? { runId: run.id, frames: inputs, worldFromDa3, roadEdgeWorld: edge.polyline,
    ground: { plane: fit.plane, gravityUp, planeRmseM: fit.rmse }, options: { ...vehicle.gridOptions } } : null;
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
  const corridor = { source: "camera-track-offset", offsetM: band.offsetM, widthM: vehicle.gridOptions.maxDistanceFromRoadM,
    lengthM: edge?.lengthM ?? null, polyline: edge?.polyline ?? [], side: "unsigned-both-sides-folded",
    status: trackTooShort ? "degenerate" : "assumed", band, error: edgeError ?? groundError,
    cameraTrack: { lengthM: trackLength.lengthM, endToEndM: trackLength.endToEndM, poses: positions.length, minTrackM: vehicle.minTrackM } };
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
    assessment, cells, frames, ground, corridor, scale, comparison, semanticClasses,
    quality: qualitySummary(assessment, frames, context, ground, corridor, scale),
    reproducibility: { model: { id: DEFAULT_MODEL.id, revision: DEFAULT_MODEL.revision, runtime: DEFAULT_MODEL.runtime, device: "cpu", dtype: "fp32" },
      implementation,
      inputs: { modelWeightsSha256: weights ? hash(weights) : null, npzSha256: hash(await readFile(run.npz)), glbSha256: hash(await readFile(run.glb)), manifestSha256: hash(await readFile(run.manifestPath)) },
      worldFromDa3, options: input?.options ?? null, sourceFrames: "all saved sampled frames, in recorded order", manifest },
    timing: { totalMs: performance.now() - started, segmentationMs: frames.reduce((s, f) => s + (f.semantic?.inferenceMs ?? 0), 0) },
  };
  bundle.contentSha256 = hash(JSON.stringify({ assessment: assessment && { ...assessment, review: undefined }, cells, frames: frames.map(({ semantic, ...f }) => ({ ...f, semantic: semantic && { ...semantic, inferenceMs: undefined, modelLoadMs: undefined } })), ground, corridor, scale, comparison, reproducibility: bundle.reproducibility, context }));
  return { bundle, images };
}
