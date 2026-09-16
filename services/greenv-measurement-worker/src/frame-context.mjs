// Joining a sampled frame to the telemetry recorded for it.
//
// Two frame numberings meet here and confusing them silently attaches the wrong position to a
// measurement, which is the one error this repository cannot detect after the fact:
//
//   - `frame-metadata-v2.json` holds one `FrameTelemetry` per ENCODED frame — every frame the
//     camera produced, roughly 300 for a ten-second segment at 30 fps. No image exists for these.
//   - `segment-manifest-v2.json`'s `sampledFrames` holds one `FrameRecord` per JPEG actually
//     written, roughly 100 at the extractor's 10 fps.
//   - Verge Studio's canonical frame number is the integer in the JPEG's file name, and the
//     extractor writes `frame-%04d.jpg` starting at 1. So canonical = FrameRecord.index + 1.
//
// The bridge between the first two is time. `FrameRecord.timestampSeconds` is NOMINAL — the
// extractor computes it as `index / effectiveFps` rather than reading the output frame's own
// presentation timestamp — so this match is approximate by construction. At 60 km/h a one-frame
// error is about 0.55 m of road, which is under the half-metre cell size but not under it by
// much. It is good enough to place a segment on a highway and not good enough to attribute a
// single cell to a surveyed point; `positions` below exists so that judgement stays with GreenV.

/** The integer Verge Studio will parse out of a sampled frame's file name. */
export function canonicalFrameNumber(fileName) {
  const match = /(\d+)/.exec(String(fileName ?? ""));
  if (!match) throw new Error(`sampled frame name carries no number: ${fileName}`);
  return Number(match[1]);
}

function nearestByTime(telemetry, seconds) {
  let best = null;
  let bestDistance = Infinity;
  for (const frame of telemetry) {
    const at = Number(frame.presentationTimeNanos) / 1e9;
    if (!Number.isFinite(at)) continue;
    const distance = Math.abs(at - seconds);
    if (distance < bestDistance) {
      best = frame;
      bestDistance = distance;
    }
  }
  return best === null ? null : { frame: best, offsetSeconds: bestDistance };
}

/**
 * The `frameContext` Verge Studio accepts, and the positions it deliberately does not.
 *
 * `roadContext` keeps exactly four fields and drops everything else, so latitude and longitude
 * cannot ride along inside the packet. They are returned separately instead: GreenV owns the
 * highway reference that turns a coordinate into a `marco km`, Verge Studio owns none of it, and
 * `km` stays null here rather than being invented from reconstructed distance — which
 * `measurement/docs/GRASS-QUALITY.md` names as the one substitution never to make.
 */
export function buildFrameContext(sampledFrames, telemetry, session = {}) {
  const frameContext = {};
  const positions = [];

  for (const record of sampledFrames) {
    const canonical = canonicalFrameNumber(record.fileName);
    const match = nearestByTime(telemetry, Number(record.timestampSeconds));

    frameContext[canonical] = {
      // Null unless GreenV knows them. A null here becomes the `road-metadata-missing` blocker
      // in the packet's quality summary, which is the correct and visible outcome.
      rodovia: session.rodovia ?? null,
      sentido: session.sentido ?? null,
      km: null,
      capturado_em: match?.frame?.capturedAtUtc ?? null,
    };

    positions.push({
      canonicalFrame: canonical,
      sampledIndex: record.index,
      timestampSeconds: record.timestampSeconds,
      encodedFrameIndex: match?.frame?.index ?? null,
      matchOffsetSeconds: match?.offsetSeconds ?? null,
      capturedAtUtc: match?.frame?.capturedAtUtc ?? null,
      locationQuality: match?.frame?.locationQuality ?? "unavailable",
      latitude: match?.frame?.location?.latitude ?? null,
      longitude: match?.frame?.location?.longitude ?? null,
      horizontalAccuracyMeters: match?.frame?.location?.horizontalAccuracyMeters ?? null,
      speedMetersPerSecond: match?.frame?.location?.speedMetersPerSecond ?? null,
      courseDegrees: match?.frame?.location?.courseDegrees ?? null,
      distanceFromSessionStartMeters: match?.frame?.location?.distanceFromSessionStartMeters ?? null,
    });
  }

  return { frameContext, positions };
}

/**
 * How far the vehicle drove over the sampled frames, from the extractor's own odometer.
 *
 * Under the `distance-groups` strategy the extractor stamps every sampled frame with its GPS
 * path distance from the segment's start, so the span between the first and the last sampled
 * frame is the length the reconstructed camera track ought to have. That is the scale anchor:
 * DA3 fixes one scalar per clip, and on 2026-09-13 that scalar ran from 0.78x to 1.86x against
 * this number on neighbouring segments of one drive. Null when the manifest cannot say — an
 * older strategy, a frame without a distance — and null reaches Verge Studio as "no anchor".
 *
 * <p>`frames` is what the reconstruction actually saw, which is a window's frames and not the
 * segment's since a segment became a row of 25 m windows. Measuring one window against the whole
 * segment's distance is how the anchor silently stopped working on 16 September 2026: the ratio
 * came out three to seven times too large, Verge Studio refused it as `factor-out-of-range`, and
 * every window was measured at DA3's own scale - the very drift this anchor exists to remove.
 */
export function sampledTrackLength(manifest, frames = manifest?.sampledFrames ?? []) {
  if (manifest?.samplingStrategy !== "distance-groups") return null;
  const first = frames[0]?.distanceMeters;
  const last = frames[frames.length - 1]?.distanceMeters;
  if (!Number.isFinite(first) || !Number.isFinite(last) || last < first) return null;
  return last - first;
}

/**
 * One segment-level context for the run as a whole.
 *
 * `capturado_em` is the first sampled frame's own timestamp rather than the segment's requested
 * `capturedAt`, so the four fields on the packet describe observed frames rather than a request.
 */
export function segmentContext(positions, session = {}) {
  return {
    rodovia: session.rodovia ?? null,
    sentido: session.sentido ?? null,
    km: null,
    capturado_em: positions.find((position) => position.capturedAtUtc)?.capturedAtUtc ?? null,
  };
}
