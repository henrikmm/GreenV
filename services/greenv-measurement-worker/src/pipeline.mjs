// One captured segment, from frames to a grass assessment.
//
// The order below is not arbitrary. Every step that costs money or time is preceded by the
// cheapest check that could rule it out, because the expensive one here wakes a GPU:
//
//   read manifest -> already measured? -> download frames -> infer -> lay out run ->
//   assess -> verify the packet -> publish -> discard the run
//
// Failures carry a code in the frame extractor's vocabulary (`snake_case`, retryable or not) so
// the two workers report trouble the same way and one dashboard can read both.

import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { artifactOfKind } from "./infer.mjs";
import { buildFrameContext, segmentContext, sampledTrackLength } from "./frame-context.mjs";
import { materialiseRun, discardRun } from "./run-directory.mjs";
import { PACKET_FILES } from "./measure.mjs";
import * as keys from "./keys.mjs";

export const RESULT_SCHEMA = "greenv.measurement-result/1.0.0";

export class MeasurementError extends Error {
  constructor(code, message, retryable = false) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }
}

/**
 * The earlier run's reconstruction, when both of its artifacts are still beside the frames.
 *
 * Shaped as the depth manifest `materialiseRun` reads, so the rest of the pipeline cannot tell
 * a kept reconstruction from a fresh one — except through `depth.reused` in the packet, which
 * is the one place the difference belongs.
 */
async function keptDepth(storage, prefix, existing) {
  const runId = String(existing.runId);
  const [glb, npz] = await Promise.all([
    storage.get(keys.depthArtifact(prefix, runId, "scene.glb")).catch(() => null),
    storage.get(keys.depthArtifact(prefix, runId, "result.npz")).catch(() => null),
  ]);
  if (!glb || !npz) return null;
  return {
    glb,
    npz,
    depth: {
      run_id: runId,
      model_repository_id: existing.depth?.modelRepositoryId ?? null,
      model_revision: existing.depth?.modelRevision ?? null,
      frames: { count: existing.depth?.framesDescribed ?? null },
      timing: { gpu_seconds: null },
      artifacts: [
        { kind: "glb", name: "scene.glb", size_bytes: glb.length },
        { kind: "npz", name: "result.npz", size_bytes: npz.length },
      ],
    },
  };
}

