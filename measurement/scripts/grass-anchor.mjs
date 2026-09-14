// Three decisions a vehicle-mounted capture forces on the grass pipeline, as pure functions so
// each can be tested without a reconstruction and read without the pipeline around it.
//
// All three came out of one day of driving on 2026-09-13 (docs/evidence/2026-09-13-car-mount.md):
//
//   - the verge was on the OTHER side of the camera track from where the fixed `offsetM` put
//     the band, in every one of 37 usable segments, which left 12 of them with no cells at all
//     although every frame carried a vegetation mask — and at what distance it lay (5 to 9 m
//     from the camera, behind a shoulder and a guardrail) no fixed number could know either;
//   - four segments reconstructed a camera track of 0.1 to 1.8 m against a car that drove 30 m
//     or more — a phone still being mounted, or a stopped car — and were reported as 1.1 to
//     3.9 m of vegetation because a door handle and a tree were the only things in the band;
//   - DA3 fixes its metric scale once per clip, and against the GPS path of the very frames it
//     reconstructed it ran from 0.78x to 1.86x on neighbouring segments of one drive.
//
// None of these is on by default. Verge Studio's own fixtures were walked, not driven, and their
// recorded numbers must not move because a car needs different rules; a caller that has a car
// asks for each of these explicitly and the packet records what was asked and what was done.

// The three vector helpers are repeated here rather than imported from `geometry/types.ts`,
// which is TypeScript and only reachable through the inspector's on-demand compile. Their
// conventions are the geometry package's own: a plane is `dot(normal, p) + offset === 0`.
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const normalize = (a) => { const len = Math.hypot(a[0], a[1], a[2]); return len > 1e-12 ? [a[0] / len, a[1] / len, a[2] / len] : null; };
const signedHeight = (plane, p) => dot(plane.normal, p) + plane.offset;
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

/** Nearest-rank percentile of an already sorted array. */
function percentileOfSorted(sorted, p) {
  if (!sorted.length) return NaN;
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))];
}

/** A point projected onto a plane, for measuring along the ground rather than through the air. */
export function ontoPlane(point, plane) {
  const h = signedHeight(plane, point);
  return [point[0] - plane.normal[0] * h, point[1] - plane.normal[1] * h, point[2] - plane.normal[2] * h];
}

/** Arc length of a polyline, and its end-to-end distance, so a curled track can be told from a short one. */
export function polylineLength(points) {
  let lengthM = 0;
  for (let i = 1; i < points.length; i++) {
    lengthM += Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1], points[i][2] - points[i - 1][2]);
  }
  const first = points[0], last = points[points.length - 1];
  const endToEndM = points.length > 1 ? Math.hypot(last[0] - first[0], last[1] - first[1], last[2] - first[2]) : 0;
  return { lengthM, endToEndM };
}

/**
 * Where a set of points lies relative to the camera track: one signed lateral distance each.
 *
 * Signed against the same `cross(normal, travel)` the road edge uses, so the sign here is the
 * sign `offsetM` needs. Travel is taken over a window of poses because consecutive poses at
 * 10 fps are centimetres apart and their difference is mostly noise. `points` is flat xyz,
 * already on the plane or not — it is projected here.
 */
export function lateralProfile(points, trackOnPlane, planeNormal, plane = null, { window = 3 } = {}) {
  const empty = { laterals: new Float64Array(0), negativeShare: NaN };
  if (trackOnPlane.length < 2) return empty;
  const normal = normalize(planeNormal);
  if (!normal) return empty;
  const sideways = trackOnPlane.map((_, i) => {
    const a = trackOnPlane[Math.max(0, i - window)], b = trackOnPlane[Math.min(trackOnPlane.length - 1, i + window)];
    const travel = normalize([b[0] - a[0], b[1] - a[1], b[2] - a[2]]);
    return travel ? normalize(cross(normal, travel)) : null;
  });
  const fallback = sideways.find(Boolean);
  if (!fallback) return empty;
  const stride = Math.max(1, Math.floor(trackOnPlane.length / 24));
  const laterals = [];
  let negative = 0;
  for (let k = 0; k + 2 < points.length; k += 3) {
    const raw = [points[k], points[k + 1], points[k + 2]];
    const p = plane ? ontoPlane(raw, plane) : raw;
    let best = Infinity, at = 0;
    for (let i = 0; i < trackOnPlane.length; i += stride) {
      const d = (p[0] - trackOnPlane[i][0]) ** 2 + (p[1] - trackOnPlane[i][1]) ** 2 + (p[2] - trackOnPlane[i][2]) ** 2;
      if (d < best) { best = d; at = i; }
    }
    const c = trackOnPlane[at], s = sideways[at] ?? fallback;
    const lateral = (p[0] - c[0]) * s[0] + (p[1] - c[1]) * s[1] + (p[2] - c[2]) * s[2];
    if (lateral < 0) negative += 1;
    laterals.push(lateral);
  }
  return { laterals: Float64Array.from(laterals), negativeShare: laterals.length ? negative / laterals.length : NaN };
}

