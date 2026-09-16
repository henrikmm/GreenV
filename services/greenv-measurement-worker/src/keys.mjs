// Object keys, in the shape `CaptureObjectKeys` already writes them.
//
// The prefix is the frame extractor's `outputPrefix` and everything this worker produces lands
// under it, beside the frames it was computed from, so a segment is one directory rather than a
// join across two naming schemes.

const pad = (index) => String(index).padStart(8, "0");

export const segmentPrefix = (sessionId, segmentIndex) =>
  `capture-sessions/${sessionId}/segments/${pad(segmentIndex)}`;

export const segmentManifest = (prefix) => `${prefix}/segment-manifest-v2.json`;
export const frameMetadata = (prefix) => `${prefix}/frame-metadata-v2.json`;
export const sampledFrame = (prefix, fileName) => `${prefix}/sampled-frames/${fileName}`;

// Where the RunPod handler leaves the reconstruction it computed for a segment
// (services/greenv-depth-runpod/handler.py, `output_prefix`): beside the frames, under the run
// id the depth service named. Kept, so a re-measure can start from geometry it already paid for.
export const depthArtifact = (prefix, runId, name) => `${prefix}/depth/${runId}/${name}`;

/**
 * Where one window's packet lands.
 *
 * <p>A segment used to produce exactly one measurement, and it lived at `measurement/`. Since the
 * extractor stopped spending its frame budget on the first 40 m, a segment is a row of 25 m
 * windows, each its own reconstruction and its own reading, and each gets a directory of its own.
 * A segment with a single window keeps the old path, so every packet measured before this change
 * is still where its row says it is.
 */
export const measurementPrefix = (prefix, windowIndex = null) =>
  windowIndex === null
    ? `${prefix}/measurement`
    : `${prefix}/measurement/w${String(windowIndex).padStart(2, "0")}`;
export const measurementArtifact = (prefix, name, windowIndex = null) =>
  `${measurementPrefix(prefix, windowIndex)}/${name}`;
export const measurementResult = (prefix, windowIndex = null) =>
  `${measurementPrefix(prefix, windowIndex)}/measurement-result-v1.json`;