export function measurementPipeline({ config, storage, infer, runner, log = () => {} }) {
  /**
   * @param request {{sessionId: string, segmentIndex: number, outputPrefix?: string,
   *                 rodovia?: string|null, sentido?: string|null, force?: boolean}}
   */
  return async function measure(request) {
    const { sessionId, segmentIndex } = request;
    if (!sessionId || !Number.isInteger(segmentIndex) || segmentIndex < 0) {
      throw new MeasurementError("invalid_measurement_request", "sessionId and a non-negative segmentIndex are required");
    }
    const prefix = request.outputPrefix ?? keys.segmentPrefix(sessionId, segmentIndex);

    const manifest = await storage.getJson(keys.segmentManifest(prefix)).catch(() => null);
    if (!manifest) {
      throw new MeasurementError(
        "segment_manifest_absent",
        `no segment manifest at ${keys.segmentManifest(prefix)}; the frames are not ready`,
        true,
      );
    }
    const sampledFrames = manifest.sampledFrames ?? [];
    if (sampledFrames.length < 2) {
      throw new MeasurementError("insufficient_frames", `segment published ${sampledFrames.length} sampled frame(s); depth needs at least 2`);
    }

    // Idempotency, in the extractor's own idiom: a result already computed from these exact
    // source objects is returned rather than recomputed, because recomputing means paying for
    // the GPU twice for the same answer.
    const existing = await storage.getJson(keys.measurementResult(prefix)).catch(() => null);
    const sameSource = existing && existing.sourceGeneration === manifest.sourceGeneration;
    if (!request.force && sameSource) {
      log({ event: "already-measured", prefix, runId: existing.runId });
      return { ...existing, reused: true };
    }

    const telemetry = await storage.getJson(keys.frameMetadata(prefix)).catch(() => null);
    if (!Array.isArray(telemetry)) {
      throw new MeasurementError("frame_metadata_absent", `no frame metadata at ${keys.frameMetadata(prefix)}`, true);
    }

    log({ event: "downloading", prefix, frames: sampledFrames.length });
    const frames = [];
    for (const record of sampledFrames) {
      const key = keys.sampledFrame(prefix, record.fileName);
      const bytes = await storage.get(key).catch(() => null);
      if (!bytes) throw new MeasurementError("sampled_frame_absent", `sampled frame ${record.fileName} is missing`, true);
      // The bytes prove the frame is there and feed the upload dialect; the key is what the
      // by-reference one sends. Reading either way keeps this loop the single place that decides
      // a frame is missing.
      frames.push({ name: record.fileName, key, bytes });
    }

    // A re-measure of unchanged frames can start from the reconstruction the depth handler left
    // beside them, and it should: the geometry is the expensive half and it has not changed. The
    // previous packet names the run, and the run's two artifacts have to both be there; anything
    // less falls through to a fresh inference rather than to a guess.
    const kept = (request.reuseDepth ?? config.measurement.reuseDepth) && sameSource && existing.runId && existing.mock !== true
      ? await keptDepth(storage, prefix, existing)
      : null;

    let depth, glb, npz;
    if (kept) {
      log({ event: "reusing-depth", prefix, runId: kept.depth.run_id });
      ({ depth, glb, npz } = kept);
    } else {
      log({ event: "inferring", prefix, frames: frames.length, service: config.infer.target });
      try {
        depth = await infer.infer(frames, { sourceDurationSeconds: manifest.durationMillis / 1000 });
      } catch (error) {
        // A depth service that is asleep, cold or rate-limited is worth retrying; a rejected
        // request is not, and the difference is the status the client reported.
        throw new MeasurementError("depth_inference_failed", error.message, !/ 4\d\d[:,]/.test(error.message));
      }

      const glbDescriptor = artifactOfKind(depth, "glb");
      const npzDescriptor = artifactOfKind(depth, "npz");
      if (!glbDescriptor || !npzDescriptor) {
        throw new MeasurementError("depth_artifacts_missing", "depth manifest lists no glb or no npz artifact");
      }
      [glb, npz] = await Promise.all([infer.artifact(glbDescriptor), infer.artifact(npzDescriptor)]);
    }

    const run = await materialiseRun({ runsRoot: config.measurement.runsRoot, manifest: depth, glb, npz, frames });
    if (run.isMock && !config.measurement.allowMock) {
      await discardRun(run.directory);
      throw new MeasurementError(
        "depth_service_is_a_mock",
        "the depth service answered with fixture geometry, which describes another scene entirely. " +
          "Point GREENV_INFER_BASE_URL at a real service, or set GREENV_MEASUREMENT_ALLOW_MOCK=true " +
          "to accept a wiring-only packet.",
      );
    }

    const { frameContext, positions } = buildFrameContext(sampledFrames, telemetry, request);
    const context = segmentContext(positions, request);
    const trackLengthM = config.measurement.scaleAnchor === "telemetry" ? sampledTrackLength(manifest) : null;
    // Only the ceilings the deployment named; an absent one leaves Verge Studio's own default.
    const gridOptions = {
      ...(config.measurement.maxHeightM === null ? {} : { maxHeightM: config.measurement.maxHeightM }),
      ...(config.measurement.canopyExtentM === null ? {} : { canopyExtentM: config.measurement.canopyExtentM }),
    };

    const output = await mkdtemp(join(tmpdir(), "greenv-measurement-"));
    try {
      log({ event: "measuring", prefix, runId: run.runId, classes: config.measurement.classes });
      const { summary, artifacts } = await runner.assess({
        runId: run.runId,
        offsetM: config.measurement.offsetM,
        offsetSide: config.measurement.offsetSide,
        minTrackM: config.measurement.minTrackM,
        cameraHeightM: config.measurement.cameraHeightM,
        trackLengthM,
        groundFallback: config.measurement.groundFallback,
        ...(Object.keys(gridOptions).length ? { gridOptions } : {}),
        classes: config.measurement.classes,
        context,
        frameContext,
      }, output);

      const result = {
        schemaVersion: RESULT_SCHEMA,
        sessionId,
        segmentIndex,
        outputPrefix: prefix,
        // Ties the answer to the exact bytes it was computed from, so a re-uploaded segment is
        // measured again instead of silently reusing the previous reading.
        sourceGeneration: manifest.sourceGeneration,
        runId: run.runId,
        // A mock run reaches here only when the deployment allowed it. Saying so in the result
        // is what keeps it out of an operations decision.
        mock: run.isMock,
        depth: {
          service: kept ? existing.depth?.service ?? config.infer.target : config.infer.target,
          modelRepositoryId: depth.model_repository_id ?? null,
          modelRevision: depth.model_revision ?? null,
          framesDescribed: run.frameCount,
          framesSent: frames.length,
          gpuSeconds: depth.timing?.gpu_seconds ?? null,
          // True when no GPU ran for this packet: the geometry is the earlier run's, re-measured.
          reused: Boolean(kept),
        },
        measurement: {
          classes: config.measurement.classes.split(",").map((label) => label.trim()),
          offsetM: config.measurement.offsetM,
          offsetSide: config.measurement.offsetSide,
          minTrackM: config.measurement.minTrackM,
          cameraHeightM: config.measurement.cameraHeightM,
          scaleAnchor: config.measurement.scaleAnchor,
          trackLengthM,
          maxHeightM: config.measurement.maxHeightM,
          canopyExtentM: config.measurement.canopyExtentM,
          groundFallback: config.measurement.groundFallback,
          contentSha256: summary.contentSha256,
          quality: summary.quality,
          timing: summary.timing,
          artifacts: Object.fromEntries(PACKET_FILES.map((name) => [name, keys.measurementArtifact(prefix, name)])),
        },
        // Verge Studio keeps exactly four road fields and cannot invent a km, so the coordinates
        // it never sees are carried here. Turning these into (rodovia, sentido, km) needs the
        // highway reference GreenV owns; until that exists, `km` in the packet is null and this
        // is the evidence a later pass would use.
        context,
        positions,
        measuredAt: new Date().toISOString(),
      };

      log({ event: "publishing", prefix, runId: run.runId });
      for (const name of PACKET_FILES) {
        await storage.put(keys.measurementArtifact(prefix, name), artifacts[name]);
      }
      await storage.put(keys.measurementResult(prefix), Buffer.from(`${JSON.stringify(result, null, 2)}\n`));

      return result;
    } finally {
      await rm(output, { recursive: true, force: true });
      await discardRun(run.directory);
    }
  };
}
