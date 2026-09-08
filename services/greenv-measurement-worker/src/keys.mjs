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

export const measurementPrefix = (prefix) => `${prefix}/measurement`;
export const measurementArtifact = (prefix, name) => `${measurementPrefix(prefix)}/${name}`;
export const measurementResult = (prefix) => `${measurementPrefix(prefix)}/measurement-result-v1.json`;