/**
 * The band that covers the vegetation: which side of the track, how far out it starts, how wide.
 *
 * The road edge polyline is placed just inside the near edge of the vegetation mass (its 10th
 * percentile, less a margin) and the band reaches its far edge (90th percentile), within a cap.
 * Both come from the masks of the run itself, so a shoulder of 2 m or of 6 m is measured
 * equally, which no fixed offset can do. A mass that straddles the track — a median strip, a
 * track that curls back on itself — keeps the caller's own offset and width rather than a guess,
 * and the reason says so.
 */
export function chooseBand(profile, given, { decisiveShare = 0.7, marginM = 0.5, minWidthM = 5, maxWidthM = 10, maxOffsetM = 12 } = {}) {
  const asGiven = (reason) => ({ offsetM: given.offsetM, widthM: given.widthM, side: "given", reason, share: profile.negativeShare, nearM: null, medianM: null, farM: null });
  if (!profile.laterals.length || !Number.isFinite(profile.negativeShare)) return asGiven("no-mask-points");
  let sign;
  if (profile.negativeShare >= decisiveShare) sign = -1;
  else if (profile.negativeShare <= 1 - decisiveShare) sign = 1;
  else return asGiven("mask-straddles-track");
  const onSide = Array.from(profile.laterals).filter((l) => Math.sign(l) === sign).map(Math.abs).sort((a, b) => a - b);
  const nearM = percentileOfSorted(onSide, 10), medianM = percentileOfSorted(onSide, 50), farM = percentileOfSorted(onSide, 90);
  const magnitude = clamp(nearM - marginM, 0, maxOffsetM);
  const widthM = clamp(farM - magnitude + marginM, minWidthM, maxWidthM);
  return { offsetM: sign * magnitude, widthM, side: sign < 0 ? "negative" : "positive", reason: "mask-mass", share: profile.negativeShare, nearM, medianM, farM };
}

/** The bounds a scale factor may take before it is a wrong plane or a wrong track rather than a wrong scale. */
const SCALE_RANGE = [0.5, 2];

/**
 * The factor that makes the reconstructed camera track as long as the vehicle actually drove.
 *
 * DA3's scale is one scalar per clip, so one scalar corrects it, and the length of the camera
 * track is the one quantity that both the reconstruction and the capture's own telemetry
 * measure directly — the frame extractor writes the GPS path length of every sampled frame into
 * the manifest. Refused when either length is too short to carry a ratio, or the ratio is beyond
 * what a wrong scale could plausibly be: a factor of 5 is a track that curled or collapsed, and
 * stretching it would only make a wrong shape a bigger wrong shape.
 */
export function trackLengthScale(expectedM, measuredM, { minExpectedM = 5, minMeasuredM = 2, range = SCALE_RANGE } = {}) {
  if (!(expectedM > 0)) return { factor: 1, applied: false, reason: "no-anchor" };
  if (expectedM < minExpectedM) return { factor: 1, applied: false, reason: "expected-track-too-short" };
  if (!(measuredM >= minMeasuredM)) return { factor: 1, applied: false, reason: "reconstructed-track-too-short" };
  const factor = expectedM / measuredM;
  if (factor < range[0] || factor > range[1]) return { factor: 1, applied: false, reason: "factor-out-of-range", proposedFactor: factor };
  return { factor, applied: true, reason: "track-length" };
}

/**
 * The factor that puts the camera at a known height above the road, for a caller that has one.
 *
 * Refused when the measured height is not a height (a collapsed track puts the camera in the
 * plane) or the factor is beyond the range above.
 */
export function cameraHeightScale(anchorHeightM, measuredHeightM, { minMeasuredM = 0.3, range = SCALE_RANGE } = {}) {
  if (!(anchorHeightM > 0)) return { factor: 1, applied: false, reason: "no-anchor" };
  if (!(measuredHeightM >= minMeasuredM)) return { factor: 1, applied: false, reason: "camera-not-above-plane" };
  const factor = anchorHeightM / measuredHeightM;
  if (factor < range[0] || factor > range[1]) return { factor: 1, applied: false, reason: "factor-out-of-range", proposedFactor: factor };
  return { factor, applied: true, reason: "camera-height" };
}

/** A row-major 4x4 with its linear part and translation both multiplied by `factor`. */
export function scaleAffine(matrix, factor) {
  return Array.from(matrix, (value, index) => (index === 15 || (index >= 12 && index <= 14) ? value : value * factor));
}
